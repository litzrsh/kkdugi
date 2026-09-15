package kkdugi.app.admin.i18n;

import java.time.LocalDateTime;

public record MessageRowResult(
        String msgCd,
        String langCd,
        String msgVal,
        LocalDateTime updDtm
) {
}
