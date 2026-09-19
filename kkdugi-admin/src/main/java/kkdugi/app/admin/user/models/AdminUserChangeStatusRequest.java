package kkdugi.app.admin.user.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/** {@code { "id": [...], "status": "20" }} 요청 본문(상태 일괄 변경). */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserChangeStatusRequest {

    private final List<String> id;
    private final String status;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public AdminUserChangeStatusRequest(@JsonProperty("id") List<String> id, @JsonProperty("status") String status) {
        this.id = id;
        this.status = status;
    }
}
