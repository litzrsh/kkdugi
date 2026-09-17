package kkdugi.core.security.models;

import org.springframework.security.core.GrantedAuthority;

import kkdugi.core.enums.AuthorityType;

public class Authority implements GrantedAuthority {

    private String id;
    private String authority;
    private AuthorityType type;
    private String name;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAuthority() {
        return authority;
    }

    public void setAuthority(String authority) {
        this.authority = authority;
    }

    public AuthorityType getType() {
        return type;
    }

    public void setType(AuthorityType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
