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
import java.util.ArrayList;
import java.util.List;

@Service
public class MoodleService {

    @Value("${moodle.api.token}")
    private String token;

    @Value("${moodle.api.url}")
    private String moodleUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String sendGetRequest(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    public JsonNode getUserDataByEmail(String email) throws Exception {
        String criteriaKey = URLEncoder.encode("criteria[0][key]", StandardCharsets.UTF_8);
        String criteriaValue = URLEncoder.encode("criteria[0][value]", StandardCharsets.UTF_8);
        String encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);

        String url = moodleUrl + "?wstoken=" + token +
                "&wsfunction=core_user_get_users&moodlewsrestformat=json" +
                "&" + criteriaKey + "=email&" + criteriaValue + "=" + encodedEmail;

        JsonNode root = objectMapper.readTree(sendGetRequest(url));
        JsonNode users = root.path("users");

        return (users.isArray() && !users.isEmpty()) ? users.get(0) : null;
    }

    public List<JsonNode> getCoursesForUser(int userId) throws Exception {
        String url = UriComponentsBuilder.fromUriString(moodleUrl)
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "core_enrol_get_users_courses")
                .queryParam("userid", userId)
                .queryParam("moodlewsrestformat", "json")
                .toUriString();

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
                .toUriString();

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
                            cNode.set("grades", objectMapper.valueToTree(rec.gradeitems()));
                        }
                    }
                    coursesArray.add(cNode);
                }
                studentNode.set("enrolled_courses", coursesArray);
            } else {
                studentNode.put("status", "error")
                           .put("message", "User not found");
            }
            bulkData.add(studentNode);
        }
        return bulkData;
    }
}