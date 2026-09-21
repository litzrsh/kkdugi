package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 등록 응답. {@code accessToken}은 이 응답에서 한 번만 나간다. */
@Getter
@AllArgsConstructor
public class BatchRegistrationResponse {
    private final String runnerId;
    private final String credentialId;
    private final String accessToken;
    private final String tokenExpiresAt;
    private final String session;
}
