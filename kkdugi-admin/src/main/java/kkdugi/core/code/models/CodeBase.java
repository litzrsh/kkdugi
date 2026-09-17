package kkdugi.core.code.models;

import java.time.LocalDateTime;

public record CodeBase(
        String id,
        String parentId,
        String code,
        String extra1,
        String extra2,
        String extra3,
        String extra4,
        String extra5,
        Integer level,
        String path,
        Integer sort,
        String use,
        LocalDateTime createdAt,
        String createdId,
        LocalDateTime updatedAt,
        String updatedId
) {
}
