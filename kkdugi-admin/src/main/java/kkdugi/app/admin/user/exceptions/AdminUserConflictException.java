package kkdugi.app.admin.user.exceptions;

/** 현재 상태와 충돌할 때(409) — username/email 중복, username 변경, 자기 삭제. */
public class AdminUserConflictException extends RuntimeException {

    private final String code;

    public AdminUserConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
