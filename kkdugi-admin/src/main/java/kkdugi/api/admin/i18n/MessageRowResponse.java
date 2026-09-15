package kkdugi.api.admin.i18n;

import java.time.LocalDateTime;

public record MessageRowResponse(
        String msgCd,
        String langCd,
        String msgVal,
        LocalDateTime updDtm
) {
}
