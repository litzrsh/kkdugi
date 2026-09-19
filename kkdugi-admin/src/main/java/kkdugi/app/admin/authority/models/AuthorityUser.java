package kkdugi.app.admin.authority.models;

import java.time.LocalDate;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_user_auth} 한 행 — 사용자↔권한 매핑과 적용 기간. */
@Getter
@Setter
public class AuthorityUser extends BaseModel {

    private String userId;
    private String authorityId;
    private LocalDate applyStartDate;
    private LocalDate applyEndDate;

    public AuthorityUser() {
    }

    public AuthorityUser(String userId, String authorityId, LocalDate applyStartDate, LocalDate applyEndDate) {
        this.userId = userId;
        this.authorityId = authorityId;
        this.applyStartDate = applyStartDate;
        this.applyEndDate = applyEndDate;
    }
}
