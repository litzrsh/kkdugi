package kkdugi.app.admin.i18n.exceptions;

import java.util.List;

import kkdugi.app.admin.i18n.models.MessageError;

public class MessageValidationException extends RuntimeException {

    private final List<MessageError> errors;

    public MessageValidationException(List<MessageError> errors) {
        super("메시지 저장 요청 검증에 실패했습니다");
        this.errors = errors;
    }

    public List<MessageError> getErrors() {
        return errors;
    }
}
