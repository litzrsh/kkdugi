package kkdugi.app.batch.service;

import static kkdugi.support.BatchTestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.models.BatchEnrollmentResult;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchEnrollmentServiceTest {

    private static final String ACTOR = BatchTestData.USER;

    @Autowired
    private BatchEnrollmentService service;

    @Autowired
    private BatchTokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    private void insertAccessCredential(String runnerId) {
        jdbc.update("INSERT INTO kkdugi_batch_runner_credential (credential_id, runner_id, credential_type, "
                + "secret_hash, expires_dtm, reg_id) VALUES (?, ?, 'ACCESS', repeat('b', 64), now() + interval '1 day', ?)",
                BatchIds.next(BatchIds.CREDENTIAL), runnerId, ACTOR);
    }

    @Test
    void issueCreatesAOneTimeTokenThatExpiresInTenMinutes() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-1", "REGISTERING");

        BatchEnrollmentResult result = service.issue(runnerId, ACTOR);

        BatchTokenService.ParsedToken parsed = tokens.parse(result.getEnrollmentToken());
        assertThat(parsed).isNotNull();
        assertThat(parsed.getCredentialId()).isEqualTo(result.getCredentialId());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT credential_type, secret_hash FROM kkdugi_batch_runner_credential WHERE credential_id = ?",
                result.getCredentialId());
        assertThat(row.get("credential_type")).isEqualTo("ENROLLMENT");
        assertThat(row.get("secret_hash")).isEqualTo(tokens.hash(parsed.getSecret()));
        Long seconds = jdbc.queryForObject("SELECT extract(epoch FROM (expires_dtm - now()))::bigint "
                + "FROM kkdugi_batch_runner_credential WHERE credential_id = ?", Long.class, result.getCredentialId());
        assertThat(seconds).isBetween(590L, 600L);
        assertThat(Instant.parse(result.getExpiresAt())).isAfter(Instant.now());
    }

    @Test
    void issueRecordsAnEventWithoutTheSecret() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-2", "REGISTERING");

        BatchEnrollmentResult result = service.issue(runnerId, ACTOR);

        Map<String, Object> event = jdbc.queryForMap("SELECT event_type, actor_type, actor_id, detail_data::text AS detail "
                + "FROM kkdugi_batch_event WHERE target_id = ?", runnerId);
        assertThat(event.get("event_type")).isEqualTo("ENROLLMENT_ISSUED");
        assertThat(event.get("actor_type")).isEqualTo("USER");
        assertThat(event.get("actor_id")).isEqualTo(ACTOR);
        assertThat(event.get("detail").toString()).contains(result.getCredentialId());
        assertThat(event.get("detail").toString()).doesNotContain(tokens.parse(result.getEnrollmentToken()).getSecret());
    }

    @Test
    void issuingAgainRevokesThePreviousUnusedToken() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-3", "REGISTERING");
        BatchEnrollmentResult first = service.issue(runnerId, ACTOR);

        BatchEnrollmentResult second = service.issue(runnerId, ACTOR);

        assertThat(jdbc.queryForObject("SELECT revoked_dtm IS NOT NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, first.getCredentialId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT revoked_dtm IS NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, second.getCredentialId())).isTrue();
    }

    @Test
    void issueIsRejectedForActiveOrPausedRunnersAndUnknownIds() {
        String active = BatchTestData.insertRunner(jdbc, "tb-enr-4", "ACTIVE");
        String paused = BatchTestData.insertRunner(jdbc, "tb-enr-5", "PAUSED");

        assertError(() -> service.issue(active, ACTOR), 409, BatchErrors.STATE_CONFLICT);
        assertError(() -> service.issue(paused, ACTOR), 409, BatchErrors.STATE_CONFLICT);
        assertError(() -> service.issue("BRNOSUCH", ACTOR), 404, BatchErrors.RUNNER_NOT_FOUND);
    }

    @Test
    void issueForARevokedRunnerReturnsItToRegisteringAndKeepsTheSessionGeneration() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-6", "REVOKED");
        jdbc.update("UPDATE kkdugi_batch_runner SET session_ver = 3 WHERE runner_id = ?", runnerId);

        service.issue(runnerId, ACTOR);

        Map<String, Object> runner = jdbc.queryForMap(
                "SELECT runner_stat, session_ver, config_ver FROM kkdugi_batch_runner WHERE runner_id = ?", runnerId);
        assertThat(runner.get("runner_stat")).isEqualTo("REGISTERING");
        assertThat(((Number) runner.get("session_ver")).longValue()).isEqualTo(3);
        assertThat(((Number) runner.get("config_ver")).longValue()).isEqualTo(2);
    }

    @Test
    void revokeInvalidatesEveryCredentialAndRecordsTheReason() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-7", "ACTIVE");
        insertAccessCredential(runnerId);
        jdbc.update("INSERT INTO kkdugi_batch_runner_credential (credential_id, runner_id, credential_type, "
                + "secret_hash, expires_dtm, reg_id) VALUES (?, ?, 'ENROLLMENT', repeat('c', 64), "
                + "now() + interval '10 minute', ?)", BatchIds.next(BatchIds.CREDENTIAL), runnerId, ACTOR);

        BatchRunner revoked = service.revoke(runnerId, "machine retired", ACTOR);

        assertThat(revoked.getStatus()).isEqualTo(RunnerStatus.REVOKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND revoked_dtm IS NULL", Integer.class, runnerId)).isZero();
        assertThat(jdbc.queryForObject("SELECT detail_data ->> 'reason' FROM kkdugi_batch_event "
                + "WHERE target_id = ? AND event_type = 'REVOKED'", String.class, runnerId)).isEqualTo("machine retired");
    }

    @Test
    void revokingAnAlreadyRevokedRunnerIsANoOp() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-8", "ACTIVE");
        service.revoke(runnerId, "first", ACTOR);

        BatchRunner again = service.revoke(runnerId, "second", ACTOR);

        assertThat(again.getStatus()).isEqualTo(RunnerStatus.REVOKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                + "AND event_type = 'REVOKED'", Integer.class, runnerId)).isEqualTo(1);
    }

    @Test
    void revokeValidatesTheReasonAndTheRunner() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-9", "ACTIVE");

        assertError(() -> service.revoke(runnerId, null, ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.revoke(runnerId, "  ", ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.revoke(runnerId, "x".repeat(4001), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.revoke("BRNOSUCH", "reason", ACTOR), 404, BatchErrors.RUNNER_NOT_FOUND);
    }
}
