package kkdugi.app.admin.i18n.models;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MessageContent {

    private final String code;
    private final Map<String, String> locale;
}
