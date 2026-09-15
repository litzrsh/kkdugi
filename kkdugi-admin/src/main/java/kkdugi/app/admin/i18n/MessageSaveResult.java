package kkdugi.app.admin.i18n;

import java.util.List;

public record MessageSaveResult(
        int insertedCount,
        int updatedCount,
        int deletedCount,
        List<MessageRowResult> rows
) {
}
