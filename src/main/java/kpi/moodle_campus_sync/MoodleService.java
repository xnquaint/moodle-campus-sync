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

    // Константи для Moodle API
    private static final String PARAM_WSTOKEN = "wstoken";
    private static final String PARAM_WSFUNC = "wsfunction";
    private static final String PARAM_FORMAT = "moodlewsrestformat";
    private static final String VAL_JSON = "json";
    private static final String WS_FUNC_ENROLLED_USERS = "core_enrol_get_enrolled_users";
    private static final String WS_FUNC_GRADE_ITEMS = "gradereport_user_get_grade_items";

    // Поля бази даних Moodle
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
    private static final String FIELD_USERGRADES = "usergrades";
    private static final String FIELD_GRADEITEMS = "gradeitems";
    private static final String FIELD_ITEMTYPE = "itemtype";
    private static final String FIELD_ITEMMODULE = "itemmodule";
    private static final String FIELD_ITEMINSTANCE = "iteminstance";
    private static final String FIELD_ITEMNAME = "itemname";
    private static final String FIELD_GRADEMAX = "grademax";
    private static final String FIELD_GRADERAW = "graderaw";
    private static final String FIELD_FEEDBACK = "feedback";
    private static final String FIELD_DATEGRADED = "gradedategraded";

    // Технічні константи
    private static final String VAL_MOD = "mod";
    private static final String VAL_ASSIGN = "assign";
    private static final String NO_GROUP = "Без групи";
    private static final String UNKNOWN_COURSE = "Unknown Course";
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    // Константи вихідного JSON
    private static final String OUT_COURSE_ID = "course_id";
    private static final String OUT_MAX_GRADE = "max_grade";
    private static final String OUT_ITEM_ID = "item_id";
    private static final String OUT_MOODLE_INSTANCE_ID = "moodle_instance_id";
    private static final String OUT_COLUMN_NAME = "column_name";
    private static final String OUT_MODULE_TYPE = "module_type";
    private static final String OUT_MARKS = "marks";
    private static final String OUT_COMMENT = "comment";
    private static final String OUT_DATE_GRADED = "date_graded";
    private static final String OUT_COLUMNS = "columns";
    private static final String OUT_TEACHER_ID = "teacher_id";
    private static final String OUT_TEACHER_NAME = "teacher_name";

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

    private String sendGetRequest(String url) {
        try {
            if (log.isInfoEnabled()) {
                log.info("Відправка HTTP GET запиту до Moodle API: {}", url.split(PARAM_WSTOKEN + "=")[0]);
            }
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Moodle повернув нестандартний статус код: {}", response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MoodleSyncException("Запит був перерваний", e);
        } catch (Exception e) {
            throw new MoodleSyncException("Помилка виконання HTTP запиту", e);
        }
    }

    private JsonNode readJson(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            throw new MoodleSyncException("Помилка парсингу JSON", e);
        }
    }

    private String formatUnixDate(long timestamp) {
        if (timestamp <= 0) return null;
        return DateTimeFormatter.ofPattern(DATE_FORMAT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(timestamp));
    }

    public JsonNode getUserDataByEmail(String email) {
        String url = moodleUrl + "?" + PARAM_WSTOKEN + "=" + token +
                "&" + PARAM_WSFUNC + "=core_user_get_users&" + PARAM_FORMAT + "=" + VAL_JSON +
                "&criteria[0][key]=" + FIELD_EMAIL + "&criteria[0][value]=" + URLEncoder.encode(email, StandardCharsets.UTF_8);

        JsonNode root = readJson(sendGetRequest(url));
        JsonNode users = root.path("users");
        return (users.isArray() && !users.isEmpty()) ? users.get(0) : null;
    }

    public JsonNode getUserById(int userId) {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_user_get_users")
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .queryParam("criteria[0][key]", FIELD_ID)
                .queryParam("criteria[0][value]", userId)
                .build().toUriString();

        JsonNode root = readJson(sendGetRequest(url));
        JsonNode users = root.path("users");
        return (users.isArray() && !users.isEmpty()) ? users.get(0) : null;
    }

    public JsonNode getAllCourses() {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_course_get_courses")
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode root = readJson(sendGetRequest(url));
        if (root.isArray()) {
            root.forEach(this::processCourseNode);
        }
        return root;
    }

    private void processCourseNode(JsonNode courseNode) {
        ObjectNode objNode = (ObjectNode) courseNode;
        if (objNode.has(FIELD_SUMMARY)) {
            objNode.put(FIELD_SUMMARY, htmlSanitizer.sanitize(objNode.get(FIELD_SUMMARY).asText()));
        }
        String[] dateFields = {"startdate", "enddate", "timecreated", "timemodified"};
        for (String field : dateFields) {
            if (objNode.has(field)) {
                String formatted = formatUnixDate(objNode.get(field).asLong(0));
                if (formatted != null) {
                    objNode.put(field, formatted);
                } else {
                    objNode.putNull(field);
                }
            }
        }
    }

    public ObjectNode getCourseTeachers(int courseId) {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, WS_FUNC_ENROLLED_USERS)
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode allUsers = readJson(sendGetRequest(url));
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
        result.put(OUT_COURSE_ID, courseId);
        result.set("teachers", teachersArray);
        return result;
    }

    public ObjectNode getCourseGradeItems(int courseId) {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, WS_FUNC_GRADE_ITEMS)
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode root = readJson(sendGetRequest(url));
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode itemsArray = objectMapper.createArrayNode();
        JsonNode userGrades = root.path(FIELD_USERGRADES);

        if (userGrades.isArray() && !userGrades.isEmpty()) {
            JsonNode gradeItems = userGrades.get(0).path(FIELD_GRADEITEMS);
            if (gradeItems.isArray()) {
                for (JsonNode item : gradeItems) {
                    if (VAL_MOD.equals(item.path(FIELD_ITEMTYPE).asText())) {
                        ObjectNode gNode = objectMapper.createObjectNode();
                        gNode.put(FIELD_ID, item.path(FIELD_ID).asInt());
                        gNode.put("name", item.path(FIELD_ITEMNAME).asText());
                        gNode.put("module", item.path(FIELD_ITEMMODULE).asText());
                        gNode.put("instance", item.path(FIELD_ITEMINSTANCE).asInt());
                        gNode.put(OUT_MAX_GRADE, item.path(FIELD_GRADEMAX).asDouble());
                        itemsArray.add(gNode);
                    }
                }
            }
        }
        result.put(OUT_COURSE_ID, courseId);
        result.set("control_activities", itemsArray);
        return result;
    }

    public Integer getGraderFromAssignment(int assignmentId, int studentId) {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "mod_assign_get_grades")
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .queryParam("assignmentids[0]", assignmentId)
                .build().toUriString();

        JsonNode root = readJson(sendGetRequest(url));
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

    public List<JsonNode> getCoursesForUser(int userId) {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_enrol_get_users_courses")
                .queryParam(FIELD_USERID, userId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode root = readJson(sendGetRequest(url));
        List<JsonNode> courses = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(courses::add);
        }
        return courses;
    }

    public List<UserGrade> getParsedGrades(int courseId) {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, WS_FUNC_GRADE_ITEMS)
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        try {
            return objectMapper.readValue(sendGetRequest(url), MoodleResponse.class).usergrades();
        } catch (Exception e) {
            throw new MoodleSyncException("Помилка серіалізації оцінок", e);
        }
    }

    public List<ObjectNode> getBulkGradesByEmails(List<String> emails) {
        List<ObjectNode> bulkData = new ArrayList<>();
        for (String email : emails) {
            bulkData.add(processSingleEmailForBulk(email));
        }
        return bulkData;
    }

    private ObjectNode processSingleEmailForBulk(String email) {
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
                coursesArray.add(processCourseForBulk(course, userId));
            }
            studentNode.set("enrolled_courses", coursesArray);
        } else {
            studentNode.put("status", "error").put("message", "User not found");
        }
        return studentNode;
    }

    private ObjectNode processCourseForBulk(JsonNode course, int userId) {
        ObjectNode cNode = objectMapper.createObjectNode();
        int cId = course.get(FIELD_ID).asInt();

        cNode.put(OUT_COURSE_ID, cId);
        cNode.put("course_fullname", course.get(FIELD_FULLNAME).asText());
        cNode.put("course_shortname", course.get(FIELD_SHORTNAME).asText());

        List<UserGrade> grades = getParsedGrades(cId);
        for (UserGrade rec : grades) {
            if (rec.userid() == userId) {
                cNode.set(OUT_MARKS, extractGradesForCourse(rec, userId));
            }
        }
        return cNode;
    }

    private ArrayNode extractGradesForCourse(UserGrade rec, int userId) {
        ArrayNode gradesArray = objectMapper.createArrayNode();
        for (GradeItem item : rec.gradeitems()) {
            if (item.itemname() == null) continue;

            ObjectNode gradeNode = objectMapper.valueToTree(item);
            appendTeacherInfoIfAssignable(gradeNode, item, userId);
            gradesArray.add(gradeNode);
        }
        return gradesArray;
    }

    private void appendTeacherInfoIfAssignable(ObjectNode gradeNode, GradeItem item, int userId) {
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
    }

    public Map<Integer, JsonNode> getEnrolledUsersMap(int courseId) {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, WS_FUNC_ENROLLED_USERS)
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode allUsers = readJson(sendGetRequest(url));
        Map<Integer, JsonNode> userMap = new HashMap<>();

        if (allUsers.isArray()) {
            for (JsonNode user : allUsers) {
                userMap.put(user.path(FIELD_ID).asInt(), user);
            }
        }
        return userMap;
    }

    public ObjectNode getCampusJournal(int courseId) {
        String courseUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_course_get_courses_by_field")
                .queryParam("field", "id")
                .queryParam("value", courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode courseRoot = readJson(sendGetRequest(courseUrl));
        JsonNode courses = courseRoot.path("courses");

        String courseName = UNKNOWN_COURSE;
        String courseSummary = "";

        if (courses.isArray() && !courses.isEmpty()) {
            JsonNode cNode = courses.get(0);
            courseName = cNode.path(FIELD_FULLNAME).asText();
            courseSummary = htmlSanitizer.sanitize(cNode.path(FIELD_SUMMARY).asText());
        }

        String gradesUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, WS_FUNC_GRADE_ITEMS)
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode userGrades = readJson(sendGetRequest(gradesUrl)).path(FIELD_USERGRADES);
        Map<Integer, ObjectNode> columnsMap = new LinkedHashMap<>();
        Map<Integer, JsonNode> enrolledUsers = getEnrolledUsersMap(courseId);

        if (userGrades.isArray()) {
            for (JsonNode studentNode : userGrades) {
                processStudentForCampusJournal(studentNode, columnsMap, enrolledUsers);
            }
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("moodle_course_id", courseId);
        result.put("course_name", courseName);
        result.put("course_description", courseSummary);

        ArrayNode columnsArray = objectMapper.createArrayNode();
        columnsMap.values().forEach(columnsArray::add);
        result.set(OUT_COLUMNS, columnsArray);

        return result;
    }

    private void processStudentForCampusJournal(JsonNode studentNode, Map<Integer, ObjectNode> columnsMap, Map<Integer, JsonNode> enrolledUsers) {
        int studentId = studentNode.path(FIELD_USERID).asInt();
        String studentName = studentNode.path("userfullname").asText();
        JsonNode gradeItems = studentNode.path(FIELD_GRADEITEMS);

        if (!gradeItems.isArray()) return;

        for (JsonNode item : gradeItems) {
            if (!VAL_MOD.equals(item.path(FIELD_ITEMTYPE).asText()) || item.path(FIELD_GRADERAW).isNull()) continue;

            int itemId = item.path(FIELD_ID).asInt();
            ObjectNode colNode = columnsMap.computeIfAbsent(itemId, id -> createColumnNode(item));
            ObjectNode markNode = createCampusMarkNode(item, studentId, studentName, enrolledUsers);
            ((ArrayNode) colNode.get(OUT_MARKS)).add(markNode);
        }
    }

    private ObjectNode createColumnNode(JsonNode item) {
        ObjectNode colNode = objectMapper.createObjectNode();
        colNode.put(OUT_ITEM_ID, item.path(FIELD_ID).asInt());
        if (!item.path(FIELD_ITEMINSTANCE).isNull()) {
            colNode.put(OUT_MOODLE_INSTANCE_ID, item.path(FIELD_ITEMINSTANCE).asInt());
        }
        colNode.put(OUT_COLUMN_NAME, item.path(FIELD_ITEMNAME).asText());
        colNode.put(OUT_MODULE_TYPE, item.path(FIELD_ITEMMODULE).asText());
        colNode.put(OUT_MAX_GRADE, item.path(FIELD_GRADEMAX).asDouble());
        colNode.set(OUT_MARKS, objectMapper.createArrayNode());
        return colNode;
    }

    private ObjectNode createCampusMarkNode(JsonNode item, int studentId, String studentName, Map<Integer, JsonNode> enrolledUsers) {
        ObjectNode markNode = objectMapper.createObjectNode();
        markNode.put("moodle_student_id", studentId);
        markNode.put("student_name", studentName);

        // Логіка користувача
        JsonNode userNode = enrolledUsers.get(studentId);
        markNode.put("student_group", getGroupName(userNode));
        markNode.put(FIELD_EMAIL, userNode != null ? userNode.path(FIELD_EMAIL).asText(null) : null);
        
        // Логіка оцінки
        markNode.put("score", item.path(FIELD_GRADERAW).asDouble());
        markNode.put(OUT_COMMENT, item.hasNonNull(FIELD_FEEDBACK) ? htmlSanitizer.sanitize(item.path(FIELD_FEEDBACK).asText()) : null);
        markNode.put(OUT_DATE_GRADED, formatUnixDate(item.path(FIELD_DATEGRADED).asLong(0)));

        // Логіка вчителя
        if (VAL_ASSIGN.equals(item.path(FIELD_ITEMMODULE).asText())) {
            Integer graderId = getGraderFromAssignment(item.path(FIELD_ITEMINSTANCE).asInt(), studentId);
            appendTeacherData(markNode, graderId, enrolledUsers);
        }
        return markNode;
    }

    private String getGroupName(JsonNode userNode) {
        if (userNode != null) {
            JsonNode groups = userNode.path(FIELD_GROUPS);
            if (groups.isArray() && !groups.isEmpty()) {
                return groups.get(0).path("name").asText(NO_GROUP);
            }
        }
        return NO_GROUP;
    }

    private void appendTeacherData(ObjectNode markNode, Integer graderId, Map<Integer, JsonNode> enrolledUsers) {
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

    public ObjectNode getTeacherCentricJournals(int courseId) {
        String courseUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, "core_course_get_courses_by_field")
                .queryParam("field", "id")
                .queryParam("value", courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode courseRoot = readJson(sendGetRequest(courseUrl));
        JsonNode courses = courseRoot.path("courses");

        String courseName = UNKNOWN_COURSE;
        String courseShortname = "";
        String courseDescription = "";

        if (courses.isArray() && !courses.isEmpty()) {
            JsonNode courseData = courses.get(0);
            courseName = courseData.path(FIELD_FULLNAME).asText(UNKNOWN_COURSE);
            courseShortname = courseData.path(FIELD_SHORTNAME).asText("");
            courseDescription = htmlSanitizer.sanitize(courseData.path(FIELD_SUMMARY).asText(""));
        }

        String gradesUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam(PARAM_WSTOKEN, token)
                .queryParam(PARAM_WSFUNC, WS_FUNC_GRADE_ITEMS)
                .queryParam(FIELD_COURSEID, courseId)
                .queryParam(PARAM_FORMAT, VAL_JSON)
                .build().toUriString();

        JsonNode userGrades = readJson(sendGetRequest(gradesUrl)).path(FIELD_USERGRADES);
        Map<Integer, JsonNode> enrolledUsers = getEnrolledUsersMap(courseId);
        
        ObjectNode result = objectMapper.createObjectNode();
        result.put(OUT_COURSE_ID, courseId);
        result.put("course_name", courseName);
        result.put("course_shortname", courseShortname);
        result.put("course_description", courseDescription);

        Map<Integer, ObjectNode> teacherJournalsMap = new HashMap<>();
        Map<Integer, ObjectNode> unassignedColumnsMap = new HashMap<>();

        if (userGrades.isArray()) {
            for (JsonNode studentNode : userGrades) {
                processStudentForTeacherCentric(studentNode, enrolledUsers, teacherJournalsMap, unassignedColumnsMap);
            }
        }

        ArrayNode teacherArray = objectMapper.createArrayNode();
        for (ObjectNode j : teacherJournalsMap.values()) {
            ObjectNode finalJournal = objectMapper.createObjectNode();
            finalJournal.put(OUT_TEACHER_ID, j.get(OUT_TEACHER_ID).asInt());
            finalJournal.put(OUT_TEACHER_NAME, j.get(OUT_TEACHER_NAME).asText());
            finalJournal.set(FIELD_EMAIL, j.get(FIELD_EMAIL));
            ArrayNode colArray = objectMapper.createArrayNode();
            j.get(OUT_COLUMNS).fields().forEachRemaining(entry -> colArray.add(entry.getValue()));
            finalJournal.set(OUT_COLUMNS, colArray);
            teacherArray.add(finalJournal);
        }
        result.set("teacher_journals", teacherArray);

        ArrayNode unassignedArray = objectMapper.createArrayNode();
        unassignedColumnsMap.values().forEach(unassignedArray::add);
        result.set("unassigned_activities", unassignedArray);

        return result;
    }

    private void processStudentForTeacherCentric(JsonNode studentNode, Map<Integer, JsonNode> enrolledUsers,
                                             Map<Integer, ObjectNode> teacherJournalsMap,
                                             Map<Integer, ObjectNode> unassignedColumnsMap) {
        int studentId = studentNode.path(FIELD_USERID).asInt();
        String studentName = studentNode.path("userfullname").asText();
        
        JsonNode userNode = enrolledUsers.get(studentId);
        String groupName = getGroupName(userNode);
        String studentEmail = (userNode != null) ? userNode.path(FIELD_EMAIL).asText(null) : null;

        for (JsonNode item : studentNode.path(FIELD_GRADEITEMS)) {
            if (VAL_MOD.equals(item.path(FIELD_ITEMTYPE).asText()) && !item.path(FIELD_GRADERAW).isNull()) {
                
                Integer graderId = VAL_ASSIGN.equals(item.path(FIELD_ITEMMODULE).asText()) ? 
                                  getGraderFromAssignment(item.path(FIELD_ITEMINSTANCE).asInt(), studentId) : null;

                if (graderId != null && graderId > 0) {
                    ObjectNode journal = teacherJournalsMap.computeIfAbsent(graderId, id -> createTeacherJournalNode(id, enrolledUsers));
                    addMarkToTeacherColumnNode((ObjectNode) journal.get(OUT_COLUMNS), item.path(FIELD_ID).asInt(), item, studentId, studentName, groupName, studentEmail);
                } else {
                    addMarkToTeacherColumnMap(unassignedColumnsMap, item.path(FIELD_ID).asInt(), item, studentId, studentName, groupName, studentEmail);
                }
            }
        }
    }

    private ObjectNode createTeacherJournalNode(int teacherId, Map<Integer, JsonNode> enrolledUsers) {
        ObjectNode journal = objectMapper.createObjectNode();
        JsonNode tInfo = enrolledUsers.get(teacherId);
        journal.put(OUT_TEACHER_ID, teacherId);
        if (tInfo != null) {
            journal.put(OUT_TEACHER_NAME, tInfo.path(FIELD_FIRSTNAME).asText() + " " + tInfo.path(FIELD_LASTNAME).asText());
            journal.put(FIELD_EMAIL, tInfo.path(FIELD_EMAIL).asText(null));
        } else {
            journal.put(OUT_TEACHER_NAME, "Unknown Teacher");
            journal.putNull(FIELD_EMAIL);
        }
        journal.set(OUT_COLUMNS, objectMapper.createObjectNode());
        return journal;
    }

    private void addMarkToTeacherColumnNode(ObjectNode columnsContainer, int itemId, JsonNode item, int studentId, String studentName, String groupName, String studentEmail) {
        String key = String.valueOf(itemId);
        if (!columnsContainer.has(key)) {
            columnsContainer.set(key, createColumnNode(item));
        }
        ObjectNode mark = createTeacherCentricMarkNode(item, studentId, studentName, groupName, studentEmail);
        ((ArrayNode) columnsContainer.get(key).get(OUT_MARKS)).add(mark);
    }

    private void addMarkToTeacherColumnMap(Map<Integer, ObjectNode> map, int itemId, JsonNode item, int studentId, String studentName, String groupName, String studentEmail) {
        ObjectNode col = map.computeIfAbsent(itemId, id -> createColumnNode(item));
        ObjectNode mark = createTeacherCentricMarkNode(item, studentId, studentName, groupName, studentEmail);
        ((ArrayNode) col.get(OUT_MARKS)).add(mark);
    }

    private ObjectNode createTeacherCentricMarkNode(JsonNode item, int studentId, String studentName, String groupName, String studentEmail) {
        ObjectNode mark = objectMapper.createObjectNode();
        mark.put("student_id", studentId);
        mark.put("student_name", studentName);
        mark.put("student_group", groupName);
        mark.put(FIELD_EMAIL, studentEmail);
        mark.put("score", item.path(FIELD_GRADERAW).asDouble());

        if (item.hasNonNull(FIELD_FEEDBACK) && !item.path(FIELD_FEEDBACK).asText().isBlank()) {
            mark.put(OUT_COMMENT, htmlSanitizer.sanitize(item.path(FIELD_FEEDBACK).asText()));
        } else {
            mark.putNull(OUT_COMMENT);
        }

        String formattedDate = formatUnixDate(item.path(FIELD_DATEGRADED).asLong(0));
        if (formattedDate != null) {
            mark.put(OUT_DATE_GRADED, formattedDate);
        } else {
            mark.putNull(OUT_DATE_GRADED);
        }
        return mark;
    }
}