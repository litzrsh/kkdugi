package kkdugi.core.security.service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import kkdugi.KkdugiAdminApplication;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.security.models.SessionUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class KkdugiUserDetailsServiceTest {

    private static final String USER_ID = "U_TEST_UDS_1";
    private static final String AUTH_ID = "A_TEST_UDS_1";
    private static final String AUTH_ID_2 = "A_TEST_UDS_2";
    private static final String LOGIN_ID = "test_uds_login";
    private static final String MENU_ID = "M_TEST_UDS_1";
    private static final String PARENT_MENU_ID = "M_TEST_UDS_PARENT";

    @Autowired
    private KkdugiUserDetailsService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_auth_menu WHERE menu_id = ?", MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id = ?", MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id = ?", MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id = ?", PARENT_MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id = ?", PARENT_MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_id IN (?, ?)", AUTH_ID, AUTH_ID_2);
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id = ?", USER_ID);
    }

    private void insertMenu() {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                MENU_ID, "test_pgm", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                MENU_ID, "ko_KR", "테스트 메뉴", "SYSTEM");
    }

    /** MENU_ID를 사용안함(use_yn='N') 상위 메뉴 밑에 둔다 — 리프 자체는 활성이다. */
    private void insertMenuUnderDisabledParent() {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, use_yn, sort_seq, reg_id) VALUES (?, 'N', ?, ?)",
                PARENT_MENU_ID, 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                PARENT_MENU_ID, "ko_KR", "비활성 상위 메뉴", "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_parent_id, menu_pgm, sort_seq, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                MENU_ID, PARENT_MENU_ID, "test_pgm", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                MENU_ID, "ko_KR", "테스트 메뉴", "SYSTEM");
    }

    private void grantMenuAuthority(String authId, int authVal) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_auth_menu (auth_id, menu_id, auth_val, reg_id) VALUES (?, ?, ?, ?)",
                authId, MENU_ID, authVal, "SYSTEM");
    }

    private void insertUser() {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_pwd, user_nm, user_email, "
                        + "user_stat_cd, reg_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                USER_ID, LOGIN_ID, "encoded-pwd", "Test User", "test-uds@example.com", "20", "SYSTEM");
    }

    private void insertAuthority() {
        grantRole(AUTH_ID, "ROLE_TEST");
    }

    /** Reuse the seeded role; role type/code is unique in the database. */
    private void grantSystemAdminRole() {
        String authId = jdbcTemplate.queryForObject(
                "SELECT auth_id FROM kkdugi_auth_base WHERE auth_tp_cd = 'ROLE' AND auth_role_cd = ?",
                String.class, kkdugi.core.Constants.SYS_ADMIN);
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                USER_ID, authId, Date.valueOf(LocalDate.now().minusDays(1)),
                Date.valueOf(LocalDate.now().plusDays(1)), "SYSTEM");
    }

    private void grantRole(String authId, String roleCd) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                authId, roleCd, "ROLE", roleCd, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                USER_ID, authId, Date.valueOf(LocalDate.now().minusDays(1)),
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

    @Test
    void loadUserByUsername_returnsMenuGrantedByAuthority() {
        insertUser();
        insertMenu();
        insertAuthority();
        grantMenuAuthority(AUTH_ID, 0x03); // READ|WRTE

        List<SessionMenu> menus = ((SessionUser) service.loadUserByUsername(LOGIN_ID)).getMenus();

        assertThat(menus).hasSize(1);
        assertThat(menus.get(0).getId()).isEqualTo(MENU_ID);
        assertThat(menus.get(0).getTitle()).isEqualTo("테스트 메뉴");
        assertThat(menus.get(0).getAuthority()).isEqualTo(0x03);
    }

    @Test
    void loadUserByUsername_aggregatesMenuAuthorityAcrossMultipleRoles() {
        insertUser();
        insertMenu();
        grantRole(AUTH_ID, "ROLE_A");
        grantRole(AUTH_ID_2, "ROLE_B");
        grantMenuAuthority(AUTH_ID, 0x01);   // READ
        grantMenuAuthority(AUTH_ID_2, 0x02); // WRTE

        List<SessionMenu> menus = ((SessionUser) service.loadUserByUsername(LOGIN_ID)).getMenus();

        assertThat(menus).hasSize(1);
        assertThat(menus.get(0).getAuthority()).isEqualTo(0x03);
    }

    @Test
    void loadUserByUsername_excludesMenuWithZeroAuthority() {
        insertUser();
        insertMenu();
        insertAuthority();
        grantMenuAuthority(AUTH_ID, 0);

        List<SessionMenu> menus = ((SessionUser) service.loadUserByUsername(LOGIN_ID)).getMenus();

        assertThat(menus).isEmpty();
    }

    @Test
    void loadUserByUsername_sysAdminSeesAllMenusRegardlessOfAuthMenu() {
        insertUser();
        insertMenu();
        grantSystemAdminRole();

        List<SessionMenu> menus = ((SessionUser) service.loadUserByUsername(LOGIN_ID)).getMenus();

        // SYS_ADMIN은 기본 시드 메뉴(V10)도 함께 받으므로 전체 개수가 아니라 이 테스트의 메뉴만 본다.
        SessionMenu fixture = menus.stream().filter(m -> MENU_ID.equals(m.getId())).findFirst().orElseThrow();
        assertThat(fixture.getAuthority()).isEqualTo(0xffff);
    }

    @Test
    void loadUserByUsername_excludesMenuUnderDisabledAncestor() {
        insertUser();
        insertMenuUnderDisabledParent();
        insertAuthority();
        grantMenuAuthority(AUTH_ID, 0x03);

        List<SessionMenu> menus = ((SessionUser) service.loadUserByUsername(LOGIN_ID)).getMenus();

        // 리프 자체는 활성이고 직접 권한도 있지만, 상위 메뉴가 사용안함이라
        // 전체 목록에서 빠져야 한다 — 트리에서만 숨기면 PragmaController가
        // 여전히 세션의 flat 목록으로 /pragma/{menuId} 접근을 허용해버린다.
        assertThat(menus).isEmpty();
    }

    @Test
    void loadUserByUsername_sysAdminAlsoExcludesMenuUnderDisabledAncestor() {
        insertUser();
        insertMenuUnderDisabledParent();
        grantSystemAdminRole();

        List<SessionMenu> menus = ((SessionUser) service.loadUserByUsername(LOGIN_ID)).getMenus();

        // 기본 시드 메뉴(V10)는 그대로 보이므로, 비활성 상위 아래의 이 테스트 메뉴들만 빠졌는지 본다.
        assertThat(menus).extracting(SessionMenu::getId).doesNotContain(MENU_ID, PARENT_MENU_ID);
    }
}
