package kkdugi.api.admin.i18n;

import java.util.List;

public record MessageSaveResponse(
        int insertedCount,
        int updatedCount,
        int deletedCount,
        List<MessageRowResponse> rows
) {
}
