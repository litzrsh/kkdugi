package kkdugi.app.admin.code.exceptions;

public class CodeConflictException extends RuntimeException {

    private final String code;

    public CodeConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
