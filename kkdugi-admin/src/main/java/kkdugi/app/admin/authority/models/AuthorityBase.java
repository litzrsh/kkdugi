package kkdugi.app.admin.authority.models;

import kkdugi.core.enums.AuthorityType;
import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_auth_base} 한 행. */
@Getter
@Setter
public class AuthorityBase extends BaseModel {

    private String id;
    private String role;
    private AuthorityType type;
    private String name;
    private String remarks;
    private String use;

    public AuthorityBase() {
    }

    public AuthorityBase(String id, String role, AuthorityType type, String name, String remarks, String use) {
        this.id = id;
        this.role = role;
        this.type = type;
        this.name = name;
        this.remarks = remarks;
        this.use = use;
    }
}
