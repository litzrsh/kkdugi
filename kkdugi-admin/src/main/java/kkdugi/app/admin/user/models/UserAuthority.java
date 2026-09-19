package kkdugi.app.admin.user.models;

import java.time.LocalDate;

import kkdugi.core.enums.AuthorityType;
import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code kkdugi_user_auth} 한 행 — 사용자↔권한 매핑과 적용 기간. {@code role}/{@code type}/{@code name}/
 * {@code remarks}/{@code use}는 조회 때 {@code kkdugi_auth_base}를 조인해 채우는 읽기 전용 값이고 쓰기(upsert)에는
 * 쓰이지 않는다.
 */
@Getter
@Setter
public class UserAuthority extends BaseModel {

    private String userId;
    private String authorityId;
    private LocalDate applyStartDate;
    private LocalDate applyEndDate;
    private String role;
    private AuthorityType type;
    private String name;
    private String remarks;
    private String use;

    public UserAuthority() {
    }

    public UserAuthority(String userId, String authorityId, LocalDate applyStartDate, LocalDate applyEndDate) {
        this.userId = userId;
        this.authorityId = authorityId;
        this.applyStartDate = applyStartDate;
        this.applyEndDate = applyEndDate;
    }
}
