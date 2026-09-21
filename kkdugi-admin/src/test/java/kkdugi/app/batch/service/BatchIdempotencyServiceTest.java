package kkdugi.app.batch.service;

import static kkdugi.support.BatchTestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.IdempotencySubjectType;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchIdempotencyKey;
import kkdugi.app.batch.models.BatchIdempotentResponse;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchIdempotencyServiceTest {

    @Autowired
    private BatchIdempotencyService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    private static BatchIdempotencyKey key(String requestKey, String requestHash, Instant createdAt) {
        BatchIdempotencyKey key = new BatchIdempotencyKey();
        key.setSubjectType(IdempotencySubjectType.USER);
        key.setSubjectId(BatchTestData.USER);
        key.setOperationHash(BatchTokenService.sha256Hex("POST /test"));
        key.setRequestKey(requestKey);
        key.setRequestHash(requestHash);
        key.setCreatedAt(createdAt);
        return key;
    }

    private static BatchIdempotentResponse created(String body) {
        return new BatchIdempotentResponse(201, body, "/test/1");
    }

    private int recordCount() {
        return jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_api_request WHERE subject_id = ?",
                Integer.class, BatchTestData.USER);
    }

    @Test
    void firstCallExecutesAndReplayReturnsTheStoredResponseWithoutReexecuting() {
        AtomicInteger calls = new AtomicInteger();
        BatchIdempotencyKey key = key("k-replay", "h1", Instant.now());

        BatchIdempotentResponse first = service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{\"n\":1}");
        });
        BatchIdempotentResponse replay = service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{\"n\":2}");
        });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getLocation()).isEqualTo("/test/1");
        assertThat(replay.getBody()).isEqualToIgnoringWhitespace("{\"n\":1}");
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void bodylessResponsesAreReplayedToo() {
        BatchIdempotencyKey key = key("k-204", "h1", Instant.now());

        service.execute(key, () -> new BatchIdempotentResponse(204, null, null));
        BatchIdempotentResponse replay = service.execute(key, () -> created("{}"));

        assertThat(replay.getStatus()).isEqualTo(204);
        assertThat(replay.getBody()).isNull();
        assertThat(replay.getLocation()).isNull();
    }

    @Test
    void sameKeyWithADifferentRequestConflicts() {
        service.execute(key("k-conflict", "h1", Instant.now()), () -> created("{}"));

        assertError(() -> service.execute(key("k-conflict", "h2", Instant.now()), () -> created("{}")),
                409, BatchErrors.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void aNewKeyWithAStaleCreationTimeIsRejectedAsGone() {
        Instant stale = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant future = Instant.now().plus(10, ChronoUnit.MINUTES);

        assertError(() -> service.execute(key("k-stale", "h1", stale), () -> created("{}")),
                410, BatchErrors.REQUEST_EXPIRED);
        assertError(() -> service.execute(key("k-future", "h1", future), () -> created("{}")),
                410, BatchErrors.REQUEST_EXPIRED);
        assertThat(recordCount()).isZero();
    }

    @Test
    void aFailedCommandLeavesNoRecordSoTheRetryExecutes() {
        BatchIdempotencyKey key = key("k-fail", "h1", Instant.now());
        AtomicInteger calls = new AtomicInteger();

        assertError(() -> service.execute(key, () -> {
            calls.incrementAndGet();
            throw BatchException.conflict(BatchErrors.STATE_CONFLICT);
        }), 409, BatchErrors.STATE_CONFLICT);
        assertThat(recordCount()).isZero();

        BatchIdempotentResponse retry = service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{\"ok\":true}");
        });

        assertThat(calls.get()).isEqualTo(2);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyExecuteOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BatchIdempotencyKey key = key("k-race", "h1", Instant.now());
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BatchIdempotentResponse>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.execute(key, () -> {
                    calls.incrementAndGet();
                    sleep(150); // 다른 요청이 같은 key로 진입할 시간을 준다
                    return created("{\"n\":1}");
                });
            }));
        }
        start.countDown();
        for (Future<BatchIdempotentResponse> result : results) {
            assertThat(result.get().getStatus()).isEqualTo(201);
        }
        pool.shutdown();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void anExpiredRecordIsReplacedByANewExecution() {
        AtomicInteger calls = new AtomicInteger();
        BatchIdempotencyKey key = key("k-expired", "h1", Instant.now());
        service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{}");
        });
        jdbc.update("UPDATE kkdugi_batch_api_request SET expires_dtm = now() - interval '1 second' WHERE subject_id = ?",
                BatchTestData.USER);

        service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{}");
        });

        assertThat(calls.get()).isEqualTo(2);
        assertThat(recordCount()).isEqualTo(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
