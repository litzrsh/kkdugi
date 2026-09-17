package kkdugi.core.security.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import kkdugi.KkdugiAdminApplication;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.security.models.Authority;
import kkdugi.core.security.models.SessionUser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V8__insert_default_admin.sql이 만든 기본 admin/SYS_ADMIN 시드 데이터를
 * 검증한다. 이 계정/권한 행 자체는 이 테스트가 만든 게 아니라 마이그레이션이
 * 만든 영구 데이터라 지우지 않는다 — 세션만 정리한다.
 */
@SpringBootTest(classes = KkdugiAdminApplication.class)
class DefaultAdminSeedTest {

    private static final String USER_ID_PATTERN = "U\\d{16}";
    private static final String AUTH_ID_PATTERN = "A\\d{16}";

    @Autowired
    private KkdugiUserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id = "
                + "(SELECT user_id FROM kkdugi_user_base WHERE user_login_id = 'admin')");
    }

    @Test
    void adminSeedUser_hasExpectedIdFormatAndPassword() {
        UserDetails admin = userDetailsService.loadUserByUsername("admin");
        SessionUser user = (SessionUser) admin;

        assertThat(user.getId()).matches(USER_ID_PATTERN);
        assertThat(passwordEncoder.matches("admin1234", user.getPassword())).isTrue();
    }

    @Test
    void adminSeedUser_hasSysAdminRoleAuthority() {
        UserDetails admin = userDetailsService.loadUserByUsername("admin");
        SessionUser user = (SessionUser) admin;

        assertThat(user.getAuthorities()).hasSize(1);
        Authority authority = user.getAuthorities().get(0);
        assertThat(authority.getId()).matches(AUTH_ID_PATTERN);
        assertThat(authority.getAuthority()).isEqualTo("SYS_ADMIN");
        assertThat(authority.getType()).isEqualTo(AuthorityType.ROLE);
    }
}
