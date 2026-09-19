package kkdugi.app.admin.i18n.exceptions;

public class MessageValidationException extends RuntimeException {

    private final String code;

    public MessageValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
