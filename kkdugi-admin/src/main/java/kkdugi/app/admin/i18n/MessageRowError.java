package kkdugi.app.admin.i18n;

public record MessageRowError(
        int rowIndex,
        String msgCd,
        String langCd,
        String reason
) {
}
