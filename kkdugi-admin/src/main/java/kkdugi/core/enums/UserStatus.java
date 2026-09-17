package kkdugi.core.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum UserStatus implements CodeEnums {

    PEND("10", "user.status.10"),
    NORM("20", "user.status.20"),
    DORM("30", "user.status.30"),
    RESN("40", "user.status.40"),
    SUPD("50", "user.status.50");

    private final String code;
    private final String labelCode;

    public static UserStatus fromCode(String code) {
        return CodeEnums.fromCode(UserStatus.class, code);
    }
}
