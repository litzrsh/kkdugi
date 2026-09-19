package kkdugi.app.admin.i18n.exceptions;

public class AdminMessageValidationException extends RuntimeException {

    private final String code;

    public AdminMessageValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
