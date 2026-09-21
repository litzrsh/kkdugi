package kkdugi.app.batch.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Runner 토큰 발급·해석. 토큰은 {@code {credentialId}.{secret}}이고 secret은 256비트 난수라서
 * 느린 password hash가 아니라 SHA-256 hex만 DB에 저장한다(평문은 발급 응답에서 한 번만 나간다).
 */
@Service
public class BatchTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CREDENTIAL_ID = Pattern.compile("[A-Za-z0-9]{1,20}");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int MAX_TOKEN_LENGTH = 200;

    public String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String join(String credentialId, String secret) {
        return credentialId + "." + secret;
    }

    /** 형식이 올바르지 않으면 null. 존재 여부·유효성은 호출자가 DB로 확인한다. */
    public ParsedToken parse(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot < 1) {
            return null;
        }
        String credentialId = token.substring(0, dot);
        String secret = token.substring(dot + 1);
        if (!CREDENTIAL_ID.matcher(credentialId).matches() || !SECRET.matcher(secret).matches()) {
            return null;
        }
        return new ParsedToken(credentialId, secret);
    }

    public String hash(String secret) {
        return sha256Hex(secret);
    }

    public boolean matches(String secret, String storedHash) {
        return storedHash != null && MessageDigest.isEqual(
                hash(secret).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Getter
    @AllArgsConstructor
    public static class ParsedToken {

        private final String credentialId;
        private final String secret;
    }
}
