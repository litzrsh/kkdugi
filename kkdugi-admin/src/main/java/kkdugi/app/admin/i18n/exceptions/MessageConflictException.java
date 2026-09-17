package kkdugi.app.admin.i18n.exceptions;

import java.util.List;

import kkdugi.app.admin.i18n.models.MessageError;

public class MessageConflictException extends RuntimeException {

    private final List<MessageError> errors;

    public MessageConflictException(String code, String reason) {
        super(reason);
        this.errors = List.of(new MessageError(code, reason));
    }

    public List<MessageError> getErrors() {
        return errors;
    }
}
