package kkdugi.app.admin.i18n.models;

import java.util.Map;

public record MessageContent(
        String code,
        Map<String, String> locale
) {
}
