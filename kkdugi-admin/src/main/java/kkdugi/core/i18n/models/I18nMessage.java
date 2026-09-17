package kkdugi.core.i18n.models;

import java.time.LocalDateTime;

public record I18nMessage(
        String msgCode,
        String langCode,
        String msgText,
        LocalDateTime createdAt,
        String createdId,
        LocalDateTime updatedAt,
        String updatedId
) {
}
