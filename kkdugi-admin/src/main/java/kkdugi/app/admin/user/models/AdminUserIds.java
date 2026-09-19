package kkdugi.app.admin.user.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * {@code { "id": ["U...", ...] }} 요청 본문(비밀번호 초기화). 인자가 하나뿐인 생성자는 Jackson이 위임
 * 생성자로 오인할 수 있어 {@code PROPERTIES} 모드를 명시한다.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserIds {

    private final List<String> id;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public AdminUserIds(@JsonProperty("id") List<String> id) {
        this.id = id;
    }
}
