package kkdugi.app.admin.i18n.exceptions;

public class MessageConflictException extends RuntimeException {

    private final String code;

    public MessageConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
