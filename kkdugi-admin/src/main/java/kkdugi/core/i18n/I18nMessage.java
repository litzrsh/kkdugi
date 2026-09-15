package kkdugi.core.i18n;

import java.time.LocalDateTime;

public record I18nMessage(
        String msgCd,
        String langCd,
        String msgVal,
        LocalDateTime regDtm,
        String regId,
        LocalDateTime updDtm,
        String updId
) {
}
