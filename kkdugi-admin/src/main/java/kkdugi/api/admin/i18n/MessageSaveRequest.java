package kkdugi.api.admin.i18n;

import java.util.List;

public record MessageSaveRequest(
        List<MessageRowRequest> rows
) {
}
