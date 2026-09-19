package kkdugi.app.admin.authority.exceptions;

/** 대상 권한이 없을 때(404). */
public class AdminAuthorityNotFoundException extends RuntimeException {

    private final String code;

    public AdminAuthorityNotFoundException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
