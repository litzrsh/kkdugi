package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import kkdugi.app.batch.config.BatchProperties;

class BatchTimeTest {

    private static final Pattern RUNNER_TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");

    @Test
    void formatsUtcWithAtMostSixFractionalDigits() {
        assertThat(BatchTime.format(Instant.parse("2026-09-20T02:00:00Z"))).isEqualTo("2026-09-20T02:00:00Z");
        assertThat(BatchTime.format(Instant.parse("2026-09-20T02:00:00.123456789Z")))
                .isEqualTo("2026-09-20T02:00:00.123456Z");
        assertThat(BatchTime.format(Instant.now())).matches(RUNNER_TIMESTAMP);
    }

    @Test
    void propertiesDefaultsMatchTheContract() {
        BatchProperties props = new BatchProperties();
        assertThat(props.getHeartbeatSeconds()).isEqualTo(10);
        assertThat(props.getPollSeconds()).isEqualTo(3);
        assertThat(props.getLeaseSeconds()).isEqualTo(60);
        assertThat(props.getOnlineSeconds()).isEqualTo(30);
        assertThat(props.getEnrollmentTtl().toMinutes()).isEqualTo(10);
        assertThat(props.getAccessTokenTtl().toDays()).isEqualTo(30);
        assertThat(props.getIdempotencyRetention().toHours()).isEqualTo(72);
        assertThat(props.getRequestSkew().toMinutes()).isEqualTo(5);
    }
}
