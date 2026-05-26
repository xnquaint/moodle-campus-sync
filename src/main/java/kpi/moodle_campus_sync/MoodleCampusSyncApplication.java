package kpi.moodle_campus_sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

@SpringBootApplication
@RestController
public class MoodleCampusSyncApplication {

    private final MoodleService moodleService;

    public MoodleCampusSyncApplication(MoodleService moodleService) {
        this.moodleService = moodleService;
    }

    public static void main(String[] args) {
        SpringApplication.run(MoodleCampusSyncApplication.class, args);
    }

    @GetMapping(value = "/export-students", produces = "application/json")
    public List<ObjectNode> exportStudents() throws Exception {
        ClassPathResource resource = new ClassPathResource("students.txt");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
            List<String> emails = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
            return moodleService.getBulkGradesByEmails(emails);
        }
    }

    @GetMapping("/courses")
    public JsonNode listAllCourses() throws Exception {
        return moodleService.getAllCourses();
    }

    @GetMapping("/course/{id}/teachers")
    public ObjectNode getCourseTeachers(@PathVariable int id) throws Exception {
        return moodleService.getCourseTeachers(id);
    }

    @GetMapping("/course/{id}/activities")
    public ObjectNode getCourseActivities(@PathVariable int id) throws Exception {
        return moodleService.getCourseGradeItems(id);
    }

    @GetMapping(value = "/course/{id}/journal", produces = "application/json")
    public ObjectNode getJournal(@PathVariable int id) throws Exception {
        return moodleService.getCampusJournal(id);
    }

    @GetMapping("/course/{id}/export-campus")
    public ObjectNode exportToCampus(@PathVariable int id) throws Exception {
        return moodleService.getTeacherCentricJournals(id);
    }
}