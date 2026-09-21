package kkdugi.api.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import kkdugi.api.BatchApiSupport;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchEnrollmentResult;
import kkdugi.app.batch.models.BatchIdempotencyKey;
import kkdugi.app.batch.models.BatchIdempotentResponse;
import kkdugi.app.batch.models.BatchRevokeRequest;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerParams;
import kkdugi.app.batch.models.BatchRunnerRequest;
import kkdugi.app.batch.service.BatchEnrollmentService;
import kkdugi.app.batch.service.BatchIdempotencyService;
import kkdugi.app.batch.service.BatchRequestKeys;
import kkdugi.app.batch.service.BatchRunnerService;
import kkdugi.core.Constants;
import kkdugi.core.enums.Rbac;
import kkdugi.core.models.Page;
import kkdugi.core.security.annotation.HasRole;
import kkdugi.core.security.annotation.RequireAuthority;
import kkdugi.core.util.SessionUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 배치 Runner 관리 API(admin-api.md). 메뉴 RBAC는 {@code admin/batch/runner} 프로그램에 묶인다 —
 * 조회 READ, 생성·수정·폐기·토큰 발급 WRTE, 삭제 DELT. 등록 토큰 발급·폐기는 SYS_ADMIN 역할이 추가로 필요하다.
 * 일회성 토큰 발급을 제외한 쓰기 명령은 {@code Idempotency-Key} + {@code X-Request-Created-At}가 필수다.
 */
@RestController
@RequestMapping("/api/v1.0/admin/batch/runners")
public class AdminBatchRunnerController extends BatchApiSupport {

    private static final String PROGRAM = "admin/batch/runner";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String CREATED_AT_HEADER = "X-Request-Created-At";

    private final BatchRunnerService runners;
    private final BatchEnrollmentService enrollment;
    private final BatchIdempotencyService idempotency;
    private final BatchRequestKeys keys;
    private final ObjectMapper objectMapper;

    public AdminBatchRunnerController(BatchRunnerService runners, BatchEnrollmentService enrollment,
            BatchIdempotencyService idempotency, BatchRequestKeys keys, ObjectMapper objectMapper) {
        this.runners = runners;
        this.enrollment = enrollment;
        this.idempotency = idempotency;
        this.keys = keys;
        this.objectMapper = objectMapper;
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @GetMapping
    public Page<BatchRunner> search(BatchRunnerParams params) {
        return runners.search(params);
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @GetMapping("/{id}")
    public BatchRunner get(@PathVariable("id") String id) {
        return runners.get(id);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @PostMapping
    public ResponseEntity<String> create(@RequestBody BatchRunnerRequest body,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @RequestHeader(value = CREATED_AT_HEADER, required = false) String createdAt,
            HttpServletRequest request) {
        String actor = actor();
        BatchIdempotencyKey idempotencyKey = keys.forUser(actor, "POST", path(request), key, createdAt, body, null);
        return json(idempotency.execute(idempotencyKey, () -> {
            BatchRunner created = runners.create(body, actor);
            return new BatchIdempotentResponse(201, write(created), request.getRequestURI() + "/" + created.getId());
        }));
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @PutMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable("id") String id, @RequestBody BatchRunnerRequest body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @RequestHeader(value = CREATED_AT_HEADER, required = false) String createdAt,
            HttpServletRequest request) {
        long expectedVersion = parseIfMatch(ifMatch);
        String actor = actor();
        BatchIdempotencyKey idempotencyKey = keys.forUser(actor, "PUT", path(request), key, createdAt, body, ifMatch);
        return json(idempotency.execute(idempotencyKey,
                () -> new BatchIdempotentResponse(200, write(runners.update(id, body, expectedVersion, actor)), null)));
    }

    @RequireAuthority(value = Rbac.DELT, program = PROGRAM)
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable("id") String id,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @RequestHeader(value = CREATED_AT_HEADER, required = false) String createdAt,
            HttpServletRequest request) {
        String actor = actor();
        BatchIdempotencyKey idempotencyKey = keys.forUser(actor, "DELETE", path(request), key, createdAt, null, null);
        return json(idempotency.execute(idempotencyKey, () -> {
            runners.delete(id, actor);
            return new BatchIdempotentResponse(204, null, null);
        }));
    }

    /** 평문 토큰을 한 번만 내려주므로 멱등 재현 대상이 아니다(응답을 잃으면 새로 발급한다). */
    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/enrollment")
    public ResponseEntity<BatchEnrollmentResult> enroll(@PathVariable("id") String id) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(enrollment.issue(id, actor()));
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/revoke")
    public ResponseEntity<String> revoke(@PathVariable("id") String id, @RequestBody BatchRevokeRequest body,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @RequestHeader(value = CREATED_AT_HEADER, required = false) String createdAt,
            HttpServletRequest request) {
        String actor = actor();
        BatchIdempotencyKey idempotencyKey = keys.forUser(actor, "POST", path(request), key, createdAt, body, null);
        return json(idempotency.execute(idempotencyKey,
                () -> new BatchIdempotentResponse(200, write(enrollment.revoke(id, body.getReason(), actor)), null)));
    }

    private static String actor() {
        return SessionUtils.getUser().getId();
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private String write(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    /** {@code If-Match: "3"} 또는 {@code 3}. 누락은 428, 형식 오류는 400. */
    private static long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            throw BatchException.preconditionRequired(BatchErrors.VERSION_REQUIRED);
        }
        String value = header.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
    }
}
