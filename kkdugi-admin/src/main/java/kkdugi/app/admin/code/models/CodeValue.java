package kkdugi.app.admin.code.models;

import java.util.regex.Pattern;

public final class CodeValue {

    private static final Pattern PATTERN = Pattern.compile("^[A-Z0-9]+(_[A-Z0-9]+)*$");

    private CodeValue() {
    }

    public static boolean matches(String code) {
        return code != null && PATTERN.matcher(code).matches();
    }
}
