package kkdugi.app.admin.i18n;

import java.util.List;

public class MessageConflictException extends RuntimeException {

    private final List<MessageRowError> errors;

    public MessageConflictException(int rowIndex, String msgCd, String langCd, String reason) {
        super(reason);
        this.errors = List.of(new MessageRowError(rowIndex, msgCd, langCd, reason));
    }

    public List<MessageRowError> getErrors() {
        return errors;
    }
}
