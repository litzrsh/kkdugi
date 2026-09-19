package kkdugi.app.admin.menu.exceptions;

public class MenuValidationException extends RuntimeException {

    private final String code;

    public MenuValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
