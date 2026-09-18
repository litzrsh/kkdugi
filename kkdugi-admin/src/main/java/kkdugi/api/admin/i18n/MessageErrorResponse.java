package kkdugi.api.admin.i18n;

import java.util.List;

import kkdugi.app.admin.i18n.models.MessageError;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MessageErrorResponse {

    private final List<MessageError> errors;
}
