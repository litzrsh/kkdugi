package kkdugi.core.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum PasswordStatus implements CodeEnums {

    NEWP("10", "user.password_status.10"), // 비밀번호 초기화 - 로그인 시 비밀번호 변경 필요
    EXPR("20", "user.password_status.20"), // 만료된 비밀번호 - 로그인 시 비밀번호를 변경하거나 변경 기한 연장
    NORM("30", "user.password_status.30"); // 정상

    private final String code;
    private final String labelCode;

    public static PasswordStatus fromCode(String code) {
        return CodeEnums.fromCode(PasswordStatus.class, code);
    }
}
