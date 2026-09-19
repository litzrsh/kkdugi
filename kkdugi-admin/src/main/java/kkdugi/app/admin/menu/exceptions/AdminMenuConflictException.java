package kkdugi.app.admin.menu.exceptions;

public class AdminMenuConflictException extends RuntimeException {

    private final String code;

    public AdminMenuConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
