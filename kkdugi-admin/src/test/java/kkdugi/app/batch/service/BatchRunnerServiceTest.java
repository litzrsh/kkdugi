package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static kkdugi.support.BatchTestData.assertError;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerParams;
import kkdugi.app.batch.models.BatchRunnerRequest;
import kkdugi.core.models.Page;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchRunnerServiceTest {

    private static final String ACTOR = BatchTestData.USER;

    @Autowired
    private BatchRunnerService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    private static BatchRunnerRequest request(String code, String name, Integer capacity, String status) {
        BatchRunnerRequest request = new BatchRunnerRequest();
        request.setCode(code);
        request.setName(name);
        request.setCapacity(capacity);
        request.setStatus(status);
        return request;
    }

    private BatchRunner create(String code) {
        return service.create(request(code, "Runner " + code, 2, null), ACTOR);
    }

    @Test
    void createStartsRegisteringWithVersionOneAndWritesAnEvent() {
        BatchRunner created = create("tb-svc-1");

        assertThat(created.getId()).startsWith("BR").hasSize(18);
        assertThat(created.getStatus()).isEqualTo(RunnerStatus.REGISTERING);
        assertThat(created.getVersion()).isEqualTo("1");
        assertThat(created.getSession()).isEqualTo("0");
        assertThat(created.getCapacity()).isEqualTo(2);
        assertThat(created.getCreatorId()).isEqualTo(ACTOR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                + "AND event_type = 'CREATED' AND actor_id = ?", Integer.class, created.getId(), ACTOR)).isEqualTo(1);
    }

    @Test
    void createValidatesFields() {
        assertError(() -> service.create(request(null, "n", 1, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("bad code!", "n", 1, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("x".repeat(21), "n", 1, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", " ", 1, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", "n", null, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", "n", 0, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", "n", 201, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", "n", 1, "ACTIVE"), ACTOR), 400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void createRejectsDuplicateCode() {
        create("tb-svc-2");

        assertError(() -> create("tb-svc-2"), 409, BatchErrors.RUNNER_DUPLICATE_CODE);
    }

    @Test
    void getReturnsTheRunnerOrNotFound() {
        BatchRunner created = create("tb-svc-3");

        assertThat(service.get(created.getId()).getCode()).isEqualTo("tb-svc-3");
        assertError(() -> service.get("BRNOSUCH"), 404, BatchErrors.RUNNER_NOT_FOUND);
    }

    @Test
    void searchNormalizesPagingAndFilters() {
        create("tb-svc-a");
        create("tb-svc-b");
        BatchRunnerParams params = new BatchRunnerParams();
        params.setCode("tb-svc-");

        Page<BatchRunner> page = service.search(params);

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getTotalItems()).isEqualTo(2);
        assertThat(page.getContents()).extracting(BatchRunner::getCode).containsExactly("tb-svc-b", "tb-svc-a");

        params.setStatus("BOGUS");
        assertError(() -> service.search(params), 400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void updateRenamesAndBumpsTheVersion() {
        BatchRunner created = create("tb-svc-4");

        BatchRunner updated = service.update(created.getId(), request("tb-svc-4", "Renamed", 5, null), 1, ACTOR);

        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getCapacity()).isEqualTo(5);
        assertThat(updated.getVersion()).isEqualTo("2");
        assertThat(updated.getStatus()).isEqualTo(RunnerStatus.REGISTERING);
        assertThat(updated.getUpdaterId()).isEqualTo(ACTOR);
    }

    @Test
    void updateRejectsStaleVersionMissingRunnerAndCodeChange() {
        BatchRunner created = create("tb-svc-5");
        service.update(created.getId(), request("tb-svc-5", "v2", 2, null), 1, ACTOR);

        assertError(() -> service.update(created.getId(), request("tb-svc-5", "stale", 2, null), 1, ACTOR),
                412, BatchErrors.VERSION_CONFLICT);
        assertError(() -> service.update("BRNOSUCH", request("x", "n", 1, null), 1, ACTOR),
                404, BatchErrors.RUNNER_NOT_FOUND);
        assertError(() -> service.update(created.getId(), request("tb-other", "n", 2, null), 2, ACTOR),
                400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void statusChangesOnlyBetweenActiveAndPaused() {
        BatchRunner created = create("tb-svc-6");
        // REGISTERING에서는 status를 바꿀 수 없다.
        assertError(() -> service.update(created.getId(), request("tb-svc-6", "n", 2, "ACTIVE"), 1, ACTOR),
                409, BatchErrors.STATE_CONFLICT);

        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'ACTIVE' WHERE runner_id = ?", created.getId());

        BatchRunner paused = service.update(created.getId(), request("tb-svc-6", "n", 2, "PAUSED"), 1, ACTOR);
        assertThat(paused.getStatus()).isEqualTo(RunnerStatus.PAUSED);
        assertError(() -> service.update(created.getId(), request("tb-svc-6", "n", 2, "REVOKED"), 2, ACTOR),
                400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.update(created.getId(), request("tb-svc-6", "n", 2, "REGISTERING"), 2, ACTOR),
                400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void revokedRunnerStatusCannotBeChanged() {
        BatchRunner created = create("tb-svc-7");
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'REVOKED' WHERE runner_id = ?", created.getId());

        assertError(() -> service.update(created.getId(), request("tb-svc-7", "n", 2, "ACTIVE"), 1, ACTOR),
                409, BatchErrors.STATE_CONFLICT);
    }

    @Test
    void deleteIsAllowedOnlyWhenRegisteringOrRevoked() {
        BatchRunner registering = create("tb-svc-8");
        service.delete(registering.getId(), ACTOR);
        assertError(() -> service.get(registering.getId()), 404, BatchErrors.RUNNER_NOT_FOUND);
        // 감사 이력은 남는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                + "AND event_type = 'DELETED'", Integer.class, registering.getId())).isEqualTo(1);

        BatchRunner active = create("tb-svc-9");
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'ACTIVE' WHERE runner_id = ?", active.getId());
        assertError(() -> service.delete(active.getId(), ACTOR), 409, BatchErrors.STATE_CONFLICT);

        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'REVOKED' WHERE runner_id = ?", active.getId());
        service.delete(active.getId(), ACTOR);
        assertError(() -> service.delete("BRNOSUCH", ACTOR), 404, BatchErrors.RUNNER_NOT_FOUND);
    }

    @Test
    void deleteRemovesTheRunnersCredentials() {
        BatchRunner created = create("tb-svc-10");
        jdbc.update("INSERT INTO kkdugi_batch_runner_credential (credential_id, runner_id, credential_type, "
                + "secret_hash, expires_dtm, reg_id) VALUES (?, ?, 'ENROLLMENT', repeat('a', 64), now(), ?)",
                BatchIds.next(BatchIds.CREDENTIAL), created.getId(), ACTOR);

        service.delete(created.getId(), ACTOR);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential WHERE runner_id = ?",
                Integer.class, created.getId())).isZero();
    }

    @Test
    void failedCreateLeavesNoEvent() {
        create("tb-svc-11");
        Integer before = jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE actor_id = ?",
                Integer.class, ACTOR);

        assertError(() -> create("tb-svc-11"), 409, BatchErrors.RUNNER_DUPLICATE_CODE);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE actor_id = ?",
                Integer.class, ACTOR)).isEqualTo(before);
    }
}
