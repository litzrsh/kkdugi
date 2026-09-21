package kkdugi.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.service.BatchIds;

/** 배치 테스트가 공유하는 데이터 정리·삽입 도우미. 테스트가 만드는 runner 코드는 모두 {@link #CODE_PREFIX}로 시작한다. */
public final class BatchTestData {

    public static final String CODE_PREFIX = "tb-";
    public static final String USER = "U_TEST_BATCH";

    /** {@code TestAuthorization}이 만드는 세션 사용자. 관리자 API 테스트가 남기는 event/멱등 기록의 actor다. */
    public static final String API_USER = "U_TEST_API";

    private BatchTestData() {
    }

    public static void wipe(JdbcTemplate jdbc) {
        String runnerIds = "SELECT runner_id FROM kkdugi_batch_runner WHERE runner_cd LIKE '" + CODE_PREFIX + "%'";
        jdbc.update("DELETE FROM kkdugi_batch_event WHERE target_id IN (" + runnerIds + ") OR actor_id IN (?, ?)",
                USER, API_USER);
        jdbc.update("DELETE FROM kkdugi_batch_runner_credential WHERE runner_id IN (" + runnerIds + ")");
        jdbc.update("DELETE FROM kkdugi_batch_runner WHERE runner_cd LIKE '" + CODE_PREFIX + "%'");
        jdbc.update("DELETE FROM kkdugi_batch_api_request WHERE subject_id IN (?, ?)", USER, API_USER);
    }

    public static String insertRunner(JdbcTemplate jdbc, String code, String status) {
        String id = BatchIds.next(BatchIds.RUNNER);
        jdbc.update("INSERT INTO kkdugi_batch_runner (runner_id, runner_cd, runner_nm, runner_stat, capacity_cnt, reg_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, code, "Test " + code, status, 1, USER);
        return id;
    }

    public static void assertError(ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BatchException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }
}
