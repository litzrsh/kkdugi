package kkdugi.api.admin.i18n;

import java.util.List;

import kkdugi.app.admin.i18n.models.MessageError;

public record MessageErrorResponse(
        List<MessageError> errors
) {
}
