package kkdugi.app.admin.i18n.exceptions;

public class AdminMessageConflictException extends RuntimeException {

    private final String code;

    public AdminMessageConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
