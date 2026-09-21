package kkdugi.app.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsTheFourS1Tables() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name IN "
                + "('kkdugi_batch_runner','kkdugi_batch_runner_credential','kkdugi_batch_event','kkdugi_batch_api_request')",
                Integer.class);
        assertThat(count).isEqualTo(4);
    }

    @Test
    void runnerStatusIsConstrained() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO kkdugi_batch_runner "
                + "(runner_id, runner_cd, runner_nm, runner_stat, capacity_cnt, reg_id) "
                + "VALUES ('TSCHEMA0000000001', 'tb-schema-1', 'x', 'BOGUS', 1, 'T')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runnerCapacityMustBeBetween1And200() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO kkdugi_batch_runner "
                + "(runner_id, runner_cd, runner_nm, runner_stat, capacity_cnt, reg_id) "
                + "VALUES ('TSCHEMA0000000002', 'tb-schema-2', 'x', 'ACTIVE', 201, 'T')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void apiRequestOperationHashMustBeSha256Hex() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO kkdugi_batch_api_request "
                + "(request_id, subject_type, subject_id, operation_hash, request_key, request_hash, request_dtm, "
                + "http_status, expires_dtm, reg_id) "
                + "VALUES ('TSCHEMA0000000003', 'USER', 'U', 'short', 'k', 'h', now(), 200, now(), 'T')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void apiRequestHttpStatusMustBeSuccess() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO kkdugi_batch_api_request "
                + "(request_id, subject_type, subject_id, operation_hash, request_key, request_hash, request_dtm, "
                + "http_status, expires_dtm, reg_id) "
                + "VALUES ('TSCHEMA0000000004', 'USER', 'U', repeat('a', 64), 'k', 'h', now(), 500, now(), 'T')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
