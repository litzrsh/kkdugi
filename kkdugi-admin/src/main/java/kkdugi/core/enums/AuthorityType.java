package kkdugi.core.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum AuthorityType implements CodeEnums {

    ROLE("ROLE", "authority.type.role"),
    PLAN("PLAN", "authority.type.plan");

    private final String code;
    private final String labelCode;

    public static AuthorityType fromCode(String code) {
        return CodeEnums.fromCode(AuthorityType.class, code);
    }
}
