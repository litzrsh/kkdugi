package kkdugi.app.admin.menu.exceptions;

public class MenuConflictException extends RuntimeException {

    private final String code;

    public MenuConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
