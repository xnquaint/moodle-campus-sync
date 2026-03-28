package kpi.moodle_campus_sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record MoodleResponse(@JsonProperty("usergrades") List<UserGrade> usergrades) {}

@JsonIgnoreProperties(ignoreUnknown = true)
record UserGrade(
    @JsonProperty("userid") int userid, 
    @JsonProperty("userfullname") String userfullname, 
    @JsonProperty("gradeitems") List<GradeItem> gradeitems
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
record GradeItem(
    @JsonProperty("itemname") String itemname, 
    @JsonProperty("graderaw") Double graderaw, 
    @JsonProperty("grademax") Double grademax,
    @JsonProperty("itemtype") String itemtype
) {}