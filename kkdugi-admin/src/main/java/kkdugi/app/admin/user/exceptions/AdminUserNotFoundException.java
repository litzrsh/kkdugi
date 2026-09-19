package kkdugi.app.admin.user.exceptions;

/** 대상 사용자가 없을 때(404). */
public class AdminUserNotFoundException extends RuntimeException {

    private final String code;

    public AdminUserNotFoundException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
