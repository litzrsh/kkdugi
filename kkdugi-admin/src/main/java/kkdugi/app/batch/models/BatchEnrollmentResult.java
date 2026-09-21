package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** enrollment token issue response, the plaintext token is returned only once and is never stored or replayed. */
@Getter
@AllArgsConstructor
public class BatchEnrollmentResult {
    private final String credentialId;
    private final String enrollmentToken;
    private final String expiresAt;
}
