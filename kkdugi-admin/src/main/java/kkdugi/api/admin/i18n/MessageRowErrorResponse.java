package kkdugi.api.admin.i18n;

public record MessageRowErrorResponse(
        int rowIndex,
        String msgCd,
        String langCd,
        String reason
) {
}
