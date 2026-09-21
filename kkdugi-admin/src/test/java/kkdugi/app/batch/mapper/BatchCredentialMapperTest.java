package kkdugi.app.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.CredentialType;
import kkdugi.app.batch.models.BatchRunnerCredential;
import kkdugi.app.batch.service.BatchIds;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchCredentialMapperTest {

    @Autowired
    private BatchCredentialMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String runnerId;

    @BeforeEach
    void setUp() {
        BatchTestData.wipe(jdbc);
        runnerId = BatchTestData.insertRunner(jdbc, "tb-cred-1", "REGISTERING");
    }

    @AfterEach
    void cleanUp() {
        BatchTestData.wipe(jdbc);
    }

    private BatchRunnerCredential insert(CredentialType type, long ttlSeconds) {
        BatchRunnerCredential row = new BatchRunnerCredential();
        row.setId(BatchIds.next(BatchIds.CREDENTIAL));
        row.setRunnerId(runnerId);
        row.setType(type);
        row.setSecretHash("a".repeat(64));
        row.setCreatorId(BatchTestData.USER);
        assertThat(mapper.insert(row, ttlSeconds)).isEqualTo(1);
        return row;
    }

    @Test
    void freshCredentialIsValidAndExposesItsFields() {
        BatchRunnerCredential row = insert(CredentialType.ENROLLMENT, 600);

        BatchRunnerCredential found = mapper.findForAuth(row.getId()).orElseThrow();

        assertThat(found.isValid()).isTrue();
        assertThat(found.getRunnerId()).isEqualTo(runnerId);
        assertThat(found.getType()).isEqualTo(CredentialType.ENROLLMENT);
        assertThat(found.getSecretHash()).isEqualTo("a".repeat(64));
        assertThat(found.getExpiresAt()).isAfter(java.time.Instant.now());
        assertThat(found.getConsumedAt()).isNull();
        assertThat(mapper.findForAuth("BCNOSUCH")).isEmpty();
    }

    @Test
    void expiredCredentialIsInvalidAndCannotBeConsumed() {
        BatchRunnerCredential row = insert(CredentialType.ENROLLMENT, -1);

        assertThat(mapper.findForAuth(row.getId()).orElseThrow().isValid()).isFalse();
        assertThat(mapper.consumeEnrollment(row.getId())).isZero();
    }

    @Test
    void enrollmentTokenIsConsumedExactlyOnce() {
        BatchRunnerCredential row = insert(CredentialType.ENROLLMENT, 600);

        assertThat(mapper.consumeEnrollment(row.getId())).isEqualTo(1);
        assertThat(mapper.consumeEnrollment(row.getId())).isZero();
        assertThat(mapper.findForAuth(row.getId()).orElseThrow().isValid()).isFalse();
    }

    @Test
    void accessCredentialCannotBeConsumedAsEnrollment() {
        BatchRunnerCredential row = insert(CredentialType.ACCESS, 600);

        assertThat(mapper.consumeEnrollment(row.getId())).isZero();
        assertThat(mapper.findForAuth(row.getId()).orElseThrow().isValid()).isTrue();
    }

    @Test
    void revokeUnusedEnrollmentsSkipsConsumedAndAccessCredentials() {
        BatchRunnerCredential unused = insert(CredentialType.ENROLLMENT, 600);
        BatchRunnerCredential consumed = insert(CredentialType.ENROLLMENT, 600);
        mapper.consumeEnrollment(consumed.getId());
        BatchRunnerCredential access = insert(CredentialType.ACCESS, 600);

        assertThat(mapper.revokeUnusedEnrollments(runnerId)).isEqualTo(1);

        assertThat(mapper.findForAuth(unused.getId()).orElseThrow().isValid()).isFalse();
        assertThat(mapper.findForAuth(access.getId()).orElseThrow().isValid()).isTrue();
    }

    @Test
    void revokeAllInvalidatesEveryOpenCredential() {
        BatchRunnerCredential enrollment = insert(CredentialType.ENROLLMENT, 600);
        BatchRunnerCredential access = insert(CredentialType.ACCESS, 600);

        assertThat(mapper.revokeAll(runnerId)).isEqualTo(2);
        assertThat(mapper.revokeAll(runnerId)).isZero();

        assertThat(mapper.findForAuth(enrollment.getId()).orElseThrow().isValid()).isFalse();
        assertThat(mapper.findForAuth(access.getId()).orElseThrow().isValid()).isFalse();
    }

    @Test
    void lastUsedIsThrottled() {
        BatchRunnerCredential row = insert(CredentialType.ACCESS, 600);

        assertThat(mapper.touchLastUsed(row.getId(), 60)).isEqualTo(1);
        assertThat(mapper.touchLastUsed(row.getId(), 60)).isZero();
        assertThat(mapper.findForAuth(row.getId()).orElseThrow().getLastUsedAt()).isNotNull();
    }

    @Test
    void deleteByRunnerIdRemovesCredentials() {
        insert(CredentialType.ACCESS, 600);

        assertThat(mapper.deleteByRunnerId(runnerId)).isEqualTo(1);
    }
}
