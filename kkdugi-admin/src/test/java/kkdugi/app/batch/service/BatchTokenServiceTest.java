package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchTokenServiceTest {

    private final BatchTokenService tokens = new BatchTokenService();

    @Test
    void secretsAreRandomUrlSafeAnd256Bits() {
        String first = tokens.newSecret();
        String second = tokens.newSecret();

        assertThat(first).isNotEqualTo(second).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void joinAndParseRoundTrip() {
        String secret = tokens.newSecret();
        String token = tokens.join("BC202609200000" + "0001", secret);

        BatchTokenService.ParsedToken parsed = tokens.parse(token);

        assertThat(parsed).isNotNull();
        assertThat(parsed.getCredentialId()).isEqualTo("BC2026092000000001");
        assertThat(parsed.getSecret()).isEqualTo(secret);
    }

    @Test
    void parseRejectsMalformedTokens() {
        assertThat(tokens.parse(null)).isNull();
        assertThat(tokens.parse("")).isNull();
        assertThat(tokens.parse("no-dot")).isNull();
        assertThat(tokens.parse("BC1.short")).isNull();
        assertThat(tokens.parse("BC1." + "a".repeat(44))).isNull();
        assertThat(tokens.parse("bad id." + "a".repeat(43))).isNull();
        // 사용자 JWT는 점이 두 개라 secret 자리에 점이 들어가 거절된다.
        assertThat(tokens.parse("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl")).isNull();
        assertThat(tokens.parse("BC1." + "a".repeat(43) + "x".repeat(200))).isNull();
    }

    @Test
    void hashIsSha256HexAndMatchesInConstantTime() {
        String secret = tokens.newSecret();
        String hash = tokens.hash(secret);

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(tokens.matches(secret, hash)).isTrue();
        assertThat(tokens.matches(tokens.newSecret(), hash)).isFalse();
        assertThat(BatchTokenService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
