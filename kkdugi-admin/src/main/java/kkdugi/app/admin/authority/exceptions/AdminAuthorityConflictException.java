package kkdugi.app.admin.authority.exceptions;

/** 현재 상태와 충돌할 때(409) — 유형+role 중복, SYS_ADMIN 보호. */
public class AdminAuthorityConflictException extends RuntimeException {

    private final String code;

    public AdminAuthorityConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
