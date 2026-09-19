package kkdugi.app.admin.authority.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_auth_menu} 한 행 — 권한이 메뉴에 갖는 RBAC 비트마스크({@code Rbac} 값의 OR). */
@Getter
@Setter
public class AuthorityMenu extends BaseModel {

    private String authorityId;
    private String menuId;
    private Integer rbac;

    public AuthorityMenu() {
    }

    public AuthorityMenu(String authorityId, String menuId, Integer rbac) {
        this.authorityId = authorityId;
        this.menuId = menuId;
        this.rbac = rbac;
    }
}
