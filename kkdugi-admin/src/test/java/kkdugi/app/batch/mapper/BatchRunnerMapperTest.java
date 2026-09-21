package kkdugi.app.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.models.BatchHeartbeatState;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.service.BatchIds;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchRunnerMapperTest {

    private static final int ONLINE_SECONDS = 30;

    @Autowired
    private BatchRunnerMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    private BatchRunner insert(String code) {
        BatchRunner row = new BatchRunner();
        row.setId(BatchIds.next(BatchIds.RUNNER));
        row.setCode(code);
        row.setName("Runner " + code);
        row.setStatus(RunnerStatus.REGISTERING);
        row.setCapacity(2);
        row.setCreatorId(BatchTestData.USER);
        assertThat(mapper.insert(row)).isEqualTo(1);
        return row;
    }

    @Test
    void insertsAndFindsWithDefaults() {
        BatchRunner row = insert("tb-map-1");

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();

        assertThat(found.getCode()).isEqualTo("tb-map-1");
        assertThat(found.getStatus()).isEqualTo(RunnerStatus.REGISTERING);
        assertThat(found.getCapacity()).isEqualTo(2);
        assertThat(found.getSessionVer()).isZero();
        assertThat(found.getConfigVer()).isEqualTo(1);
        assertThat(found.getLastSeenAt()).isNull();
        assertThat(found.isOnline()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getCreatorId()).isEqualTo(BatchTestData.USER);
        assertThat(mapper.countByCode("tb-map-1")).isEqualTo(1);
        assertThat(mapper.lockById(row.getId())).isPresent();
    }

    @Test
    void updateIsGuardedByVersion() {
        BatchRunner row = insert("tb-map-2");

        assertThat(mapper.update(row.getId(), "Renamed", 5, RunnerStatus.REGISTERING, 1, BatchTestData.USER)).isEqualTo(1);
        assertThat(mapper.update(row.getId(), "Again", 5, RunnerStatus.REGISTERING, 1, BatchTestData.USER)).isZero();

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();
        assertThat(found.getName()).isEqualTo("Renamed");
        assertThat(found.getCapacity()).isEqualTo(5);
        assertThat(found.getConfigVer()).isEqualTo(2);
        assertThat(found.getUpdaterId()).isEqualTo(BatchTestData.USER);
    }

    @Test
    void searchPagesAndCountsWithWindowTotal() {
        insert("tb-map-a");
        insert("tb-map-b");
        insert("tb-map-c");

        List<BatchRunner> firstPage = mapper.search("tb-map-", null, null, 0, 2, ONLINE_SECONDS);
        List<BatchRunner> filtered = mapper.search("tb-map-", null, "ACTIVE", 0, 10, ONLINE_SECONDS);

        assertThat(firstPage).hasSize(2);
        assertThat(firstPage.get(0).getTotalSize()).isEqualTo(3);
        assertThat(filtered).isEmpty();
    }

    @Test
    void changeStatusAndRegistrationBumpTheVersion() {
        BatchRunner row = insert("tb-map-3");

        assertThat(mapper.markRegistered(row.getId(), "host-1", "LINUX", "0.1.0", row.getId())).isEqualTo(1);

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(found.getHostname()).isEqualTo("host-1");
        assertThat(found.getOs()).isEqualTo("LINUX");
        assertThat(found.getAgentVersion()).isEqualTo("0.1.0");
        assertThat(found.getConfigVer()).isEqualTo(2);

        assertThat(mapper.changeStatus(row.getId(), RunnerStatus.PAUSED, BatchTestData.USER)).isEqualTo(1);
        assertThat(mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow().getConfigVer()).isEqualTo(3);
    }

    @Test
    void openSessionAdvancesGenerationWithoutTouchingConfigVersion() {
        BatchRunner row = insert("tb-map-4");

        assertThat(mapper.openSession(row.getId(), "boot-1", "0.1.0")).isEqualTo(1);

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();
        assertThat(found.getSessionVer()).isEqualTo(1);
        assertThat(found.getBootRef()).isEqualTo("boot-1");
        assertThat(found.getConfigVer()).isEqualTo(1);
    }

    @Test
    void touchOnlyAppliesToActiveOrPausedRunnersInTheCurrentSession() {
        BatchRunner row = insert("tb-map-5");
        assertThat(mapper.touch(row.getId(), 0)).isEmpty(); // REGISTERING

        mapper.changeStatus(row.getId(), RunnerStatus.ACTIVE, BatchTestData.USER);
        BatchHeartbeatState state = mapper.touch(row.getId(), 0).orElseThrow();
        assertThat(state.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(state.getCapacity()).isEqualTo(2);
        assertThat(mapper.touch(row.getId(), 7)).isEmpty(); // 다른 세대

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();
        assertThat(found.getLastSeenAt()).isNotNull();
        assertThat(found.isOnline()).isTrue();
        assertThat(found.getConfigVer()).isEqualTo(2); // touch는 버전을 올리지 않는다
    }

    @Test
    void nowReturnsAnInstantCloseToTheClock() {
        Instant now = mapper.now();
        assertThat(now).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    void deleteRemovesTheRow() {
        BatchRunner row = insert("tb-map-6");
        assertThat(mapper.deleteById(row.getId())).isEqualTo(1);
        assertThat(mapper.findById(row.getId(), ONLINE_SECONDS)).isEmpty();
    }
}
