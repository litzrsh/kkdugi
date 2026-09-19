package kkdugi.app.admin.user.models;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 사용자에게 붙는 권한 한 건과 적용 기간 — 요청/응답 공용. 날짜는 {@code yyyy-MM-dd}.
 * 요청에서는 {@code id}와 {@code applyStartDate}/{@code applyEndDate}만 읽는다. 나머지
 * ({@code role}/{@code type}/{@code name}/{@code remarks}/{@code use})는 응답 전용이라 실려 와도 무시한다
 * — 응답 항목을 그대로 요청으로 돌려보내도 동작한다.
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserAuthority {

    private final String id;
    private final String role;
    private final String type;
    private final String name;
    private final String remarks;
    private final String use;
    private final LocalDate applyStartDate;
    private final LocalDate applyEndDate;
}
