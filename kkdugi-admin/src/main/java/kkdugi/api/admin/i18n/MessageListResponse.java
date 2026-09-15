package kkdugi.api.admin.i18n;

import java.util.List;

public record MessageListResponse(
        List<MessageRowResponse> rows,
        long totalCount,
        int page,
        int size
) {
}
