package kkdugi.app.admin.code.exceptions;

public class CodeValidationException extends RuntimeException {

    private final String code;

    public CodeValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
