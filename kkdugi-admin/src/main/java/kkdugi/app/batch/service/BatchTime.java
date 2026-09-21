package kkdugi.app.batch.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Runner API/관리자 API의 시각 표기: UTC, 소수 초 최대 6자리. */
public final class BatchTime {

    private BatchTime() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS).toString();
    }
}
