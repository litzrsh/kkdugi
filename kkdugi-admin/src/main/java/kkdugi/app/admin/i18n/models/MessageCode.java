package kkdugi.app.admin.i18n.models;

import java.util.regex.Pattern;

public final class MessageCode {

    private static final Pattern PATTERN =
            Pattern.compile("^[a-z0-9]+(_[a-z0-9]+)*(\\.[a-z0-9]+(_[a-z0-9]+)*){2}\\z");

    private MessageCode() {
    }

    public static boolean matches(String code) {
        return code != null && PATTERN.matcher(code).matches();
    }

    public static void validate(String code) {
        if (!matches(code)) {
            throw new IllegalArgumentException(
                    "메시지 코드 형식이 올바르지 않습니다: " + code);
        }
    }
}
