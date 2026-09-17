package kkdugi.core.exceptions;

import kkdugi.core.util.MessageUtils;

public class ExceptionMessage {

    private final String code;
    private final Object[] args;

    public ExceptionMessage(String code, Object... args) {
        this.code = code;
        this.args = args;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return MessageUtils.getMessage(code, args);
    }
}
