package kkdugi.app.admin.i18n.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MessageError {

    private final String code;
    private final String reason;
}
