package kkdugi.app.batch.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
import kkdugi.app.batch.models.BatchHeartbeatAction;
import kkdugi.app.batch.models.BatchHeartbeatItem;
import kkdugi.app.batch.models.BatchHeartbeatRequest;
import kkdugi.app.batch.models.BatchHeartbeatResponse;
import kkdugi.app.batch.models.BatchHeartbeatState;
import kkdugi.app.batch.models.BatchLimits;
import kkdugi.app.batch.models.BatchRegistrationRequest;
import kkdugi.app.batch.models.BatchRegistrationResponse;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerCredential;
import kkdugi.app.batch.models.BatchSessionRequest;
import kkdugi.app.batch.models.BatchSessionResponse;

/**
 * Runner가 호출하는 등록·세션·heartbeat. 인증(토큰 → runnerId/credentialId)은 보안 필터가 끝낸 뒤 호출된다.
 * 상태를 바꾸는 작업(등록, 세션 개설)은 runner 행을 잠근 뒤 credential 유효성과 상태를 다시 확인한다.
 */
@Service
public class BatchAgentService {

    private static final Set<String> OS = Set.of("LINUX", "WINDOWS");
    private static final Set<String> ARCHITECTURE = Set.of("AMD64", "ARM64");
    private static final Set<String> MODES = Set.of("ACCEPTING", "DRAINING", "DEGRADED");
    private static final Set<String> PHASES =
            Set.of("ASSIGNED", "STARTING", "RUNNING", "STOPPING", "FINISHED", "UNKNOWN");
    private static final Pattern BOOT_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final int MAX_HEARTBEAT_ITEMS = 200;
    private static final int MAX_ASSIGNMENT_ID_LENGTH = 20;

    private final BatchRunnerMapper runnerMapper;
    private final BatchCredentialMapper credentialMapper;
    private final BatchTokenService tokens;
    private final BatchEventService events;
    private final BatchProperties properties;

    public BatchAgentService(BatchRunnerMapper runnerMapper, BatchCredentialMapper credentialMapper,
            BatchTokenService tokens, BatchEventService events, BatchProperties properties) {
        this.runnerMapper = runnerMapper;
        this.credentialMapper = credentialMapper;
        this.tokens = tokens;
        this.events = events;
        this.properties = properties;
    }

    @Transactional
    public BatchRegistrationResponse register(String runnerId, String credentialId, BatchRegistrationRequest request) {
        String runnerCode = requireText(request.getRunnerCode(), 20);
        String agentVersion = requireText(request.getAgentVersion(), 50);
        String hostname = requireText(request.getHostname(), 200);
        String os = requireText(request.getOs(), 20);
        String architecture = requireText(request.getArchitecture(), 20);

        BatchRunner runner = runnerMapper.lockById(runnerId)
                .orElseThrow(() -> BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID));
        if (runner.getStatus() != RunnerStatus.REGISTERING) {
            throw BatchException.conflict(BatchErrors.STATE_CONFLICT);
        }
        if (!runner.getCode().equals(runnerCode)) {
            throw BatchException.forbidden(BatchErrors.RUNNER_FORBIDDEN);
        }
        if (!OS.contains(os) || !ARCHITECTURE.contains(architecture)) {
            throw BatchException.conflict(BatchErrors.PLATFORM_UNSUPPORTED);
        }
        // 원자 소비: 동시에 같은 토큰으로 온 요청 중 하나만 1을 받는다. 실패는 트랜잭션 전체를 되돌린다.
        if (credentialMapper.consumeEnrollment(credentialId) != 1) {
            throw BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID);
        }

        String accessId = BatchIds.next(BatchIds.CREDENTIAL);
        String secret = tokens.newSecret();
        BatchRunnerCredential access = new BatchRunnerCredential();
        access.setId(accessId);
        access.setRunnerId(runnerId);
        access.setType(CredentialType.ACCESS);
        access.setSecretHash(tokens.hash(secret));
        access.setCreatorId(runnerId);
        credentialMapper.insert(access, properties.getAccessTokenTtl().toSeconds());
        runnerMapper.markRegistered(runnerId, hostname, os, agentVersion, runnerId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("enrollmentCredentialId", credentialId);
        detail.put("accessCredentialId", accessId);
        detail.put("architecture", architecture);
        events.record(EventTargetType.RUNNER, runnerId, EventType.REGISTERED, RunnerStatus.REGISTERING.getCode(),
                RunnerStatus.ACTIVE.getCode(), ActorType.RUNNER, runnerId, detail);

        Instant expiresAt = credentialMapper.findForAuth(accessId).orElseThrow().getExpiresAt();
        return new BatchRegistrationResponse(runnerId, accessId, tokens.join(accessId, secret),
                BatchTime.format(expiresAt), Long.toString(runner.getSessionVer()));
    }

    @Transactional
    public BatchSessionResponse openSession(String runnerId, String credentialId, BatchSessionRequest request) {
        String bootId = request.getBootId();
        if (bootId == null || !BOOT_ID.matcher(bootId).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        long expectedSession = parseSession(request.getExpectedSession());
        String agentVersion = requireText(request.getAgentVersion(), 50);

        BatchRunner runner = runnerMapper.lockById(runnerId)
                .orElseThrow(() -> BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID));
        // 잠금 아래에서 자격증명을 다시 확인한다: 폐기와 겹쳐도 폐기 이후의 세션 개설은 성공하지 못한다.
        credentialMapper.findForAuth(credentialId)
                .filter(c -> c.isValid() && c.getType() == CredentialType.ACCESS && runnerId.equals(c.getRunnerId()))
                .orElseThrow(() -> BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID));
        if (runner.getStatus() != RunnerStatus.ACTIVE && runner.getStatus() != RunnerStatus.PAUSED) {
            throw BatchException.forbidden(BatchErrors.RUNNER_FORBIDDEN);
        }

        if (!bootId.equals(runner.getBootRef())) { // 같은 bootId의 재전송이면 현재 세대를 그대로 돌려준다
            if (runner.getSessionVer() != expectedSession) {
                throw BatchException.conflict(BatchErrors.SESSION_STALE);
            }
            runnerMapper.openSession(runnerId, bootId, agentVersion);
            events.record(EventTargetType.RUNNER, runnerId, EventType.SESSION_OPENED, null, null, ActorType.RUNNER,
                    runnerId, Map.of("session", Long.toString(runner.getSessionVer() + 1)));
        }

        BatchRunner current = runnerMapper.findById(runnerId, properties.getOnlineSeconds()).orElseThrow();
        return new BatchSessionResponse(runnerId, Long.toString(current.getSessionVer()),
                BatchTime.format(runnerMapper.now()), properties.getHeartbeatSeconds(), properties.getPollSeconds(),
                properties.getLeaseSeconds(), current.getCapacity(),
                new BatchLimits(properties.getJsonBytes(), properties.getInputBytes(), properties.getResultBytes(),
                        properties.getLogChunkBytes()));
    }

    @Transactional
    public BatchHeartbeatResponse heartbeat(String runnerId, String sessionHeader, BatchHeartbeatRequest request) {
        long session = parseSession(sessionHeader);
        validate(request);

        BatchHeartbeatState state = runnerMapper.touch(runnerId, session).orElse(null);
        if (state == null) {
            BatchRunner runner = runnerMapper.findById(runnerId, properties.getOnlineSeconds())
                    .orElseThrow(() -> BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID));
            if (runner.getStatus() != RunnerStatus.ACTIVE && runner.getStatus() != RunnerStatus.PAUSED) {
                throw BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID);
            }
            throw BatchException.conflict(BatchErrors.SESSION_STALE);
        }
        if (request.getFreeSlots() > state.getCapacity()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID); // 예외로 touch도 롤백된다
        }

        // S1 한계: 배정(Attempt) 테이블이 없으므로 보고된 assignment id는 모두 admin이 모르는 값이다.
        // S3에서 실제 조회로 대체한다(빈 배열을 고정 반환하는 임시 구현으로 남기지 않는다).
        List<BatchHeartbeatAction> actions = request.getAssignments().stream()
                .map(item -> new BatchHeartbeatAction(item.getId(), "RECONCILE", null, null, null)).toList();
        return new BatchHeartbeatResponse(BatchTime.format(runnerMapper.now()),
                state.getStatus() == RunnerStatus.ACTIVE, actions);
    }

    private void validate(BatchHeartbeatRequest request) {
        if (request.getObservedAt() == null || request.getMode() == null || request.getFreeSlots() == null
                || request.getFreeSlots() < 0 || request.getAssignments() == null
                || !MODES.contains(request.getMode())) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        try {
            Instant.parse(request.getObservedAt());
        } catch (DateTimeParseException e) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        if (request.getAssignments().size() > MAX_HEARTBEAT_ITEMS) {
            throw BatchException.tooLarge(BatchErrors.PAYLOAD_TOO_LARGE);
        }
        for (BatchHeartbeatItem item : request.getAssignments()) {
            if (item == null || item.getId() == null || item.getId().isBlank()
                    || item.getId().length() > MAX_ASSIGNMENT_ID_LENGTH
                    || item.getPhase() == null || !PHASES.contains(item.getPhase())) { // Set.of(...).contains(null)은 NPE
                throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
            }
        }
    }

    private static long parseSession(String value) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID); // bigint 범위를 넘는 값
        }
    }

    private static String requireText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        return value;
    }
}
