package kpi.moodle_campus_sync;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;

@SpringBootApplication
@RestController
public class MoodleCampusSyncApplication {

    @Autowired
    private MoodleService moodleService;

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
                                       .collect(Collectors.toList());
                                       
            return moodleService.getBulkGradesByEmails(emails);
        }
    }
}