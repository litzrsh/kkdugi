package kkdugi.app.admin.authority.models;

import java.time.LocalDate;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code kkdugi_user_auth} 한 행 — 사용자↔권한 매핑과 적용 기간. {@code userName}/{@code userImage}는
 * 조회 때 {@code kkdugi_user_base}를 조인해 채우는 읽기 전용 값이고 쓰기(upsert)에는 쓰이지 않는다.
 */
@Getter
@Setter
public class AuthorityUser extends BaseModel {

    private String userId;
    private String authorityId;
    private LocalDate applyStartDate;
    private LocalDate applyEndDate;
    private String userName;
    private String userImage;

    public AuthorityUser() {
    }

    public AuthorityUser(String userId, String authorityId, LocalDate applyStartDate, LocalDate applyEndDate) {
        this.userId = userId;
        this.authorityId = authorityId;
        this.applyStartDate = applyStartDate;
        this.applyEndDate = applyEndDate;
    }
}
