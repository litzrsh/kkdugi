package kkdugi.app.batch.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.batch.config.BatchProperties;
import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.mapper.BatchCredentialMapper;
import kkdugi.app.batch.mapper.BatchRunnerMapper;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerParams;
import kkdugi.app.batch.models.BatchRunnerRequest;
import kkdugi.core.models.Page;

/**
 * Runner 설정 관리(관리자 API). 상태를 바꾸는 모든 메서드는 runner 행 잠금 아래에서 동작하고
 * event를 같은 트랜잭션에 남긴다. 멱등 키 처리는 호출자({@link BatchIdempotencyService})의 몫이다.
 */
@Service
public class BatchRunnerService {

    static final int MAX_CODE_LENGTH = 20;
    static final int MAX_NAME_LENGTH = 200;
    static final int MIN_CAPACITY = 1;
    static final int MAX_CAPACITY = 200;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,19}");

    private final BatchRunnerMapper runnerMapper;
    private final BatchCredentialMapper credentialMapper;
    private final BatchEventService events;
    private final BatchProperties properties;

    public BatchRunnerService(BatchRunnerMapper runnerMapper, BatchCredentialMapper credentialMapper,
            BatchEventService events, BatchProperties properties) {
        this.runnerMapper = runnerMapper;
        this.credentialMapper = credentialMapper;
        this.events = events;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Page<BatchRunner> search(BatchRunnerParams params) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());
        String status = params.getStatus();
        if (status != null && !status.isBlank()) {
            try {
                RunnerStatus.fromCode(status);
            } catch (IllegalArgumentException e) {
                throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
            }
        }
        return Page.of(runnerMapper.search(params.getCode(), params.getName(), status, params.getOffset(),
                params.getLimit(), properties.getOnlineSeconds()), params);
    }

    @Transactional(readOnly = true)
    public BatchRunner get(String id) {
        return runnerMapper.findById(id, properties.getOnlineSeconds())
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
    }

    @Transactional
    public BatchRunner create(BatchRunnerRequest request, String actorId) {
        String code = requireCode(request.getCode());
        String name = requireName(request.getName());
        int capacity = requireCapacity(request.getCapacity());
        if (request.getStatus() != null) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        if (runnerMapper.countByCode(code) > 0) {
            throw BatchException.conflict(BatchErrors.RUNNER_DUPLICATE_CODE);
        }
        BatchRunner row = new BatchRunner();
        row.setId(BatchIds.next(BatchIds.RUNNER));
        row.setCode(code);
        row.setName(name);
        row.setStatus(RunnerStatus.REGISTERING);
        row.setCapacity(capacity);
        row.setCreatorId(actorId);
        try {
            runnerMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw BatchException.conflict(BatchErrors.RUNNER_DUPLICATE_CODE);
        }
        events.record(EventTargetType.RUNNER, row.getId(), EventType.CREATED, null, RunnerStatus.REGISTERING.getCode(),
                ActorType.USER, actorId, detail("code", code, "capacity", capacity));
        return get(row.getId());
    }

    @Transactional
    public BatchRunner update(String id, BatchRunnerRequest request, long expectedVersion, String actorId) {
        BatchRunner current = runnerMapper.lockById(id)
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
        if (current.getConfigVer() != expectedVersion) {
            throw BatchException.preconditionFailed(BatchErrors.VERSION_CONFLICT);
        }
        String code = requireCode(request.getCode());
        if (!code.equals(current.getCode())) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID); // code는 생성 후 불변
        }
        String name = requireName(request.getName());
        int capacity = requireCapacity(request.getCapacity());

        RunnerStatus target = current.getStatus();
        if (request.getStatus() != null) {
            RunnerStatus requested;
            try {
                requested = RunnerStatus.fromCode(request.getStatus());
            } catch (IllegalArgumentException e) {
                throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
            }
            if (requested != RunnerStatus.ACTIVE && requested != RunnerStatus.PAUSED) {
                throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
            }
            if (current.getStatus() != RunnerStatus.ACTIVE && current.getStatus() != RunnerStatus.PAUSED) {
                throw BatchException.conflict(BatchErrors.STATE_CONFLICT); // 등록 전/폐기 후에는 상태를 바꿀 수 없다
            }
            target = requested;
        }
        // S3: 이 위치에서 capacity를 점유 슬롯보다 작게 줄이는 요청을 409로 막는다.
        if (runnerMapper.update(id, name, capacity, target, expectedVersion, actorId) != 1) {
            throw BatchException.preconditionFailed(BatchErrors.VERSION_CONFLICT);
        }
        events.record(EventTargetType.RUNNER, id, EventType.UPDATED, current.getStatus().getCode(), target.getCode(),
                ActorType.USER, actorId, detail("name", name, "capacity", capacity));
        return get(id);
    }

    @Transactional
    public void delete(String id, String actorId) {
        BatchRunner current = runnerMapper.lockById(id)
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
        if (current.getStatus() != RunnerStatus.REGISTERING && current.getStatus() != RunnerStatus.REVOKED) {
            throw BatchException.conflict(BatchErrors.STATE_CONFLICT); // 먼저 폐기해야 한다
        }
        // S3: attempt가 이 runner를 참조하면 FK 위반을 409 STATE_CONFLICT로 변환한다.
        credentialMapper.deleteByRunnerId(id);
        runnerMapper.deleteById(id);
        events.record(EventTargetType.RUNNER, id, EventType.DELETED, current.getStatus().getCode(), null,
                ActorType.USER, actorId, detail("code", current.getCode()));
    }

    private static String requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        return code;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME_LENGTH) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        return name.strip();
    }

    private static int requireCapacity(Integer capacity) {
        if (capacity == null || capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        return capacity;
    }

    private static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            detail.put((String) keyValues[i], keyValues[i + 1]);
        }
        return detail;
    }
}
