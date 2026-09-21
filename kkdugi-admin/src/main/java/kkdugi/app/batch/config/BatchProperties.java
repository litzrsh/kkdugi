package kkdugi.app.batch.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/** 배치 운영 기본값(application.yml의 kkdugi.batch.*로 조정). 기본값은 runner-api.md의 운영 기본값이다. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kkdugi.batch")
public class BatchProperties {

    private Duration enrollmentTtl = Duration.ofMinutes(10);
    private Duration accessTokenTtl = Duration.ofDays(30);
    private int heartbeatSeconds = 10;
    private int pollSeconds = 3;
    private int leaseSeconds = 60;
    private int jsonBytes = 1_048_576;
    private int inputBytes = 262_144;
    private int resultBytes = 262_144;
    private int logChunkBytes = 32_768;
    private Duration idempotencyRetention = Duration.ofHours(72);
    private Duration requestSkew = Duration.ofMinutes(5);
    /** credential.last_used_dtm을 이 간격(초)보다 자주 갱신하지 않는다. */
    private int lastUsedTouchSeconds = 60;

    /** last_seen이 이 시간(초) 이내면 online. heartbeat 주기의 3배. */
    public int getOnlineSeconds() {
        return heartbeatSeconds * 3;
    }
}
