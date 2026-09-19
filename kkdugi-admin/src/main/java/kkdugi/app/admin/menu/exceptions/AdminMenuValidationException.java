package kkdugi.app.admin.menu.exceptions;

public class AdminMenuValidationException extends RuntimeException {

    private final String code;

    public AdminMenuValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
