package kkdugi.core.security.service;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import kkdugi.core.exceptions.RestfulAuthenticationException;
import kkdugi.core.security.config.SecurityConfigurationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private JwtTokenService newService(String base64Secret) {
        SecurityConfigurationProperties properties = new SecurityConfigurationProperties();
        properties.setJwtSecret(base64Secret);
        return new JwtTokenService(properties);
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void issue_thenExtractSessionId_roundTrips() {
        JwtTokenService service = newService(randomSecret());

        String token = service.issue("S_TEST_1");

        assertThat(service.extractSessionId(token)).isEqualTo("S_TEST_1");
    }

    @Test
    void issue_carriesNoExpiry_sessionRowOwnsExpiry() {
        JwtTokenService service = newService(randomSecret());

        String token = service.issue("S_TEST_2");

        // payload(두 번째 조각)에 exp 클레임이 없다 — 슬라이딩 세션이라 만료는 DB가 결정한다.
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).contains("sess_id").doesNotContain("exp");
    }

    @Test
    void extractSessionId_wrongSigningKey_throwsInvalidToken() {
        JwtTokenService issuer = newService(randomSecret());
        JwtTokenService verifier = newService(randomSecret());

        String token = issuer.issue("S_TEST_3");

        assertThatThrownBy(() -> verifier.extractSessionId(token))
                .isInstanceOf(RestfulAuthenticationException.class);
    }

    @Test
    void extractSessionId_malformedToken_throwsInvalidToken() {
        JwtTokenService service = newService(randomSecret());

        assertThatThrownBy(() -> service.extractSessionId("not-a-jwt"))
                .isInstanceOf(RestfulAuthenticationException.class);
    }
}
