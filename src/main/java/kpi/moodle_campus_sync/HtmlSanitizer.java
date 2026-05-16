package kpi.moodle_campus_sync;

import org.springframework.stereotype.Component;

@Component
public class HtmlSanitizer {
    
    public String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.replaceAll("<[^>]*>", "").trim();
    }
}