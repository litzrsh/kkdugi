package kkdugi.core.security.service;

import java.util.Base64;
import java.util.Date;

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

        String token = service.issue("S_TEST_1", new Date(System.currentTimeMillis() + 60_000));

        assertThat(service.extractSessionId(token)).isEqualTo("S_TEST_1");
    }

    @Test
    void extractSessionId_expiredToken_throwsInvalidToken() {
        JwtTokenService service = newService(randomSecret());

        String token = service.issue("S_TEST_2", new Date(System.currentTimeMillis() - 1_000));

        assertThatThrownBy(() -> service.extractSessionId(token))
                .isInstanceOf(RestfulAuthenticationException.class)
                .satisfies(ex -> assertThat(((RestfulAuthenticationException) ex).getExceptionMessage().getCode())
                        .isEqualTo(JwtTokenService.ERR_INVALID_TOKEN));
    }

    @Test
    void extractSessionId_wrongSigningKey_throwsInvalidToken() {
        JwtTokenService issuer = newService(randomSecret());
        JwtTokenService verifier = newService(randomSecret());

        String token = issuer.issue("S_TEST_3", new Date(System.currentTimeMillis() + 60_000));

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
