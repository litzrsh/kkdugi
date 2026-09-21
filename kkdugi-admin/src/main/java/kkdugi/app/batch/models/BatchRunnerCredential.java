package kkdugi.app.batch.models;

import java.time.Instant;
import kkdugi.app.batch.enums.CredentialType;
import kkdugi.core.models.BaseModel;
import lombok.Getter;
import lombok.Setter;

/**
 * kkdugi_batch_runner_credential 한 행. 'valid'는 데이터베이스 시계를 기준으로 계산됩니다.
 */
@Getter
@Setter
public class BatchRunnerCredential extends BaseModel {

    private String id;
    private String runnerId;
    private CredentialType type;
    private String secretHash;
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant revokedAt;
    private Instant lastUsedAt;
    private boolean valid;
}
