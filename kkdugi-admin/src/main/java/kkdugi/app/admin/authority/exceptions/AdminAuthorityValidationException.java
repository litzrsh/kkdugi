package kkdugi.app.admin.authority.exceptions;

/** 요청 값이 올바르지 않을 때(400). */
public class AdminAuthorityValidationException extends RuntimeException {

    private final String code;

    public AdminAuthorityValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
