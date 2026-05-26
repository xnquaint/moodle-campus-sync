package kpi.moodle_campus_sync;

public class MoodleSyncException extends RuntimeException {
    public MoodleSyncException(String message) {
        super(message);
    }

    public MoodleSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}