package kkdugi.app.admin.code.exceptions;

public class AdminCodeConflictException extends RuntimeException {

    private final String code;

    public AdminCodeConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
