package kkdugi.app.admin.code.exceptions;

public class AdminCodeValidationException extends RuntimeException {

    private final String code;

    public AdminCodeValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
