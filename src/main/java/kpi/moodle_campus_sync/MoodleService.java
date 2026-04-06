package kpi.moodle_campus_sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MoodleService {

    @Value("${moodle.api.token}")
    private String token;

    @Value("${moodle.api.url}")
    private String moodleUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String sendGetRequest(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    public JsonNode getUserDataByEmail(String email) throws Exception {
        String url = moodleUrl + "?wstoken=" + token +
                "&wsfunction=core_user_get_users&moodlewsrestformat=json" +
                "&criteria[0][key]=email&criteria[0][value]=" + URLEncoder.encode(email, StandardCharsets.UTF_8);

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        JsonNode users = root.path("users");
        return (users.isArray() && !users.isEmpty()) ? users.get(0) : null;
    }

    public JsonNode getUserById(int userId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "core_user_get_users")
                .queryParam("moodlewsrestformat", "json")
                .queryParam("criteria[0][key]", "id")
                .queryParam("criteria[0][value]", userId)
                .build().toUriString();

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        JsonNode users = root.path("users");
        return (users.isArray() && !users.isEmpty()) ? users.get(0) : null;
    }

    public JsonNode getAllCourses() throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "core_course_get_courses")
                .queryParam("moodlewsrestformat", "json")
                .build().toUriString();
        return objectMapper.readTree(sendGetRequest(url));
    }

    public ObjectNode getCourseTeachers(int courseId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "core_enrol_get_enrolled_users")
                .queryParam("courseid", courseId)
                .queryParam("moodlewsrestformat", "json")
                .build().toUriString();

        JsonNode allUsers = objectMapper.readTree(sendGetRequest(url));
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode teachersArray = objectMapper.createArrayNode();

        if (allUsers.isArray()) {
            for (JsonNode user : allUsers) {
                boolean isTeacher = false;
                for (JsonNode role : user.path("roles")) {
                    if (role.path("shortname").asText().toLowerCase().contains("teacher")) {
                        isTeacher = true;
                        break;
                    }
                }
                if (isTeacher) {
                    ObjectNode teacherInfo = objectMapper.createObjectNode();
                    teacherInfo.put("id", user.path("id").asInt());
                    teacherInfo.put("firstname", user.path("firstname").asText());
                    teacherInfo.put("lastname", user.path("lastname").asText());
                    teacherInfo.put("role_name", user.path("roles").get(0).path("shortname").asText());
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
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "gradereport_user_get_grade_items")
                .queryParam("courseid", courseId)
                .queryParam("moodlewsrestformat", "json")
                .build().toUriString();

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode itemsArray = objectMapper.createArrayNode();
        JsonNode userGrades = root.path("usergrades");

        if (userGrades.isArray() && !userGrades.isEmpty()) {
            JsonNode gradeItems = userGrades.get(0).path("gradeitems");
            if (gradeItems.isArray()) {
                for (JsonNode item : gradeItems) {
                    if ("mod".equals(item.path("itemtype").asText())) {
                        ObjectNode gNode = objectMapper.createObjectNode();
                        gNode.put("id", item.path("id").asInt());
                        gNode.put("name", item.path("itemname").asText());
                        gNode.put("module", item.path("itemmodule").asText());
                        gNode.put("instance", item.path("iteminstance").asInt());
                        gNode.put("max_grade", item.path("grademax").asDouble());
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
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "mod_assign_get_grades")
                .queryParam("moodlewsrestformat", "json")
                .queryParam("assignmentids[0]", assignmentId)
                .build().toUriString();

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        JsonNode assignments = root.path("assignments");

        if (assignments.isArray() && !assignments.isEmpty()) {
            JsonNode grades = assignments.get(0).path("grades");
            if (grades.isArray()) {
                for (JsonNode grade : grades) {
                    if (grade.path("userid").asInt() == studentId) {
                        return grade.path("grader").asInt();
                    }
                }
            }
        }
        return null;
    }

    public List<JsonNode> getCoursesForUser(int userId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "core_enrol_get_users_courses")
                .queryParam("userid", userId)
                .queryParam("moodlewsrestformat", "json")
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
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "gradereport_user_get_grade_items")
                .queryParam("courseid", courseId)
                .queryParam("moodlewsrestformat", "json")
                .build().toUriString();

        return objectMapper.readValue(sendGetRequest(url), MoodleResponse.class).usergrades();
    }

    public List<ObjectNode> getBulkGradesByEmails(List<String> emails) throws Exception {
        List<ObjectNode> bulkData = new ArrayList<>();

        for (String email : emails) {
            ObjectNode studentNode = objectMapper.createObjectNode();
            studentNode.put("email", email);

            JsonNode moodleUser = getUserDataByEmail(email);
            if (moodleUser != null) {
                int userId = moodleUser.get("id").asInt();
                studentNode.put("moodle_user_id", userId);
                studentNode.put("firstname", moodleUser.get("firstname").asText());
                studentNode.put("lastname", moodleUser.get("lastname").asText());

                ArrayNode coursesArray = objectMapper.createArrayNode();
                for (JsonNode course : getCoursesForUser(userId)) {
                    ObjectNode cNode = objectMapper.createObjectNode();
                    int cId = course.get("id").asInt();

                    cNode.put("course_id", cId);
                    cNode.put("course_fullname", course.get("fullname").asText());
                    cNode.put("course_shortname", course.get("shortname").asText());

                    List<UserGrade> grades = getParsedGrades(cId);
                    for (UserGrade rec : grades) {
                        if (rec.userid() == userId) {
                            ArrayNode gradesArray = objectMapper.createArrayNode();
                            for (GradeItem item : rec.gradeitems()) {
                                if (item.itemname() == null) continue;

                                ObjectNode gradeNode = objectMapper.valueToTree(item);
                                if ("mod".equals(item.itemtype()) && "assign".equals(item.itemmodule()) && item.iteminstance() != null) {
                                    Integer graderId = getGraderFromAssignment(item.iteminstance(), userId);
                                    if (graderId != null && graderId > 0) {
                                        JsonNode teacher = getUserById(graderId);
                                        if (teacher != null) {
                                            ObjectNode tNode = objectMapper.createObjectNode();
                                            tNode.put("id", graderId);
                                            tNode.put("firstname", teacher.path("firstname").asText());
                                            tNode.put("lastname", teacher.path("lastname").asText());
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

    public ObjectNode getCampusJournal(int courseId) throws Exception {
        String courseUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "core_course_get_courses_by_field")
                .queryParam("field", "id")
                .queryParam("value", courseId)
                .queryParam("moodlewsrestformat", "json")
                .build().toUriString();

        JsonNode courseRoot = objectMapper.readTree(sendGetRequest(courseUrl));
        JsonNode courses = courseRoot.path("courses");

        String courseName = "Unknown Course";
        String courseSummary = "";

        if (courses.isArray() && !courses.isEmpty()) {
            JsonNode cNode = courses.get(0);
            courseName = cNode.path("fullname").asText();
            courseSummary = cNode.path("summary").asText().replaceAll("<[^>]*>", "").trim();
        }

        String gradesUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "gradereport_user_get_grade_items")
                .queryParam("courseid", courseId)
                .queryParam("moodlewsrestformat", "json")
                .build().toUriString();

        JsonNode userGrades = objectMapper.readTree(sendGetRequest(gradesUrl)).path("usergrades");
        Map<Integer, ObjectNode> columnsMap = new LinkedHashMap<>();

        if (userGrades.isArray()) {
            for (JsonNode studentNode : userGrades) {
                int studentId = studentNode.path("userid").asInt();
                String studentName = studentNode.path("userfullname").asText();
                JsonNode gradeItems = studentNode.path("gradeitems");

                if (gradeItems.isArray()) {
                    for (JsonNode item : gradeItems) {
                        if (!"mod".equals(item.path("itemtype").asText())) continue;
                        int itemId = item.path("id").asInt();

                        if (!columnsMap.containsKey(itemId)) {
                            ObjectNode colNode = objectMapper.createObjectNode();
                            colNode.put("item_id", itemId);
                            colNode.put("moodle_instance_id", item.path("iteminstance").asInt());
                            colNode.put("column_name", item.path("itemname").asText());
                            colNode.put("module_type", item.path("itemmodule").asText());
                            colNode.put("max_grade", item.path("grademax").asDouble());
                            colNode.set("marks", objectMapper.createArrayNode());
                            columnsMap.put(itemId, colNode);
                        }

                        if (!item.path("graderaw").isNull()) {
                            ObjectNode markNode = objectMapper.createObjectNode();
                            markNode.put("moodle_student_id", studentId);
                            markNode.put("student_name", studentName);
                            markNode.put("score", item.path("graderaw").asDouble());

                            if ("assign".equals(item.path("itemmodule").asText())) {
                                Integer graderId = getGraderFromAssignment(item.path("iteminstance").asInt(), studentId);
                                if (graderId != null && graderId > 0) {
                                    JsonNode teacher = getUserById(graderId);
                                    if (teacher != null) {
                                        ObjectNode tNode = objectMapper.createObjectNode();
                                        tNode.put("id", graderId);
                                        tNode.put("firstname", teacher.path("firstname").asText());
                                        tNode.put("lastname", teacher.path("lastname").asText());
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
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "core_course_get_courses_by_field")
                .queryParam("field", "id")
                .queryParam("value", courseId)
                .queryParam("moodlewsrestformat", "json")
                .build().toUriString();

        JsonNode courseRoot = objectMapper.readTree(sendGetRequest(courseUrl));
        JsonNode courseData = courseRoot.path("courses").get(0);

        String gradesUrl = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "gradereport_user_get_grade_items")
                .queryParam("courseid", courseId)
                .queryParam("moodlewsrestformat", "json")
                .build().toUriString();

        JsonNode userGrades = objectMapper.readTree(sendGetRequest(gradesUrl)).path("usergrades");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("course_id", courseId);
        result.put("course_name", courseData.path("fullname").asText());
        result.put("course_shortname", courseData.path("shortname").asText());

        Map<Integer, ObjectNode> teacherJournalsMap = new HashMap<>();
        Map<Integer, ObjectNode> unassignedColumnsMap = new HashMap<>();

        if (userGrades.isArray()) {
            for (JsonNode studentNode : userGrades) {
                int studentId = studentNode.path("userid").asInt();
                String studentName = studentNode.path("userfullname").asText();

                for (JsonNode item : studentNode.path("gradeitems")) {
                    if (!"mod".equals(item.path("itemtype").asText()) || item.path("graderaw").isNull()) continue;

                    int itemId = item.path("id").asInt();
                    String moduleType = item.path("itemmodule").asText();

                    if ("assign".equals(moduleType)) {
                        Integer graderId = getGraderFromAssignment(item.path("iteminstance").asInt(), studentId);
                        if (graderId != null && graderId > 0) {
                            teacherJournalsMap.computeIfAbsent(graderId, id -> {
                                ObjectNode journal = objectMapper.createObjectNode();
                                try {
                                    JsonNode tInfo = getUserById(id);
                                    journal.put("teacher_id", id);
                                    journal.put("teacher_name", tInfo.path("firstname").asText() + " " + tInfo.path("lastname").asText());
                                    journal.set("columns", objectMapper.createObjectNode());
                                } catch (Exception e) {}
                                return journal;
                            });
                            ObjectNode columnsContainer = (ObjectNode) teacherJournalsMap.get(graderId).get("columns");
                            addMarkToColumn(columnsContainer, itemId, item, studentId, studentName);
                        }
                    } else {
                        addMarkToColumn(unassignedColumnsMap, itemId, item, studentId, studentName);
                    }
                }
            }
        }

        ArrayNode teacherArray = objectMapper.createArrayNode();
        for (ObjectNode j : teacherJournalsMap.values()) {
            ObjectNode finalJournal = objectMapper.createObjectNode();
            finalJournal.put("teacher_id", j.get("teacher_id").asInt());
            finalJournal.put("teacher_name", j.get("teacher_name").asText());
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

    private void addMarkToColumn(ObjectNode columnsContainer, int itemId, JsonNode item, int studentId, String studentName) {
        String key = String.valueOf(itemId);
        if (!columnsContainer.has(key)) {
            ObjectNode col = objectMapper.createObjectNode();
            col.put("column_name", item.path("itemname").asText());
            col.put("module_type", item.path("itemmodule").asText());
            col.put("max_grade", item.path("grademax").asDouble());
            col.set("marks", objectMapper.createArrayNode());
            columnsContainer.set(key, col);
        }
        ObjectNode mark = objectMapper.createObjectNode();
        mark.put("student_id", studentId);
        mark.put("student_name", studentName);
        mark.put("score", item.path("graderaw").asDouble());
        ((ArrayNode) columnsContainer.get(key).get("marks")).add(mark);
    }

    private void addMarkToColumn(Map<Integer, ObjectNode> map, int itemId, JsonNode item, int studentId, String studentName) {
        if (!map.containsKey(itemId)) {
            ObjectNode col = objectMapper.createObjectNode();
            col.put("column_name", item.path("itemname").asText());
            col.put("module_type", item.path("itemmodule").asText());
            col.put("max_grade", item.path("grademax").asDouble());
            col.set("marks", objectMapper.createArrayNode());
            map.put(itemId, col);
        }
        ObjectNode mark = objectMapper.createObjectNode();
        mark.put("student_id", studentId);
        mark.put("student_name", studentName);
        mark.put("score", item.path("graderaw").asDouble());
        ((ArrayNode) map.get(itemId).get("marks")).add(mark);
    }
}