package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchEventServiceTest {

    @Autowired
    private BatchEventService events;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    @Test
    void recordsStateTransitionWithDetail() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-evt-1", "REGISTERING");

        events.record(EventTargetType.RUNNER, runnerId, EventType.UPDATED, "REGISTERING", "ACTIVE",
                ActorType.USER, BatchTestData.USER, Map.of("reason", "test", "capacity", 3));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM kkdugi_batch_event WHERE target_id = ?", runnerId);
        assertThat(row.get("event_id").toString()).startsWith("BE").hasSize(18);
        assertThat(row.get("target_type")).isEqualTo("RUNNER");
        assertThat(row.get("event_type")).isEqualTo("UPDATED");
        assertThat(row.get("from_stat")).isEqualTo("REGISTERING");
        assertThat(row.get("to_stat")).isEqualTo("ACTIVE");
        assertThat(row.get("actor_type")).isEqualTo("USER");
        assertThat(row.get("actor_id")).isEqualTo(BatchTestData.USER);
        assertThat(row.get("occurred_dtm")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT detail_data ->> 'reason' FROM kkdugi_batch_event WHERE target_id = ?",
                String.class, runnerId)).isEqualTo("test");
    }

    @Test
    void allowsEventsWithoutStatesOrDetail() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-evt-2", "ACTIVE");

        events.record(EventTargetType.RUNNER, runnerId, EventType.SESSION_OPENED, null, null,
                ActorType.RUNNER, runnerId, null);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM kkdugi_batch_event WHERE target_id = ?", runnerId);
        assertThat(row.get("from_stat")).isNull();
        assertThat(row.get("detail_data")).isNull();
    }
}
