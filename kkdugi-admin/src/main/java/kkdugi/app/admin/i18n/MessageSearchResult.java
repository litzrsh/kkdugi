package kkdugi.app.admin.i18n;

import kkdugi.core.i18n.I18nMessage;

import java.util.List;

public record MessageSearchResult(
        List<I18nMessage> rows,
        long totalCount,
        int page,
        int size
) {
}
