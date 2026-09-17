package kkdugi.core.security.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "kkdugi.security")
public class SecurityConfigurationProperties {

    private boolean allowMultiple = false;
    private Duration sessionTimeout = Duration.ofHours(1);

    /**
     * JWT 서명(HS256)에 쓰는 base64 인코딩된 비밀키. 이 프로젝트의 JWT는
     * {@code sess_id}/{@code exp}만 담는 불투명 토큰이라(실 사용자 데이터는
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

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }
}
