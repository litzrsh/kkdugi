package kkdugi.app.admin.user.exceptions;

/** 요청 값이 올바르지 않을 때(400). */
public class AdminUserValidationException extends RuntimeException {

    private final String code;

    public AdminUserValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
