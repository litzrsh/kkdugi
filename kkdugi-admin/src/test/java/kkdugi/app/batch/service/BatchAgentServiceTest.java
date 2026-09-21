package kkdugi.app.batch.service;

import static kkdugi.support.BatchTestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.models.BatchHeartbeatItem;
import kkdugi.app.batch.models.BatchHeartbeatRequest;
import kkdugi.app.batch.models.BatchHeartbeatResponse;
import kkdugi.app.batch.models.BatchRegistrationRequest;
import kkdugi.app.batch.models.BatchRegistrationResponse;
import kkdugi.app.batch.models.BatchSessionRequest;
import kkdugi.app.batch.models.BatchSessionResponse;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchAgentServiceTest {

    private static final String ACTOR = BatchTestData.USER;

    @Autowired
    private BatchAgentService agent;

    @Autowired
    private BatchEnrollmentService enrollment;

    @Autowired
    private BatchTokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    /** REGISTERING runner와 미사용 등록 토큰을 준비하고 {runnerId, enrollmentCredentialId, token}을 돌려준다. */
    private String[] registrable(String code) {
        String runnerId = BatchTestData.insertRunner(jdbc, code, "REGISTERING");
        String token = enrollment.issue(runnerId, ACTOR).getEnrollmentToken();
        return new String[] { runnerId, tokens.parse(token).getCredentialId(), token };
    }

    private static BatchRegistrationRequest registration(String code) {
        BatchRegistrationRequest request = new BatchRegistrationRequest();
        request.setRunnerCode(code);
        request.setAgentVersion("0.1.0");
        request.setHostname("host-1");
        request.setOs("LINUX");
        request.setArchitecture("AMD64");
        return request;
    }

    /** 등록까지 마친 runner의 {runnerId, accessCredentialId}. */
    private String[] registered(String code) {
        String[] ids = registrable(code);
        BatchRegistrationResponse response = agent.register(ids[0], ids[1], registration(code));
        return new String[] { ids[0], response.getCredentialId() };
    }

    private static BatchSessionRequest session(String bootId, String expected) {
        BatchSessionRequest request = new BatchSessionRequest();
        request.setBootId(bootId);
        request.setExpectedSession(expected);
        request.setAgentVersion("0.1.0");
        return request;
    }

    private static BatchHeartbeatRequest heartbeat(int freeSlots, String... assignmentIds) {
        BatchHeartbeatRequest request = new BatchHeartbeatRequest();
        request.setObservedAt("2026-09-20T02:00:00Z");
        request.setMode("ACCEPTING");
        request.setFreeSlots(freeSlots);
        List<BatchHeartbeatItem> items = new ArrayList<>();
        for (String id : assignmentIds) {
            BatchHeartbeatItem item = new BatchHeartbeatItem();
            item.setId(id);
            item.setPhase("RUNNING");
            items.add(item);
        }
        request.setAssignments(items);
        return request;
    }

    private Map<String, Object> runnerRow(String runnerId) {
        return jdbc.queryForMap("SELECT runner_stat, host_nm, os_cd, agent_ver, session_ver, config_ver, boot_ref, "
                + "last_seen_dtm IS NOT NULL AS seen FROM kkdugi_batch_runner WHERE runner_id = ?", runnerId);
    }

    // ---- 등록 ----------------------------------------------------------

    @Test
    void registrationActivatesTheRunnerAndIssuesAnAccessToken() {
        String[] ids = registrable("tb-agt-1");

        BatchRegistrationResponse response = agent.register(ids[0], ids[1], registration("tb-agt-1"));

        assertThat(response.getRunnerId()).isEqualTo(ids[0]);
        assertThat(response.getSession()).isEqualTo("0");
        assertThat(Instant.parse(response.getTokenExpiresAt())).isAfter(Instant.now().plusSeconds(29L * 24 * 3600));
        BatchTokenService.ParsedToken access = tokens.parse(response.getAccessToken());
        assertThat(access).isNotNull();
        assertThat(access.getCredentialId()).isEqualTo(response.getCredentialId());
        assertThat(jdbc.queryForObject("SELECT secret_hash FROM kkdugi_batch_runner_credential WHERE credential_id = ?",
                String.class, response.getCredentialId())).isEqualTo(tokens.hash(access.getSecret()));
        Map<String, Object> runner = runnerRow(ids[0]);
        assertThat(runner.get("runner_stat")).isEqualTo("ACTIVE");
        assertThat(runner.get("host_nm")).isEqualTo("host-1");
        assertThat(runner.get("os_cd")).isEqualTo("LINUX");
        assertThat(runner.get("agent_ver")).isEqualTo("0.1.0");
        assertThat(((Number) runner.get("config_ver")).longValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT consumed_dtm IS NOT NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, ids[1])).isTrue();
    }

    @Test
    void registrationEventCarriesCredentialIdsAndArchitectureButNoToken() {
        String[] ids = registrable("tb-agt-2");

        BatchRegistrationResponse response = agent.register(ids[0], ids[1], registration("tb-agt-2"));

        Map<String, Object> event = jdbc.queryForMap("SELECT actor_type, actor_id, from_stat, to_stat, "
                + "detail_data::text AS detail FROM kkdugi_batch_event WHERE target_id = ? AND event_type = 'REGISTERED'",
                ids[0]);
        assertThat(event.get("actor_type")).isEqualTo("RUNNER");
        assertThat(event.get("actor_id")).isEqualTo(ids[0]);
        assertThat(event.get("from_stat")).isEqualTo("REGISTERING");
        assertThat(event.get("to_stat")).isEqualTo("ACTIVE");
        String detail = event.get("detail").toString();
        assertThat(detail).contains(response.getCredentialId()).contains("AMD64");
        assertThat(detail).doesNotContain(tokens.parse(response.getAccessToken()).getSecret());
    }

    @Test
    void aMismatchedRunnerCodeIsForbiddenAndDoesNotBurnTheToken() {
        String[] ids = registrable("tb-agt-3");

        assertError(() -> agent.register(ids[0], ids[1], registration("tb-other")), 403, BatchErrors.RUNNER_FORBIDDEN);

        assertThat(jdbc.queryForObject("SELECT consumed_dtm IS NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, ids[1])).isTrue();
        assertThat(runnerRow(ids[0]).get("runner_stat")).isEqualTo("REGISTERING");
    }

    @Test
    void anUnsupportedPlatformIsRejectedWithoutBurningTheToken() {
        String[] ids = registrable("tb-agt-4");
        BatchRegistrationRequest request = registration("tb-agt-4");
        request.setOs("SOLARIS");

        assertError(() -> agent.register(ids[0], ids[1], request), 409, BatchErrors.PLATFORM_UNSUPPORTED);

        assertThat(jdbc.queryForObject("SELECT consumed_dtm IS NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, ids[1])).isTrue();
    }

    @Test
    void registrationValidatesRequiredFields() {
        String[] ids = registrable("tb-agt-5");
        BatchRegistrationRequest missingHost = registration("tb-agt-5");
        missingHost.setHostname(" ");
        BatchRegistrationRequest longVersion = registration("tb-agt-5");
        longVersion.setAgentVersion("v".repeat(51));

        assertError(() -> agent.register(ids[0], ids[1], missingHost), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.register(ids[0], ids[1], longVersion), 400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void theTokenCannotBeUsedTwiceOrOnANonRegisteringRunner() {
        String[] ids = registrable("tb-agt-6");
        agent.register(ids[0], ids[1], registration("tb-agt-6"));

        assertError(() -> agent.register(ids[0], ids[1], registration("tb-agt-6")), 409, BatchErrors.STATE_CONFLICT);
    }

    @Test
    void anExpiredEnrollmentTokenIsRejected() {
        String[] ids = registrable("tb-agt-7");
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET expires_dtm = now() - interval '1 second' "
                + "WHERE credential_id = ?", ids[1]);

        assertError(() -> agent.register(ids[0], ids[1], registration("tb-agt-7")), 401, BatchErrors.CREDENTIAL_INVALID);
        assertThat(runnerRow(ids[0]).get("runner_stat")).isEqualTo("REGISTERING");
    }

    // ---- 세션 ----------------------------------------------------------

    @Test
    void openingTheFirstSessionReturnsGenerationOneWithTheServerSettings() {
        String[] ids = registered("tb-agt-8");
        String bootId = UUID.randomUUID().toString();

        BatchSessionResponse response = agent.openSession(ids[0], ids[1], session(bootId, "0"));

        assertThat(response.getRunnerId()).isEqualTo(ids[0]);
        assertThat(response.getSession()).isEqualTo("1");
        assertThat(Instant.parse(response.getServerTime())).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(response.getHeartbeatSeconds()).isEqualTo(10);
        assertThat(response.getPollSeconds()).isEqualTo(3);
        assertThat(response.getLeaseSeconds()).isEqualTo(60);
        assertThat(response.getCapacity()).isEqualTo(1);
        assertThat(response.getLimits().getJsonBytes()).isEqualTo(1_048_576);
        assertThat(response.getLimits().getInputBytes()).isEqualTo(262_144);
        assertThat(response.getLimits().getResultBytes()).isEqualTo(262_144);
        assertThat(response.getLimits().getLogChunkBytes()).isEqualTo(32_768);
        Map<String, Object> runner = runnerRow(ids[0]);
        assertThat(runner.get("boot_ref")).isEqualTo(bootId);
        assertThat(((Number) runner.get("config_ver")).longValue()).isEqualTo(2); // 세션 개설은 버전을 올리지 않는다
    }

    @Test
    void resendingTheSameBootIdReturnsTheSameGenerationWithoutAnotherEvent() {
        String[] ids = registered("tb-agt-9");
        String bootId = UUID.randomUUID().toString();
        agent.openSession(ids[0], ids[1], session(bootId, "0"));

        BatchSessionResponse again = agent.openSession(ids[0], ids[1], session(bootId, "0"));

        assertThat(again.getSession()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                + "AND event_type = 'SESSION_OPENED'", Integer.class, ids[0])).isEqualTo(1);
    }

    @Test
    void aNewBootIdMustPresentTheCurrentGeneration() {
        String[] ids = registered("tb-agt-10");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));

        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0")),
                409, BatchErrors.SESSION_STALE);
        BatchSessionResponse next = agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "1"));

        assertThat(next.getSession()).isEqualTo("2");
    }

    @Test
    void sessionRequestValidation() {
        String[] ids = registered("tb-agt-11");

        assertError(() -> agent.openSession(ids[0], ids[1], session("not-a-uuid", "0")), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "-1")), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "01")), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "9999999999999999999")),
                400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void aRevokedCredentialCannotOpenASession() {
        String[] ids = registered("tb-agt-12");
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET revoked_dtm = now() WHERE credential_id = ?", ids[1]);

        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0")),
                401, BatchErrors.CREDENTIAL_INVALID);
    }

    // ---- heartbeat -----------------------------------------------------

    @Test
    void heartbeatRecordsLastSeenWithoutTouchingConfigOrStatus() {
        String[] ids = registered("tb-agt-13");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        assertThat(runnerRow(ids[0]).get("seen")).isEqualTo(false);

        BatchHeartbeatResponse response = agent.heartbeat(ids[0], "1", heartbeat(1));

        assertThat(response.isAcceptingAssignments()).isTrue();
        assertThat(response.getAssignments()).isEmpty();
        assertThat(Instant.parse(response.getServerTime())).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        Map<String, Object> runner = runnerRow(ids[0]);
        assertThat(runner.get("seen")).isEqualTo(true);
        assertThat(runner.get("runner_stat")).isEqualTo("ACTIVE");
        assertThat(((Number) runner.get("config_ver")).longValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ?", Integer.class, ids[0]))
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                        + "AND event_type IN ('ENROLLMENT_ISSUED', 'REGISTERED', 'SESSION_OPENED')", Integer.class, ids[0]));
    }

    @Test
    void aPausedRunnerStopsAcceptingAssignments() {
        String[] ids = registered("tb-agt-14");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'PAUSED' WHERE runner_id = ?", ids[0]);

        assertThat(agent.heartbeat(ids[0], "1", heartbeat(1)).isAcceptingAssignments()).isFalse();
    }

    @Test
    void unknownAssignmentsAreToldToReconcile() {
        String[] ids = registered("tb-agt-15");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));

        BatchHeartbeatResponse response = agent.heartbeat(ids[0], "1", heartbeat(0, "BA0000000000000001"));

        assertThat(response.getAssignments()).hasSize(1);
        assertThat(response.getAssignments().get(0).getId()).isEqualTo("BA0000000000000001");
        assertThat(response.getAssignments().get(0).getAction()).isEqualTo("RECONCILE");
        assertThat(response.getAssignments().get(0).getLeaseUntil()).isNull();
    }

    @Test
    void heartbeatRequiresTheCurrentSession() {
        String[] ids = registered("tb-agt-16");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));

        assertError(() -> agent.heartbeat(ids[0], "0", heartbeat(1)), 409, BatchErrors.SESSION_STALE);
        assertError(() -> agent.heartbeat(ids[0], "2", heartbeat(1)), 409, BatchErrors.SESSION_STALE);
        assertError(() -> agent.heartbeat(ids[0], null, heartbeat(1)), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "abc", heartbeat(1)), 400, BatchErrors.REQUEST_INVALID);
        assertThat(runnerRow(ids[0]).get("seen")).isEqualTo(false);
    }

    @Test
    void invalidHeartbeatBodiesAreRejectedAndRollBackTheTouch() {
        String[] ids = registered("tb-agt-17");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        BatchHeartbeatRequest tooManySlots = heartbeat(2); // capacity 1
        BatchHeartbeatRequest badMode = heartbeat(1);
        badMode.setMode("SLEEPING");
        BatchHeartbeatRequest badTime = heartbeat(1);
        badTime.setObservedAt("yesterday");
        BatchHeartbeatRequest noAssignments = heartbeat(1);
        noAssignments.setAssignments(null);
        BatchHeartbeatRequest badPhase = heartbeat(1, "BA1");
        badPhase.getAssignments().get(0).setPhase("DANCING");
        BatchHeartbeatRequest missingPhase = heartbeat(1, "BA1");
        missingPhase.getAssignments().get(0).setPhase(null);
        BatchHeartbeatRequest nullItem = heartbeat(1);
        nullItem.getAssignments().add(null);

        assertError(() -> agent.heartbeat(ids[0], "1", tooManySlots), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", badMode), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", badTime), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", noAssignments), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", badPhase), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", missingPhase), 400, BatchErrors.REQUEST_INVALID); // NPE가 아니라 400
        assertError(() -> agent.heartbeat(ids[0], "1", nullItem), 400, BatchErrors.REQUEST_INVALID);
        assertThat(runnerRow(ids[0]).get("seen")).isEqualTo(false);
    }

    @Test
    void tooManyHeartbeatItemsAreRejectedAsTooLarge() {
        String[] ids = registered("tb-agt-18");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        String[] many = new String[201];
        for (int i = 0; i < many.length; i++) {
            many[i] = "BA" + i;
        }

        assertError(() -> agent.heartbeat(ids[0], "1", heartbeat(1, many)), 413, BatchErrors.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aRevokedRunnerCannotHeartbeat() {
        String[] ids = registered("tb-agt-19");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'REVOKED' WHERE runner_id = ?", ids[0]);

        assertError(() -> agent.heartbeat(ids[0], "1", heartbeat(1)), 401, BatchErrors.CREDENTIAL_INVALID);
    }
}
