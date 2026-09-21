package kkdugi.app.batch.service;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.batch.config.BatchProperties;
import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.CredentialType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.mapper.BatchCredentialMapper;
import kkdugi.app.batch.mapper.BatchRunnerMapper;
import kkdugi.app.batch.models.BatchEnrollmentResult;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerCredential;

/**
 * 등록 토큰 발급과 자격증명 폐기. 모든 메서드는 runner 행을 먼저 잠근다 —
 * 등록/세션 개설/폐기와 같은 잠금을 공유해야 상태 전이가 직렬화된다.
 */
@Service
public class BatchEnrollmentService {

    private static final int MAX_REASON_LENGTH = 4000;

    private final BatchRunnerMapper runnerMapper;
    private final BatchCredentialMapper credentialMapper;
    private final BatchTokenService tokens;
    private final BatchEventService events;
    private final BatchProperties properties;

    public BatchEnrollmentService(BatchRunnerMapper runnerMapper, BatchCredentialMapper credentialMapper,
            BatchTokenService tokens, BatchEventService events, BatchProperties properties) {
        this.runnerMapper = runnerMapper;
        this.credentialMapper = credentialMapper;
        this.tokens = tokens;
        this.events = events;
        this.properties = properties;
    }

    @Transactional
    public BatchEnrollmentResult issue(String runnerId, String actorId) {
        BatchRunner runner = runnerMapper.lockById(runnerId)
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
        if (runner.getStatus() != RunnerStatus.REGISTERING && runner.getStatus() != RunnerStatus.REVOKED) {
            throw BatchException.conflict(BatchErrors.STATE_CONFLICT); // 재등록은 먼저 폐기해야 한다
        }
        // S3: REVOKED runner의 재등록 토큰 발급에 미해결 실행 검사를 연결한다.
        credentialMapper.revokeUnusedEnrollments(runnerId);

        String credentialId = BatchIds.next(BatchIds.CREDENTIAL);
        String secret = tokens.newSecret();
        BatchRunnerCredential row = new BatchRunnerCredential();
        row.setId(credentialId);
        row.setRunnerId(runnerId);
        row.setType(CredentialType.ENROLLMENT);
        row.setSecretHash(tokens.hash(secret));
        row.setCreatorId(actorId);
        credentialMapper.insert(row, properties.getEnrollmentTtl().toSeconds());

        if (runner.getStatus() == RunnerStatus.REVOKED) {
            runnerMapper.changeStatus(runnerId, RunnerStatus.REGISTERING, actorId);
        }
        events.record(EventTargetType.RUNNER, runnerId, EventType.ENROLLMENT_ISSUED, runner.getStatus().getCode(),
                RunnerStatus.REGISTERING.getCode(), ActorType.USER, actorId, Map.of("credentialId", credentialId));

        Instant expiresAt = credentialMapper.findForAuth(credentialId).orElseThrow().getExpiresAt();
        return new BatchEnrollmentResult(credentialId, tokens.join(credentialId, secret), BatchTime.format(expiresAt));
    }

    @Transactional
    public BatchRunner revoke(String runnerId, String reason, String actorId) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        BatchRunner runner = runnerMapper.lockById(runnerId)
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
        if (runner.getStatus() != RunnerStatus.REVOKED) {
            credentialMapper.revokeAll(runnerId);
            runnerMapper.changeStatus(runnerId, RunnerStatus.REVOKED, actorId);
            // 폐기는 실행 프로세스의 종료를 뜻하지 않는다(S3 이후 미해결 Attempt는 별도 복구 대상).
            events.record(EventTargetType.RUNNER, runnerId, EventType.REVOKED, runner.getStatus().getCode(),
                    RunnerStatus.REVOKED.getCode(), ActorType.USER, actorId, Map.of("reason", reason));
        }
        return runnerMapper.findById(runnerId, properties.getOnlineSeconds()).orElseThrow();
    }
}
