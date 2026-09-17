package kkdugi.core.security.service;

import java.sql.Date;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import kkdugi.KkdugiAdminApplication;
import kkdugi.core.security.models.SessionUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class KkdugiUserDetailsServiceTest {

    private static final String USER_ID = "U_TEST_UDS_1";
    private static final String AUTH_ID = "A_TEST_UDS_1";
    private static final String LOGIN_ID = "test_uds_login";

    @Autowired
    private KkdugiUserDetailsService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_id = ?", AUTH_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id = ?", USER_ID);
    }

    private void insertUser() {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_pwd, user_nm, user_email, "
                        + "user_stat_cd, reg_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                USER_ID, LOGIN_ID, "encoded-pwd", "Test User", "test-uds@example.com", "20", "SYSTEM");
    }

    private void insertAuthority() {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                AUTH_ID, "ROLE_TEST", "ROLE", "Test Role", "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                USER_ID, AUTH_ID, Date.valueOf(LocalDate.now().minusDays(1)),
                Date.valueOf(LocalDate.now().plusDays(1)), "SYSTEM");
    }

    @Test
    void loadUserByUsername_returnsUserWithAuthorities() {
        insertUser();
        insertAuthority();

        UserDetails found = service.loadUserByUsername(LOGIN_ID);

        assertThat(found).isInstanceOf(SessionUser.class);
        SessionUser user = (SessionUser) found;
        assertThat(user.getId()).isEqualTo(USER_ID);
        assertThat(user.getUsername()).isEqualTo(LOGIN_ID);
        assertThat(user.getAuthorities()).hasSize(1);
        assertThat(user.getAuthorities().get(0).getAuthority()).isEqualTo("ROLE_TEST");
    }

    @Test
    void loadUserByUsername_excludesExpiredAuthority() {
        insertUser();
        jdbcTemplate.update(
                "INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                AUTH_ID, "ROLE_EXPIRED", "ROLE", "Expired Role", "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                USER_ID, AUTH_ID, Date.valueOf(LocalDate.now().minusDays(10)),
                Date.valueOf(LocalDate.now().minusDays(1)), "SYSTEM");

        UserDetails found = service.loadUserByUsername(LOGIN_ID);

        assertThat(((SessionUser) found).getAuthorities()).isEmpty();
    }

    @Test
    void loadUserByUsername_unknownUsername_throwsUsernameNotFound() {
        assertThatThrownBy(() -> service.loadUserByUsername("no_such_login"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void updatePassword_persistsNewEncodedPassword() {
        insertUser();
        UserDetails found = service.loadUserByUsername(LOGIN_ID);

        service.updatePassword(found, "new-encoded-pwd");

        String persisted = jdbcTemplate.queryForObject(
                "SELECT user_pwd FROM kkdugi_user_base WHERE user_id = ?", String.class, USER_ID);
        assertThat(persisted).isEqualTo("new-encoded-pwd");
    }

    @Test
    void loadUserByUsername_authorityListIsEmptyWhenNone() {
        insertUser();

        UserDetails found = service.loadUserByUsername(LOGIN_ID);

        assertThat(((SessionUser) found).getAuthorities()).isEmpty();
    }
}
