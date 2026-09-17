package kkdugi.core.security.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.core.exceptions.RestfulAuthenticationException;
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

    @AfterEach
    void cleanUp() {
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
}
