package kpi.moodle_campus_sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class MoodleService {
    private static final Logger log = LoggerFactory.getLogger(MoodleService.class);

    private static final String PARAM_WSTOKEN = "wstoken";
    private static final String PARAM_WSFUNC = "wsfunction";
    private static final String PARAM_FORMAT = "moodlewsrestformat";
    private static final String VAL_JSON = "json";

    private static final String FIELD_ID = "id";
    private static final String FIELD_COURSEID = "courseid";
    private static final String FIELD_USERID = "userid";
    private static final String FIELD_EMAIL = "email";
    private static final String FIELD_FIRSTNAME = "firstname";
    private static final String FIELD_LASTNAME = "lastname";
    private static final String FIELD_FULLNAME = "fullname";
    private static final String FIELD_SHORTNAME = "shortname";
    private static final String FIELD_SUMMARY = "summary";
    private static final String FIELD_GROUPS = "groups";

    private static final String FIELD_ITEMTYPE = "itemtype";
    private static final String FIELD_ITEMMODULE = "itemmodule";
    private static final String FIELD_ITEMINSTANCE = "iteminstance";
    private static final String FIELD_ITEMNAME = "itemname";
    private static final String FIELD_GRADEMAX = "grademax";
    private static final String FIELD_GRADERAW = "graderaw";
    private static final String FIELD_FEEDBACK = "feedback";
    private static final String FIELD_DATEGRADED = "gradedategraded";

    private static final String VAL_MOD = "mod";
    private static final String VAL_ASSIGN = "assign";
    private static final String NO_GROUP = "Без групи";
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    // ----------------------------------------------------------------

    @Value("${moodle.api.token}")
    private String token;

    @Value("${moodle.api.url}")
    private String moodleUrl;

    private final HtmlSanitizer htmlSanitizer;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MoodleService(HtmlSanitizer htmlSanitizer) {
        this.htmlSanitizer = htmlSanitizer;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    private String sendGetRequest(String url) throws Exception {
        log.info("Відправка HTTP GET запиту до Moodle API: {}", url.split(PARAM_WSTOKEN + "=")[0]);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Moodle повернув нестандартний статус код: {}", response.statusCode());
        }
        return response.body();
    }

    private String formatUnixDate(long timestamp) {
        if (timestamp <= 0) return null;
        return DateTimeFormatter.ofPattern(DATE_FORMAT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(timestamp));
    }

    public JsonNode getUserDataByEmail(String email) throws Exception {
        String url = moodleUrl + "?" + PARAM_WSTOKEN + "=" + token +
                "&" + PARAM_WSFUNC + "=core_user_get_users&" + PARAM_FORMAT + "=" + VAL_JSON +
                "&criteria[0][key]=" + FIELD_EMAIL + "&criteria[0][value]=" + URLEncoder.encode(email, StandardCharsets.UTF_8);

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        JsonNode users = root.path("users");
        return (users.isArray() && !users.isEmpty()) ? users.get(0) : null;
    }

    public JsonNode getUserById(int userId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_user_get_users")
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .queryParam("criteria[0][key]", FIELD_ID)
                .queryParam("criteria[0][value]", userId)
                .build().toUriString();

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        JsonNode users = root.path("users");
        return (users.isArray() && !users.isEmpty()) ? users.get(0) : null;
    }

    public JsonNode getAllCourses() throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_course_get_courses")
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        String[] dateFields = {"startdate", "enddate", "timecreated", "timemodified"};

        if (root.isArray()) {
            for (JsonNode courseNode : root) {
                ObjectNode objNode = (ObjectNode) courseNode;

                if (objNode.has(FIELD_SUMMARY)) {
                    objNode.put(FIELD_SUMMARY, htmlSanitizer.sanitize(objNode.get(FIELD_SUMMARY).asText()));
                }

                for (String field : dateFields) {
                    if (objNode.has(field)) {
                        long timestamp = objNode.get(field).asLong(0);
                        String formatted = formatUnixDate(timestamp);
                        if (formatted != null) {
                            objNode.put(field, formatted);
                        } else {
                            objNode.putNull(field);
                        }
                    }
                }
            }
        }
        return root;
    }

    public ObjectNode getCourseTeachers(int courseId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_enrol_get_enrolled_users")
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode allUsers = objectMapper.readTree(sendGetRequest(url));
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode teachersArray = objectMapper.createArrayNode();

        if (allUsers.isArray()) {
            for (JsonNode user : allUsers) {
                boolean isTeacher = false;
                for (JsonNode role : user.path("roles")) {
                    if (role.path(FIELD_SHORTNAME).asText().toLowerCase().contains("teacher")) {
                        isTeacher = true;
                        break;
                    }
                }
                if (isTeacher) {
                    ObjectNode teacherInfo = objectMapper.createObjectNode();
                    teacherInfo.put(FIELD_ID, user.path(FIELD_ID).asInt());
                    teacherInfo.put(FIELD_FIRSTNAME, user.path(FIELD_FIRSTNAME).asText());
                    teacherInfo.put(FIELD_LASTNAME, user.path(FIELD_LASTNAME).asText());
                    teacherInfo.put("role_name", user.path("roles").get(0).path(FIELD_SHORTNAME).asText());
                    teacherInfo.put(FIELD_EMAIL, user.path(FIELD_EMAIL).asText(null));
                    teachersArray.add(teacherInfo);
                }
            }
        }
        result.put("course_id", courseId);
        result.set("teachers", teachersArray);
        return result;
    }

    public ObjectNode getCourseGradeItems(int courseId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "gradereport_user_get_grade_items")
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode itemsArray = objectMapper.createArrayNode();
        JsonNode userGrades = root.path("usergrades");

        if (userGrades.isArray() && !userGrades.isEmpty()) {
            JsonNode gradeItems = userGrades.get(0).path("gradeitems");
            if (gradeItems.isArray()) {
                for (JsonNode item : gradeItems) {
                    if (VAL_MOD.equals(item.path(FIELD_ITEMTYPE).asText())) {
                        ObjectNode gNode = objectMapper.createObjectNode();
                        gNode.put(FIELD_ID, item.path(FIELD_ID).asInt());
                        gNode.put("name", item.path(FIELD_ITEMNAME).asText());
                        gNode.put("module", item.path(FIELD_ITEMMODULE).asText());
                        gNode.put("instance", item.path(FIELD_ITEMINSTANCE).asInt());
                        gNode.put("max_grade", item.path(FIELD_GRADEMAX).asDouble());
                        itemsArray.add(gNode);
                    }
                }
            }
        }
        result.put("course_id", courseId);
        result.set("control_activities", itemsArray);
        return result;
    }

    public Integer getGraderFromAssignment(int assignmentId, int studentId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "mod_assign_get_grades")
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .queryParam("assignmentids[0]", assignmentId)
                .build().toUriString();

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        JsonNode assignments = root.path("assignments");

        if (assignments.isArray() && !assignments.isEmpty()) {
            JsonNode grades = assignments.get(0).path("grades");
            if (grades.isArray()) {
                for (JsonNode grade : grades) {
                    if (grade.path(FIELD_USERID).asInt() == studentId) {
                        return grade.path("grader").asInt();
                    }
                }
            }
        }
        return null;
    }

    public List<JsonNode> getCoursesForUser(int userId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_enrol_get_users_courses")
                .queryParam(FIELD_USERID, userId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        List<JsonNode> courses = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(courses::add);
        }
        return courses;
    }

    public List<UserGrade> getParsedGrades(int courseId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "gradereport_user_get_grade_items")
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        return objectMapper.readValue(sendGetRequest(url), MoodleResponse.class).usergrades();
    }

    public List<ObjectNode> getBulkGradesByEmails(List<String> emails) throws Exception {
        List<ObjectNode> bulkData = new ArrayList<>();

        for (String email : emails) {
            ObjectNode studentNode = objectMapper.createObjectNode();
            studentNode.put(FIELD_EMAIL, email);

            JsonNode moodleUser = getUserDataByEmail(email);
            if (moodleUser != null) {
                int userId = moodleUser.get(FIELD_ID).asInt();
                studentNode.put("moodle_user_id", userId);
                studentNode.put(FIELD_FIRSTNAME, moodleUser.get(FIELD_FIRSTNAME).asText());
                studentNode.put(FIELD_LASTNAME, moodleUser.get(FIELD_LASTNAME).asText());

                ArrayNode coursesArray = objectMapper.createArrayNode();
                for (JsonNode course : getCoursesForUser(userId)) {
                    ObjectNode cNode = objectMapper.createObjectNode();
                    int cId = course.get(FIELD_ID).asInt();

                    cNode.put("course_id", cId);
                    cNode.put("course_fullname", course.get(FIELD_FULLNAME).asText());
                    cNode.put("course_shortname", course.get(FIELD_SHORTNAME).asText());

                    List<UserGrade> grades = getParsedGrades(cId);
                    for (UserGrade rec : grades) {
                        if (rec.userid() == userId) {
                            ArrayNode gradesArray = objectMapper.createArrayNode();
                            for (GradeItem item : rec.gradeitems()) {
                                if (item.itemname() == null) continue;

                                ObjectNode gradeNode = objectMapper.valueToTree(item);
                                if (VAL_MOD.equals(item.itemtype()) && VAL_ASSIGN.equals(item.itemmodule()) && item.iteminstance() != null) {
                                    Integer graderId = getGraderFromAssignment(item.iteminstance(), userId);
                                    if (graderId != null && graderId > 0) {
                                        JsonNode teacher = getUserById(graderId);
                                        if (teacher != null) {
                                            ObjectNode tNode = objectMapper.createObjectNode();
                                            tNode.put(FIELD_ID, graderId);
                                            tNode.put(FIELD_FIRSTNAME, teacher.path(FIELD_FIRSTNAME).asText());
                                            tNode.put(FIELD_LASTNAME, teacher.path(FIELD_LASTNAME).asText());
                                            gradeNode.set("graded_by", tNode);
                                        }
                                    }
                                }
                                gradesArray.add(gradeNode);
                            }
                            cNode.set("grades", gradesArray);
                        }
                    }
                    coursesArray.add(cNode);
                }
                studentNode.set("enrolled_courses", coursesArray);
            } else {
                studentNode.put("status", "error").put("message", "User not found");
            }
            bulkData.add(studentNode);
        }
        return bulkData;
    }

    private Map<Integer, String> getStudentGroups(int courseId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_enrol_get_enrolled_users")
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode allUsers = objectMapper.readTree(sendGetRequest(url));
        Map<Integer, String> studentGroups = new HashMap<>();

        if (allUsers.isArray()) {
            for (JsonNode user : allUsers) {
                int userId = user.path(FIELD_ID).asInt();
                JsonNode groups = user.path(FIELD_GROUPS);
                if (groups != null && groups.isArray() && !groups.isEmpty()) {
                    studentGroups.put(userId, groups.get(0).path("name").asText());
                } else {
                    studentGroups.put(userId, NO_GROUP);
                }
            }
        }
        return studentGroups;
    }

    private Map<Integer, JsonNode> getEnrolledUsersMap(int courseId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_enrol_get_enrolled_users")
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode allUsers = objectMapper.readTree(sendGetRequest(url));
        Map<Integer, JsonNode> userMap = new HashMap<>();

        if (allUsers.isArray()) {
            for (JsonNode user : allUsers) {
                userMap.put(user.path(FIELD_ID).asInt(), user);
            }
        }
        return userMap;
    }

    public ObjectNode getCampusJournal(int courseId) throws Exception {
        String courseUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_course_get_courses_by_field")
                .queryParam("field", "id")
                .queryParam("value", courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode courseRoot = objectMapper.readTree(sendGetRequest(courseUrl));
        JsonNode courses = courseRoot.path("courses");

        String courseName = "Unknown Course";
        String courseSummary = "";

        if (courses.isArray() && !courses.isEmpty()) {
            JsonNode cNode = courses.get(0);
            courseName = cNode.path(FIELD_FULLNAME).asText();
            courseSummary = htmlSanitizer.sanitize(cNode.path(FIELD_SUMMARY).asText());
        }

        String gradesUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "gradereport_user_get_grade_items")
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode userGrades = objectMapper.readTree(sendGetRequest(gradesUrl)).path("usergrades");
        Map<Integer, ObjectNode> columnsMap = new LinkedHashMap<>();
        Map<Integer, JsonNode> enrolledUsers = getEnrolledUsersMap(courseId);

        if (userGrades.isArray()) {
            for (JsonNode studentNode : userGrades) {
                int studentId = studentNode.path(FIELD_USERID).asInt();
                String studentName = studentNode.path("userfullname").asText();
                JsonNode gradeItems = studentNode.path("gradeitems");

                if (gradeItems.isArray()) {
                    for (JsonNode item : gradeItems) {
                        if (!VAL_MOD.equals(item.path(FIELD_ITEMTYPE).asText())) continue;
                        int itemId = item.path(FIELD_ID).asInt();

                        if (!columnsMap.containsKey(itemId)) {
                            ObjectNode colNode = objectMapper.createObjectNode();
                            colNode.put("item_id", itemId);
                            colNode.put("moodle_instance_id", item.path(FIELD_ITEMINSTANCE).asInt());
                            colNode.put("column_name", item.path(FIELD_ITEMNAME).asText());
                            colNode.put("module_type", item.path(FIELD_ITEMMODULE).asText());
                            colNode.put("max_grade", item.path(FIELD_GRADEMAX).asDouble());
                            colNode.set("marks", objectMapper.createArrayNode());
                            columnsMap.put(itemId, colNode);
                        }

                        if (!item.path(FIELD_GRADERAW).isNull()) {
                            ObjectNode markNode = objectMapper.createObjectNode();
                            markNode.put("moodle_student_id", studentId); // Точно як у твоєму ендпоінті
                            markNode.put("student_name", studentName);

                            JsonNode userNode = enrolledUsers.get(studentId);
                            String groupName = NO_GROUP;
                            String studentEmail = null;
                            if (userNode != null) {
                                studentEmail = userNode.path(FIELD_EMAIL).asText(null);
                                JsonNode groups = userNode.path(FIELD_GROUPS);
                                if (groups != null && groups.isArray() && !groups.isEmpty()) {
                                    groupName = groups.get(0).path("name").asText(NO_GROUP);
                                }
                            }
                            markNode.put("student_group", groupName);
                            markNode.put(FIELD_EMAIL, studentEmail);
                            markNode.put("score", item.path(FIELD_GRADERAW).asDouble());

                            if (item.hasNonNull(FIELD_FEEDBACK) && !item.path(FIELD_FEEDBACK).asText().isBlank()) {
                                markNode.put("comment", htmlSanitizer.sanitize(item.path(FIELD_FEEDBACK).asText()));
                            } else {
                                markNode.putNull("comment");
                            }

                            String formattedDate = formatUnixDate(item.path(FIELD_DATEGRADED).asLong(0));
                            if (formattedDate != null) {
                                markNode.put("date_graded", formattedDate);
                            } else {
                                markNode.putNull("date_graded");
                            }

                            if (VAL_ASSIGN.equals(item.path(FIELD_ITEMMODULE).asText())) {
                                Integer graderId = getGraderFromAssignment(item.path(FIELD_ITEMINSTANCE).asInt(), studentId);
                                if (graderId != null && graderId > 0) {
                                    JsonNode teacher = enrolledUsers.get(graderId);
                                    if (teacher != null) {
                                        ObjectNode tNode = objectMapper.createObjectNode();
                                        tNode.put(FIELD_ID, graderId);
                                        tNode.put(FIELD_FIRSTNAME, teacher.path(FIELD_FIRSTNAME).asText(""));
                                        tNode.put(FIELD_LASTNAME, teacher.path(FIELD_LASTNAME).asText(""));
                                        tNode.put(FIELD_EMAIL, teacher.path(FIELD_EMAIL).asText(null));
                                        markNode.set("graded_by_teacher", tNode);
                                    }
                                }
                            }
                            ((ArrayNode) columnsMap.get(itemId).get("marks")).add(markNode);
                        }
                    }
                }
            }
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("moodle_course_id", courseId);
        result.put("course_name", courseName);
        result.put("course_description", courseSummary);

        ArrayNode columnsArray = objectMapper.createArrayNode();
        columnsMap.values().forEach(columnsArray::add);
        result.set("columns", columnsArray);

        return result;
    }

    public ObjectNode getTeacherCentricJournals(int courseId) throws Exception {
        String courseUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_course_get_courses_by_field")
                .queryParam("field", "id")
                .queryParam("value", courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode courseRoot = objectMapper.readTree(sendGetRequest(courseUrl));
        JsonNode courses = courseRoot.path("courses");

        String courseName = "Unknown Course";
        String courseShortname = "";
        String courseDescription = "";

        if (courses.isArray() && !courses.isEmpty()) {
            JsonNode courseData = courses.get(0);
            courseName = courseData.path(FIELD_FULLNAME).asText("Unknown Course");
            courseShortname = courseData.path(FIELD_SHORTNAME).asText("");
            courseDescription = htmlSanitizer.sanitize(courseData.path(FIELD_SUMMARY).asText(""));
        }

        String gradesUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "gradereport_user_get_grade_items")
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode userGrades = objectMapper.readTree(sendGetRequest(gradesUrl)).path("usergrades");
        Map<Integer, JsonNode> enrolledUsers = getEnrolledUsersMap(courseId);
        
        ObjectNode result = objectMapper.createObjectNode();
        result.put("course_id", courseId);
        result.put("course_name", courseName);
        result.put("course_shortname", courseShortname);
        result.put("course_description", courseDescription);

        Map<Integer, ObjectNode> teacherJournalsMap = new HashMap<>();
        Map<Integer, ObjectNode> unassignedColumnsMap = new HashMap<>();

        if (userGrades.isArray()) {
            for (JsonNode studentNode : userGrades) {
                int studentId = studentNode.path(FIELD_USERID).asInt();
                String studentName = studentNode.path("userfullname").asText();

                for (JsonNode item : studentNode.path("gradeitems")) {
                    if (!VAL_MOD.equals(item.path(FIELD_ITEMTYPE).asText()) || item.path(FIELD_GRADERAW).isNull()) continue;

                    int itemId = item.path(FIELD_ID).asInt();
                    String moduleType = item.path(FIELD_ITEMMODULE).asText();

                    JsonNode userNode = enrolledUsers.get(studentId);
                    String groupName = NO_GROUP;
                    String studentEmail = null;
                    if (userNode != null) {
                        studentEmail = userNode.path(FIELD_EMAIL).asText(null);
                        JsonNode groups = userNode.path(FIELD_GROUPS);
                        if (groups != null && groups.isArray() && !groups.isEmpty()) {
                            groupName = groups.get(0).path("name").asText(NO_GROUP);
                        }
                    }

                    if (VAL_ASSIGN.equals(moduleType)) {
                        Integer graderId = getGraderFromAssignment(item.path(FIELD_ITEMINSTANCE).asInt(), studentId);
                        if (graderId != null && graderId > 0) {
                            teacherJournalsMap.computeIfAbsent(graderId, id -> {
                                ObjectNode journal = objectMapper.createObjectNode();
                                JsonNode tInfo = enrolledUsers.get(id);
                                journal.put("teacher_id", id);
                                if (tInfo != null) {
                                    journal.put("teacher_name", tInfo.path(FIELD_FIRSTNAME).asText() + " " + tInfo.path(FIELD_LASTNAME).asText());
                                    journal.put(FIELD_EMAIL, tInfo.path(FIELD_EMAIL).asText(null));
                                } else {
                                    journal.put("teacher_name", "Unknown Teacher");
                                    journal.putNull(FIELD_EMAIL);
                                }
                                journal.set("columns", objectMapper.createObjectNode());
                                return journal;
                            });
                            ObjectNode columnsContainer = (ObjectNode) teacherJournalsMap.get(graderId).get("columns");
                            addMarkToColumn(columnsContainer, itemId, item, studentId, studentName, groupName, studentEmail);
                        }
                    } else {
                        addMarkToColumn(unassignedColumnsMap, itemId, item, studentId, studentName, groupName, studentEmail);
                    }
                }
            }
        }

        ArrayNode teacherArray = objectMapper.createArrayNode();
        for (ObjectNode j : teacherJournalsMap.values()) {
            ObjectNode finalJournal = objectMapper.createObjectNode();
            finalJournal.put("teacher_id", j.get("teacher_id").asInt());
            finalJournal.put("teacher_name", j.get("teacher_name").asText());
            finalJournal.set(FIELD_EMAIL, j.get(FIELD_EMAIL));
            ArrayNode colArray = objectMapper.createArrayNode();
            j.get("columns").fields().forEachRemaining(entry -> colArray.add(entry.getValue()));
            finalJournal.set("columns", colArray);
            teacherArray.add(finalJournal);
        }
        result.set("teacher_journals", teacherArray);

        ArrayNode unassignedArray = objectMapper.createArrayNode();
        unassignedColumnsMap.values().forEach(unassignedArray::add);
        result.set("unassigned_activities", unassignedArray);

        return result;
    }

    private void addMarkToColumn(ObjectNode columnsContainer, int itemId, JsonNode item, int studentId, String studentName, String groupName, String studentEmail) {
        String key = String.valueOf(itemId);
        if (!columnsContainer.has(key)) {
            ObjectNode col = objectMapper.createObjectNode();
            col.put("item_id", itemId);
            if (!item.path(FIELD_ITEMINSTANCE).isNull()) {
                col.put("moodle_instance_id", item.path(FIELD_ITEMINSTANCE).asInt());
            }
            col.put("column_name", item.path(FIELD_ITEMNAME).asText());
            col.put("module_type", item.path(FIELD_ITEMMODULE).asText());
            col.put("max_grade", item.path(FIELD_GRADEMAX).asDouble());
            col.set("marks", objectMapper.createArrayNode());
            columnsContainer.set(key, col);
        }

        ObjectNode mark = createMarkNode(item, studentId, studentName, groupName, studentEmail);
        ((ArrayNode) columnsContainer.get(key).get("marks")).add(mark);
    }

    private void addMarkToColumn(Map<Integer, ObjectNode> map, int itemId, JsonNode item, int studentId, String studentName, String groupName, String studentEmail) {
        if (!map.containsKey(itemId)) {
            ObjectNode col = objectMapper.createObjectNode();
            col.put("item_id", itemId);
            if (!item.path(FIELD_ITEMINSTANCE).isNull()) {
                col.put("moodle_instance_id", item.path(FIELD_ITEMINSTANCE).asInt());
            }
            col.put("column_name", item.path(FIELD_ITEMNAME).asText());
            col.put("module_type", item.path(FIELD_ITEMMODULE).asText());
            col.put("max_grade", item.path(FIELD_GRADEMAX).asDouble());
            col.set("marks", objectMapper.createArrayNode());
            map.put(itemId, col);
        }

        ObjectNode mark = createMarkNode(item, studentId, studentName, groupName, studentEmail);
        ((ArrayNode) map.get(itemId).get("marks")).add(mark);
    }

    // Виніс створення оцінки в окремий метод, щоб прибрати дублювання
    private ObjectNode createMarkNode(JsonNode item, int studentId, String studentName, String groupName, String studentEmail) {
        ObjectNode mark = objectMapper.createObjectNode();
        mark.put("student_id", studentId);
        mark.put("student_name", studentName);
        mark.put("student_group", groupName);
        mark.put(FIELD_EMAIL, studentEmail);
        mark.put("score", item.path(FIELD_GRADERAW).asDouble());

        if (item.hasNonNull(FIELD_FEEDBACK) && !item.path(FIELD_FEEDBACK).asText().isBlank()) {
            mark.put("comment", htmlSanitizer.sanitize(item.path(FIELD_FEEDBACK).asText()));
        } else {
            mark.putNull("comment");
        }

        String formattedDate = formatUnixDate(item.path(FIELD_DATEGRADED).asLong(0));
        if (formattedDate != null) {
            mark.put("date_graded", formattedDate);
        } else {
            mark.putNull("date_graded");
        }
        return mark;
    }
}