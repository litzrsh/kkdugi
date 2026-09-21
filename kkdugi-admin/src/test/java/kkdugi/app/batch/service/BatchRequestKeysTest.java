package kkdugi.app.batch.service;

import static kkdugi.support.BatchTestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.IdempotencySubjectType;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.models.BatchIdempotencyKey;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchRequestKeysTest {

    private static final String CREATED_AT = "2026-09-20T02:00:00.123456Z";

    @Autowired
    private BatchRequestKeys keys;

    private BatchIdempotencyKey key(Object body, String ifMatch) {
        return keys.forUser("U1", "PUT", "/api/v1.0/admin/batch/runners/BR1", "key-1", CREATED_AT, body, ifMatch);
    }

    @Test
    void buildsTheKeyFromTheRequest() {
        BatchIdempotencyKey key = key(Map.of("a", 1), "\"3\"");

        assertThat(key.getSubjectType()).isEqualTo(IdempotencySubjectType.USER);
        assertThat(key.getSubjectId()).isEqualTo("U1");
        assertThat(key.getRequestKey()).isEqualTo("key-1");
        assertThat(key.getCreatedAt()).isEqualTo(Instant.parse(CREATED_AT));
        assertThat(key.getOperationHash()).isEqualTo(BatchTokenService.sha256Hex("PUT /api/v1.0/admin/batch/runners/BR1"));
        assertThat(key.getRequestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void requestHashIgnoresJsonKeyOrderButNotValues() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "n");
        first.put("nested", new LinkedHashMap<>(Map.of("x", 1, "y", 2)));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("nested", new LinkedHashMap<>(Map.of("y", 2, "x", 1)));
        reordered.put("name", "n");
        Map<String, Object> different = new LinkedHashMap<>(first);
        different.put("name", "other");

        assertThat(key(first, null).getRequestHash()).isEqualTo(key(reordered, null).getRequestHash());
        assertThat(key(first, null).getRequestHash()).isNotEqualTo(key(different, null).getRequestHash());
    }

    @Test
    void ifMatchAndCreatedAtParticipateInTheHash() {
        assertThat(key(Map.of("a", 1), "\"1\"").getRequestHash()).isNotEqualTo(key(Map.of("a", 1), "\"2\"").getRequestHash());
        assertThat(key(Map.of("a", 1), null).getRequestHash()).isNotEqualTo(key(Map.of("a", 1), "\"1\"").getRequestHash());
        assertThat(keys.forUser("U1", "PUT", "/p", "k", "2026-09-20T02:00:01Z", null, null).getRequestHash())
                .isNotEqualTo(keys.forUser("U1", "PUT", "/p", "k", "2026-09-20T02:00:02Z", null, null).getRequestHash());
    }

    @Test
    void nullBodiesAreAllowedForBodylessCommands() {
        assertThat(keys.forUser("U1", "DELETE", "/p", "k", CREATED_AT, null, null).getRequestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsInvalidHeaders() {
        assertError(() -> keys.forUser("U1", "POST", "/p", null, CREATED_AT, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "", CREATED_AT, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "has space", CREATED_AT, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k".repeat(201), CREATED_AT, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k", null, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k", "2026-09-20T02:00:00+09:00", null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k", "2026-09-20T02:00:00.1234567Z", null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k", "2026-13-40T02:00:00Z", null, null), 400, BatchErrors.REQUEST_INVALID);
    }
}
