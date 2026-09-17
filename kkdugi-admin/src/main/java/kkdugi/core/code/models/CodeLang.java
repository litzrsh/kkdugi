package kkdugi.core.code.models;

import java.time.LocalDateTime;

public record CodeLang(
        String codeId,
        String langCode,
        String name,
        String remarks,
        LocalDateTime createdAt,
        String createdId,
        LocalDateTime updatedAt,
        String updatedId
) {
}
