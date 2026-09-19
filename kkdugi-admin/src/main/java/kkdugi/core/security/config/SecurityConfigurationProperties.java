package kkdugi.core.security.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "kkdugi.security")
public class SecurityConfigurationProperties {

    /** 비밀번호 변경/만료 연장 시 현재 시각부터의 유효 기간. 기본 30일. */
    private Duration passwordValidity = Duration.ofDays(30);

    public Duration getPasswordValidity() { return passwordValidity; }

    public void setPasswordValidity(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("password-validity must be positive");
        }
        passwordValidity = value;
    }

    private boolean allowMultiple = false;
    private Duration sessionTimeout = Duration.ofHours(1);

    /**
     * 슬라이딩 세션 갱신 최소 간격. 인증된 요청마다 만료 시각을 뒤로 미루되,
     * 마지막 갱신 후 이 시간이 지나지 않았으면 DB 쓰기를 건너뛴다 — 토큰이
     * 쿠키로도 전달되므로 정적 리소스 요청까지 모두 인증 요청이 되기 때문이다.
     * 실제 유휴 만료 시점은 {@code sessionTimeout}에서 최대 이 값만큼 짧아질 수 있다.
     */
    private Duration sessionRefreshInterval = Duration.ofMinutes(1);

    /** 발급한 토큰을 담는 쿠키 이름. 프론트(JS)도 이 이름으로 쿠키를 읽는다. */
    private String tokenCookieName = "KKDUGI_TOKEN";

    /**
     * JWT 서명(HS256)에 쓰는 base64 인코딩된 비밀키. 이 프로젝트의 JWT는
     * {@code sess_id}만 담는 불투명 토큰이라(실 사용자 데이터는
     * {@code kkdugi_session}에만 저장) 키 하나로 HMAC 서명/검증만 하면 된다.
     */
    private String jwtSecret;

    public boolean isAllowMultiple() {
        return allowMultiple;
    }

    public void setAllowMultiple(boolean allowMultiple) {
        this.allowMultiple = allowMultiple;
    }

    public Duration getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(Duration sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public Duration getSessionRefreshInterval() {
        return sessionRefreshInterval;
    }

    public void setSessionRefreshInterval(Duration sessionRefreshInterval) {
        this.sessionRefreshInterval = sessionRefreshInterval;
    }

    public String getTokenCookieName() {
        return tokenCookieName;
    }

    public void setTokenCookieName(String tokenCookieName) {
        this.tokenCookieName = tokenCookieName;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }
}
