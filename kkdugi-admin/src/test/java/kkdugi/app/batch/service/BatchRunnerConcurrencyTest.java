package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.mapper.BatchRunnerMapper;
import kkdugi.app.batch.models.BatchRegistrationRequest;
import kkdugi.app.batch.models.BatchRegistrationResponse;
import kkdugi.app.batch.models.BatchSessionRequest;
import kkdugi.support.BatchTestData;

/**
 * runner 행 잠금 아래에서 등록·재발급·폐기·세션 개설이 직렬화되는지 검사한다. 토큰 소비 CAS 한 행만으로는
 * runner 상태 전이 전체가 직렬화되지 않는다(admin-plan-review.md).
 */
@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchRunnerConcurrencyTest {

    private static final String ACTOR = BatchTestData.USER;

    @Autowired
    private BatchAgentService agent;

    @Autowired
    private BatchEnrollmentService enrollment;

    @Autowired
    private BatchTokenService tokens;

    @Autowired
    private BatchRunnerMapper runnerMapper;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        BatchTestData.wipe(jdbc);
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        BatchTestData.wipe(jdbc);
    }

    /** {runnerId, enrollmentCredentialId} */
    private String[] registrable(String code) {
        String runnerId = BatchTestData.insertRunner(jdbc, code, "REGISTERING");
        String token = enrollment.issue(runnerId, ACTOR).getEnrollmentToken();
        return new String[] { runnerId, tokens.parse(token).getCredentialId() };
    }

    private static BatchRegistrationRequest registration(String code) {
        BatchRegistrationRequest request = new BatchRegistrationRequest();
        request.setRunnerCode(code);
        request.setAgentVersion("0.1.0");
        request.setHostname("host");
        request.setOs("LINUX");
        request.setArchitecture("AMD64");
        return request;
    }

    private static BatchSessionRequest session(String expected) {
        BatchSessionRequest request = new BatchSessionRequest();
        request.setBootId(UUID.randomUUID().toString());
        request.setExpectedSession(expected);
        request.setAgentVersion("0.1.0");
        return request;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 성공하면 true, 배치 오류(BatchException)면 false. 그 외 예외는 그대로 테스트를 실패시킨다. */
    private static Callable<Boolean> attempt(CountDownLatch start, Runnable action) {
        return () -> {
            start.await();
            try {
                action.run();
                return true;
            } catch (BatchException e) {
                return false;
            }
        };
    }

    private int openCredentials(String runnerId) {
        return jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND revoked_dtm IS NULL", Integer.class, runnerId);
    }

    /** 폐기 트랜잭션이 runner 행 잠금을 잡은 채로 열려 있게 하고, 그 사이 다른 스레드의 작업이 대기하게 만든다. */
    private Future<?> holdLockThenRevoke(String runnerId, CountDownLatch locked) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return pool.submit(() -> tx.executeWithoutResult(status -> {
            runnerMapper.lockById(runnerId);
            locked.countDown();
            pause(500); // 다른 스레드가 같은 행 잠금에서 대기할 시간
            enrollment.revoke(runnerId, "race", ACTOR);
        }));
    }

    /** runner 행 잠금만 잡고 holdMillis 뒤 커밋한다(상태 변경 없음). */
    private Future<?> holdLockOnly(String runnerId, CountDownLatch locked, long holdMillis) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return pool.submit(() -> tx.executeWithoutResult(status -> {
            runnerMapper.lockById(runnerId);
            locked.countDown();
            pause(holdMillis);
        }));
    }

    @Test
    void concurrentRegistrationsWithTheSameTokenSucceedExactlyOnce() throws Exception {
        String[] ids = registrable("tb-race-1");
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(attempt(start, () -> agent.register(ids[0], ids[1], registration("tb-race-1")))));
        }
        start.countDown();

        long successes = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }

        assertThat(successes).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND credential_type = 'ACCESS'", Integer.class, ids[0])).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_id = ?",
                String.class, ids[0])).isEqualTo("ACTIVE");
    }

    @Test
    void aRegistrationQueuedBehindARevokeFailsAndNeverRevivesTheRunner() throws Exception {
        String[] ids = registrable("tb-race-2");
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> revoker = holdLockThenRevoke(ids[0], locked);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> agent.register(ids[0], ids[1], registration("tb-race-2")))
                .isInstanceOf(BatchException.class);
        revoker.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_id = ?",
                String.class, ids[0])).isEqualTo("REVOKED");
        assertThat(openCredentials(ids[0])).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND credential_type = 'ACCESS'", Integer.class, ids[0])).isZero();
    }

    @Test
    void aSessionOpenQueuedBehindARevokeIsRejected() throws Exception {
        String[] ids = registrable("tb-race-3");
        BatchRegistrationResponse registered = agent.register(ids[0], ids[1], registration("tb-race-3"));
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> revoker = holdLockThenRevoke(ids[0], locked);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> agent.openSession(ids[0], registered.getCredentialId(), session("0")))
                .isInstanceOf(BatchException.class);
        revoker.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT session_ver FROM kkdugi_batch_runner WHERE runner_id = ?",
                Long.class, ids[0])).isZero();
        assertThat(openCredentials(ids[0])).isZero();
    }

    @Test
    void concurrentSessionOpensAdvanceTheGenerationExactlyOnce() throws Exception {
        String[] ids = registrable("tb-race-4");
        BatchRegistrationResponse registered = agent.register(ids[0], ids[1], registration("tb-race-4"));
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(attempt(start, () -> agent.openSession(ids[0], registered.getCredentialId(), session("0")))));
        }
        start.countDown();

        long successes = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }

        assertThat(successes).isEqualTo(1); // 서로 다른 bootId는 현재 세대를 아는 하나만 세대를 올린다
        assertThat(jdbc.queryForObject("SELECT session_ver FROM kkdugi_batch_runner WHERE runner_id = ?",
                Long.class, ids[0])).isEqualTo(1);
    }

    @Test
    void registrationRacingAReissueHasExactlyOneWinnerForTheToken() throws Exception {
        for (int round = 0; round < 5; round++) {
            String code = "tb-race-5" + round;
            String[] ids = registrable(code);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> register = pool.submit(attempt(start, () -> agent.register(ids[0], ids[1], registration(code))));
            Future<Boolean> reissue = pool.submit(attempt(start, () -> enrollment.issue(ids[0], ACTOR)));
            start.countDown();

            boolean registered = register.get(30, TimeUnit.SECONDS);
            boolean reissued = reissue.get(30, TimeUnit.SECONDS);

            // 재발급이 먼저면 이전 토큰이 폐기돼 등록이 실패하고, 등록이 먼저면 runner가 ACTIVE라 재발급이 409다.
            assertThat(registered ^ reissued).as("round %d: registered=%s reissued=%s", round, registered, reissued).isTrue();
            String status = jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_id = ?",
                    String.class, ids[0]);
            assertThat(status).isEqualTo(registered ? "ACTIVE" : "REGISTERING");
        }
    }

    /**
     * PostgreSQL의 now()는 트랜잭션 시작 시각이다. 만료 직전에 시작한 트랜잭션이 runner 행 잠금에서 기다리다 만료 뒤에 깨어나면
     * now() 기준으로는 아직 유효해 보인다. 만료 판정이 clock_timestamp()인지 검사한다(검토 문서 4번).
     */
    @Test
    void aRegistrationThatWaitedOnTheLockPastTheTokenExpiryIsRejected() throws Exception {
        String[] ids = registrable("tb-race-6");
        // 토큰은 0.8초 뒤에 만료되고 잠금은 1.5초 동안 잡혀 있다: 등록 트랜잭션은 만료 전에 시작해 만료 뒤에 잠금을 얻는다.
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET expires_dtm = clock_timestamp() + interval '800 milliseconds' "
                + "WHERE credential_id = ?", ids[1]);
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> holder = holdLockOnly(ids[0], locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> agent.register(ids[0], ids[1], registration("tb-race-6")))
                .isInstanceOfSatisfying(BatchException.class, e -> assertThat(e.getStatus()).isEqualTo(401));
        holder.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT consumed_dtm IS NULL FROM kkdugi_batch_runner_credential WHERE credential_id = ?",
                Boolean.class, ids[1])).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND credential_type = 'ACCESS'", Integer.class, ids[0])).isZero();
        assertThat(jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_id = ?",
                String.class, ids[0])).isEqualTo("REGISTERING");
    }

    @Test
    void aSessionOpenThatWaitedOnTheLockPastTheTokenExpiryIsRejected() throws Exception {
        String[] ids = registrable("tb-race-7");
        BatchRegistrationResponse registered = agent.register(ids[0], ids[1], registration("tb-race-7"));
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET expires_dtm = clock_timestamp() + interval '800 milliseconds' "
                + "WHERE credential_id = ?", registered.getCredentialId());
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> holder = holdLockOnly(ids[0], locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> agent.openSession(ids[0], registered.getCredentialId(), session("0")))
                .isInstanceOfSatisfying(BatchException.class, e -> assertThat(e.getStatus()).isEqualTo(401));
        holder.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT session_ver FROM kkdugi_batch_runner WHERE runner_id = ?",
                Long.class, ids[0])).isZero();
    }
}
