package kkdugi.core.security.service;

import java.sql.Timestamp;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.core.exceptions.RestfulAuthenticationException;
import kkdugi.core.security.config.SecurityConfigurationProperties;
import kkdugi.core.security.models.Session;
import kkdugi.core.security.models.SessionUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class SessionServiceTest {

    private static final String USER_ID = "U_TEST_SESSION_1";

    @Autowired
    private SessionService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SecurityConfigurationProperties properties;

    private boolean savedAllowMultiple;

    @BeforeEach
    void saveConfig() {
        savedAllowMultiple = properties.isAllowMultiple();
    }

    @AfterEach
    void cleanUp() {
        properties.setAllowMultiple(savedAllowMultiple);
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id = ?", USER_ID);
    }

    private SessionUser insertUserAndBuild() {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_pwd, user_nm, user_email, "
                        + "user_stat_cd, reg_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                USER_ID, "test_session_login", "encoded-pwd", "Test User", "test-session@example.com", "20",
                "SYSTEM");
        SessionUser user = new SessionUser();
        user.setId(USER_ID);
        user.setUsername("test_session_login");
        return user;
    }

    @Test
    void createSession_thenFindSession_returnsSameSnapshot() {
        SessionUser user = insertUserAndBuild();

        Session created = service.createSession(user, false);
        Session found = service.findSession(created.getId());

        assertThat(found).isNotNull();
        assertThat(found.getUserId()).isEqualTo(USER_ID);
        assertThat(found.getDetails().getUsername()).isEqualTo("test_session_login");
    }

    @Test
    void createSession_secondLoginWithoutForce_throwsDuplicate() {
        SessionUser user = insertUserAndBuild();
        service.createSession(user, false);

        assertThatThrownBy(() -> service.createSession(user, false))
                .isInstanceOf(RestfulAuthenticationException.class)
                .satisfies(ex -> assertThat(((RestfulAuthenticationException) ex).getExceptionMessage().getCode())
                        .isEqualTo(SessionService.ERR_DUPLICATE));
    }

    @Test
    void createSession_secondLoginWithForce_terminatesOldSessionAndSucceeds() {
        SessionUser user = insertUserAndBuild();
        Session first = service.createSession(user, false);

        Session second = service.createSession(user, true);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(service.findSession(first.getId())).isNull();
        assertThat(service.findSession(second.getId())).isNotNull();
    }

    @Test
    void invalidate_removesSession() {
        SessionUser user = insertUserAndBuild();
        Session created = service.createSession(user, false);

        service.invalidate(created.getId());

        assertThat(service.findSession(created.getId())).isNull();
    }

    @Test
    void findSession_unknownId_returnsNull() {
        assertThat(service.findSession("S_NO_SUCH_SESSION")).isNull();
    }

    private long sessionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kkdugi_session WHERE user_id = ?", Long.class, USER_ID);
    }

    @Test
    void extend_afterRefreshInterval_pushesExpiryBack() {
        SessionUser user = insertUserAndBuild();
        Session created = service.createSession(user, false);
        Timestamp longAgo = new Timestamp(System.currentTimeMillis() - 600_000);
        Timestamp soon = new Timestamp(System.currentTimeMillis() + 60_000);
        jdbcTemplate.update("UPDATE kkdugi_session SET upd_dtm = ?, exp_dtm = ? WHERE sess_id = ?",
                longAgo, soon, created.getId());

        service.extend(service.findSession(created.getId()));

        long expected = System.currentTimeMillis() + properties.getSessionTimeout().toMillis();
        assertThat(service.findSession(created.getId()).getExpiresAt().getTime())
                .isBetween(expected - 30_000, expected + 30_000);
    }

    @Test
    void extend_withinRefreshInterval_skipsWrite() {
        SessionUser user = insertUserAndBuild();
        Session created = service.createSession(user, false);

        service.extend(service.findSession(created.getId()));

        assertThat(service.findSession(created.getId()).getExpiresAt().getTime())
                .isEqualTo(created.getExpiresAt().getTime());
    }

    @Test
    void logout_allowMultipleFalse_deletesAllSessionsOfUser() {
        properties.setAllowMultiple(false);
        SessionUser user = insertUserAndBuild();
        Session first = service.createSession(user, false);
        jdbcTemplate.update("INSERT INTO kkdugi_session (sess_id, user_id, user_dtl, exp_dtm) "
                + "VALUES ('S_TEST_EXTRA_2', ?, '{}'::jsonb, ?)", USER_ID,
                new Timestamp(System.currentTimeMillis() + 3_600_000));

        service.logout(first.getId(), USER_ID);

        assertThat(sessionCount()).isZero();
    }

    @Test
    void logout_allowMultipleTrue_deletesOnlyGivenSession() {
        properties.setAllowMultiple(true);
        SessionUser user = insertUserAndBuild();
        Session first = service.createSession(user, false);
        Session second = service.createSession(user, false);

        service.logout(first.getId(), USER_ID);

        assertThat(service.findSession(first.getId())).isNull();
        assertThat(service.findSession(second.getId())).isNotNull();
    }

    @Test
    void updateAttribute_persistsValueAndKeepsOtherKeys() {
        SessionUser user = insertUserAndBuild();
        Session created = service.createSession(user, false);

        service.updateAttribute(created.getId(), "theme", "dark");
        service.updateAttribute(created.getId(), "pageSize", 50);
        service.updateAttribute(created.getId(), "filter", Map.of("status", "20"));

        SessionUser saved = service.findSession(created.getId()).getDetails();
        assertThat(saved.getAttributes()).containsEntry("theme", "dark").containsEntry("pageSize", 50);
        assertThat(saved.getAttributes().get("filter")).isEqualTo(Map.of("status", "20"));
        assertThat(saved.getUsername()).isEqualTo("test_session_login");
    }

    @Test
    void updateAttribute_nullValueRemovesKey() {
        SessionUser user = insertUserAndBuild();
        Session created = service.createSession(user, false);
        service.updateAttribute(created.getId(), "theme", "dark");
        service.updateAttribute(created.getId(), "keep", "me");

        service.updateAttribute(created.getId(), "theme", null);

        Map<String, Object> attributes = service.findSession(created.getId()).getDetails().getAttributes();
        assertThat(attributes).doesNotContainKey("theme").containsEntry("keep", "me");
    }
}
