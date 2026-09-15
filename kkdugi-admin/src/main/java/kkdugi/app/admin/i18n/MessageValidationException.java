package kkdugi.app.admin.i18n;

import java.util.List;

public class MessageValidationException extends RuntimeException {

    private final List<MessageRowError> errors;

    public MessageValidationException(List<MessageRowError> errors) {
        super("메시지 저장 요청 검증에 실패했습니다");
        this.errors = errors;
    }

    public List<MessageRowError> getErrors() {
        return errors;
    }
}
