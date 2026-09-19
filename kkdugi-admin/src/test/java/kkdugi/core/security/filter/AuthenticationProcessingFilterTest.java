package kkdugi.core.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.http.Cookie;
import kkdugi.KkdugiAdminApplication;
import kkdugi.core.security.config.SecurityConfigurationProperties;
import kkdugi.core.security.service.JwtTokenService;
import kkdugi.support.TestLogin;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AuthenticationProcessingFilterTest {

    private static final String LOGIN_URL = TestLogin.LOGIN_URL;
    private static final String LOGOUT_URL = "/api/v1.0/auth/logout";
    private static final String MENU_URL = "/api/v1.0/menu";
    private static final String COOKIE = TestLogin.TOKEN_COOKIE;
    private static final String USER_ID = "U_TEST_LOGIN_1";
    private static final String LOGIN_ID = "test_login_filter";
    private static final String PASSWORD = "correct-password";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityConfigurationProperties properties;

    @Autowired
    private JwtTokenService jwtTokenService;

    private MockMvc mockMvc;
    private boolean originalAllowMultiple;

    @BeforeEach
    void setUp() {
        originalAllowMultiple = properties.isAllowMultiple();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_pwd, user_nm, user_email, "
                        + "user_stat_cd, reg_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                USER_ID, LOGIN_ID, passwordEncoder.encode(PASSWORD), "Test Login User",
                "test-login-filter@example.com", "20", "SYSTEM");
    }

    @AfterEach
    void cleanUp() {
        properties.setAllowMultiple(originalAllowMultiple);
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id = ?", USER_ID);
    }

    private long sessionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kkdugi_session WHERE user_id = ?", Long.class, USER_ID);
    }

    private MockHttpServletResponse login(String username, String password, boolean force) throws Exception {
        return TestLogin.submit(mockMvc, username, password, force).getResponse();
    }

    // ---- 로그인(form submit) ----

    @Test
    void login_withValidCredentials_setsTokenCookieAndRedirectsToRoot() throws Exception {
        MockHttpServletResponse response = login(LOGIN_ID, PASSWORD, false);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        Cookie cookie = response.getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.getPath()).isEqualTo("/");
        // 프론트 JS가 읽어야 하므로 HttpOnly가 아니다.
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("SameSite=Lax");
        assertThat(sessionCount()).isEqualTo(1L);
    }

    @Test
    void login_usesConfiguredCookieName() throws Exception {
        String original = properties.getTokenCookieName();
        properties.setTokenCookieName("MY_TOKEN");
        try {
            MockHttpServletResponse response = login(LOGIN_ID, PASSWORD, false);

            assertThat(response.getCookie("MY_TOKEN")).isNotNull();
            assertThat(response.getCookie(COOKIE)).isNull();
        } finally {
            properties.setTokenCookieName(original);
        }
    }

    @Test
    void login_withWrongPassword_returnsJsonLoginError() throws Exception {
        MockHttpServletResponse response = login(LOGIN_ID, "wrong", false);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\"");
        assertThat(response.getCookie(COOKIE)).isNull();
        assertThat(sessionCount()).isZero();
    }

    @Test
    void login_withUnknownUsername_returnsJsonLoginError() throws Exception {
        MockHttpServletResponse response = login("no_such_login", PASSWORD, false);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void login_jsonBody_isRejectedAsMalformed() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + LOGIN_ID + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\"");
        assertThat(sessionCount()).isZero();
    }

    @Test
    void login_secondLoginWithoutForce_returnsJsonDuplicateChallenge() throws Exception {
        login(LOGIN_ID, PASSWORD, false);

        MockHttpServletResponse second = login(LOGIN_ID, PASSWORD, false);

        assertThat(second.getStatus()).isEqualTo(401);
        assertThat(second.getRedirectedUrl()).isNull();
        assertThat(second.getContentAsString()).contains("\"code\":\"session.err.duplicate\"");
        assertThat(second.getCookie(COOKIE)).isNull();
        assertThat(sessionCount()).isEqualTo(1L);
    }

    @Test
    void login_secondLoginWithForce_replacesSession() throws Exception {
        String firstToken = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();

        MockHttpServletResponse second = login(LOGIN_ID, PASSWORD, true);

        assertThat(second.getRedirectedUrl()).isEqualTo("/");
        assertThat(sessionCount()).isEqualTo(1L);
        assertThat(sessionExists(firstToken)).isFalse();
        assertThat(sessionExists(second.getCookie(COOKIE).getValue())).isTrue();
    }

    // ---- 토큰 인증: 헤더 / 쿠키 ----

    private boolean sessionExists(String token) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kkdugi_session WHERE sess_id = ?", Long.class,
                jwtTokenService.extractSessionId(token));
        return count != null && count > 0;
    }

    @Test
    void tokenAuth_supportsBothHeaderAndCookie() throws Exception {
        properties.setAllowMultiple(true);
        String token = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();

        // 헤더로 로그아웃 → 세션 삭제(=헤더 인증 성공)
        mockMvc.perform(post(LOGOUT_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(sessionCount()).isZero();

        String cookieToken = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();
        // 쿠키로 로그아웃 → 세션 삭제(=쿠키 인증 성공)
        mockMvc.perform(post(LOGOUT_URL).cookie(new Cookie(COOKIE, cookieToken)))
                .andExpect(status().isNoContent());
        assertThat(sessionCount()).isZero();
    }

    @Test
    void tokenAuth_headerTakesPrecedenceOverCookie() throws Exception {
        properties.setAllowMultiple(true);
        String validToken = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();

        // 유효하지 않은 헤더 + 유효한 쿠키 → 헤더가 우선이므로 인증되지 않아 세션이 남는다.
        mockMvc.perform(post(LOGOUT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                        .cookie(new Cookie(COOKIE, validToken)))
                .andExpect(status().isNoContent());

        assertThat(sessionCount()).isEqualTo(1L);
    }

    // ---- 로그아웃 ----

    @Test
    void logout_allowMultipleFalse_deletesEverySessionOfTheUser() throws Exception {
        properties.setAllowMultiple(false);
        String token = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();
        // 정책 위반 상태(같은 사용자의 세션이 둘 이상)를 직접 만든다 — 예: allowMultiple을 바꾼 이력.
        insertExtraSession("S_TEST_EXTRA_1");
        assertThat(sessionCount()).isEqualTo(2L);

        MockHttpServletResponse response = mockMvc.perform(post(LOGOUT_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertThat(sessionCount()).isZero();
        assertCookieCleared(response);
    }

    @Test
    void logout_allowMultipleTrue_deletesOnlyCurrentSession() throws Exception {
        properties.setAllowMultiple(true);
        String token = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();
        String otherToken = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();
        assertThat(sessionCount()).isEqualTo(2L);

        mockMvc.perform(post(LOGOUT_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(sessionCount()).isEqualTo(1L);
        // 남은 세션은 다른 기기의 것 — 그 토큰으로 로그아웃하면 마지막 세션도 사라진다.
        mockMvc.perform(post(LOGOUT_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNoContent());
        assertThat(sessionCount()).isZero();
    }

    @Test
    void logout_withoutToken_stillClearsCookieAndKeepsSessions() throws Exception {
        login(LOGIN_ID, PASSWORD, false);

        MockHttpServletResponse response = mockMvc.perform(post(LOGOUT_URL))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertThat(sessionCount()).isEqualTo(1L);
        assertCookieCleared(response);
    }

    private void assertCookieCleared(MockHttpServletResponse response) {
        Cookie cleared = response.getCookie(COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.getPath()).isEqualTo("/");
    }

    private void insertExtraSession(String sessionId) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_session (sess_id, user_id, user_dtl, exp_dtm) VALUES (?, ?, '{}'::jsonb, ?)",
                sessionId, USER_ID, new Timestamp(System.currentTimeMillis() + 3_600_000));
    }

    // ---- 슬라이딩 세션 ----

    @Test
    void authenticatedRequest_extendsSessionExpiry() throws Exception {
        String token = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();
        // 마지막 갱신이 오래됐고 곧 만료되는 상태로 되돌린다.
        Timestamp longAgo = new Timestamp(System.currentTimeMillis() - 600_000);
        Timestamp soon = new Timestamp(System.currentTimeMillis() + 60_000);
        jdbcTemplate.update("UPDATE kkdugi_session SET upd_dtm = ?, exp_dtm = ? WHERE user_id = ?", longAgo, soon, USER_ID);

        mockMvc.perform(get(MENU_URL).header("X-Menu-Id", "__shell__").cookie(new Cookie(COOKIE, token))).andExpect(status().isOk());

        Timestamp expiresAt = jdbcTemplate.queryForObject(
                "SELECT exp_dtm FROM kkdugi_session WHERE user_id = ?", Timestamp.class, USER_ID);
        long expected = System.currentTimeMillis() + properties.getSessionTimeout().toMillis();
        assertThat(expiresAt.getTime()).isBetween(expected - 30_000, expected + 30_000);
    }

    @Test
    void authenticatedRequest_withinRefreshInterval_doesNotWrite() throws Exception {
        String token = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();
        Timestamp before = jdbcTemplate.queryForObject(
                "SELECT exp_dtm FROM kkdugi_session WHERE user_id = ?", Timestamp.class, USER_ID);

        mockMvc.perform(get(MENU_URL).header("X-Menu-Id", "__shell__").cookie(new Cookie(COOKIE, token))).andExpect(status().isOk());

        Timestamp after = jdbcTemplate.queryForObject(
                "SELECT exp_dtm FROM kkdugi_session WHERE user_id = ?", Timestamp.class, USER_ID);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void expiredSession_isNotAuthenticatedAndNotRevived() throws Exception {
        String token = login(LOGIN_ID, PASSWORD, false).getCookie(COOKIE).getValue();
        jdbcTemplate.update("UPDATE kkdugi_session SET exp_dtm = ? WHERE user_id = ?",
                new Timestamp(new Date().getTime() - 1_000), USER_ID);

        mockMvc.perform(post(LOGOUT_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        // 인증되지 않았으므로 로그아웃 핸들러가 세션을 건드리지 않았고, 만료 시각도 그대로다.
        Timestamp expiresAt = jdbcTemplate.queryForObject(
                "SELECT exp_dtm FROM kkdugi_session WHERE user_id = ?", Timestamp.class, USER_ID);
        assertThat(expiresAt.getTime()).isLessThan(System.currentTimeMillis());
    }
    private MockHttpServletResponse passwordAction(String action, String nextPassword, boolean force) throws Exception {
        var request = post(LOGIN_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", LOGIN_ID).param("password", PASSWORD).param("force", Boolean.toString(force))
                .param("passwordAction", action);
        if (nextPassword != null) request.param("newPassword", nextPassword);
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String passwordHash() {
        return jdbcTemplate.queryForObject("SELECT user_pwd FROM kkdugi_user_base WHERE user_id = ?", String.class, USER_ID);
    }

    @Test
    void nonNormalAccountsCannotLoginEvenWithForceOrPasswordAction() throws Exception {
        String[][] states = {{"10", "pending"}, {"30", "dormant"}, {"40", "resigned"}, {"50", "suspended"}};
        String original = passwordHash();
        for (String[] state : states) {
            jdbcTemplate.update("UPDATE kkdugi_user_base SET user_stat_cd = ?, pwd_stat_cd = '10' WHERE user_id = ?", state[0], USER_ID);
            MockHttpServletResponse response = passwordAction("change", "a-new-password", true);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("auth.err." + state[1]);
            assertThat(response.getCookie(COOKIE)).isNull();
            assertThat(passwordHash()).isEqualTo(original);
            assertThat(sessionCount()).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT last_login_dtm FROM kkdugi_user_base WHERE user_id = ?", Timestamp.class, USER_ID)).isNull();
        }
    }

    @Test
    void wrongPasswordDoesNotRevealUserOrPasswordState() throws Exception {
        for (String state : new String[]{"10", "20", "30", "40", "50"}) {
            jdbcTemplate.update("UPDATE kkdugi_user_base SET user_stat_cd = ?, pwd_stat_cd = '10' WHERE user_id = ?", state, USER_ID);
            MockHttpServletResponse response = login(LOGIN_ID, "incorrect-password", true);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).doesNotContain("auth.err.pending", "auth.err.dormant", "auth.err.password_required", "auth.err.resigned", "auth.err.suspended");
        }
    }

    @Test
    void initialPasswordRequiresChangeAndCannotBeExtended() throws Exception {
        jdbcTemplate.update("UPDATE kkdugi_user_base SET pwd_stat_cd = '10' WHERE user_id = ?", USER_ID);
        assertThat(login(LOGIN_ID, PASSWORD, true).getContentAsString()).contains("auth.err.password_required");
        assertThat(passwordAction("extend", null, true).getContentAsString()).contains("auth.err.password_required");
        assertThat(passwordAction("change", PASSWORD, false).getContentAsString()).contains("auth.err.password_invalid");
        assertThat(passwordAction("change", "short", false).getContentAsString()).contains("auth.err.password_invalid");
        assertThat(passwordAction("change", "가".repeat(25), false).getContentAsString()).contains("auth.err.password_invalid");
        assertThat(sessionCount()).isZero();
        long before = System.currentTimeMillis();
        MockHttpServletResponse changed = passwordAction("change", "a-new-password", false);
        assertThat(changed.getStatus()).isEqualTo(302);
        assertThat(changed.getCookie(COOKIE)).isNotNull();
        assertThat(passwordEncoder.matches("a-new-password", passwordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, passwordHash())).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT pwd_stat_cd FROM kkdugi_user_base WHERE user_id = ?", String.class, USER_ID)).isEqualTo("30");
        Timestamp expiry = jdbcTemplate.queryForObject("SELECT pwd_expr_dtm FROM kkdugi_user_base WHERE user_id = ?", Timestamp.class, USER_ID);
        assertThat(expiry.getTime()).isBetween(before + java.time.Duration.ofDays(30).toMillis(), System.currentTimeMillis() + java.time.Duration.ofDays(30).toMillis());
        assertThat(jdbcTemplate.queryForObject("SELECT last_chg_pwd_dtm FROM kkdugi_user_base WHERE user_id = ?", Timestamp.class, USER_ID)).isNotNull();
    }

    @Test
    void expiredPasswordCanBeExtendedThirtyDaysWithoutChangingPasswordOrChangeDate() throws Exception {
        Timestamp changed = Timestamp.valueOf("2020-01-01 01:02:03");
        jdbcTemplate.update("UPDATE kkdugi_user_base SET pwd_stat_cd = '20', last_chg_pwd_dtm = ?, pwd_expr_dtm = ? WHERE user_id = ?", changed, changed, USER_ID);
        String original = passwordHash();
        assertThat(login(LOGIN_ID, PASSWORD, false).getContentAsString()).contains("auth.err.password_expired");
        long before = System.currentTimeMillis();
        MockHttpServletResponse response = passwordAction("extend", null, false);
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(passwordHash()).isEqualTo(original);
        assertThat(jdbcTemplate.queryForObject("SELECT last_chg_pwd_dtm FROM kkdugi_user_base WHERE user_id = ?", Timestamp.class, USER_ID)).isEqualTo(changed);
        Timestamp expiry = jdbcTemplate.queryForObject("SELECT pwd_expr_dtm FROM kkdugi_user_base WHERE user_id = ?", Timestamp.class, USER_ID);
        assertThat(expiry.getTime()).isBetween(before + java.time.Duration.ofDays(30).toMillis(), System.currentTimeMillis() + java.time.Duration.ofDays(30).toMillis());
        assertThat(jdbcTemplate.queryForObject("SELECT pwd_stat_cd FROM kkdugi_user_base WHERE user_id = ?", String.class, USER_ID)).isEqualTo("30");
    }

    @Test
    void normalPasswordPastExpiryRequiresActionAndCanBeChanged() throws Exception {
        jdbcTemplate.update("UPDATE kkdugi_user_base SET pwd_stat_cd = '30', pwd_expr_dtm = ? WHERE user_id = ?", Timestamp.valueOf("2020-01-01 00:00:00"), USER_ID);
        assertThat(login(LOGIN_ID, PASSWORD, false).getContentAsString()).contains("auth.err.password_expired");
        assertThat(passwordAction("change", "a-new-password", false).getStatus()).isEqualTo(302);
    }

    @Test
    void duplicateConfirmationRollsBackPasswordChangeUntilForceRetry() throws Exception {
        properties.setAllowMultiple(false);
        assertThat(login(LOGIN_ID, PASSWORD, false).getStatus()).isEqualTo(302);
        String original = passwordHash();
        jdbcTemplate.update("UPDATE kkdugi_user_base SET pwd_stat_cd = '10' WHERE user_id = ?", USER_ID);
        assertThat(passwordAction("change", "a-new-password", false).getContentAsString()).contains("session.err.duplicate");
        assertThat(passwordHash()).isEqualTo(original);
        assertThat(jdbcTemplate.queryForObject("SELECT pwd_stat_cd FROM kkdugi_user_base WHERE user_id = ?", String.class, USER_ID)).isEqualTo("10");
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(passwordAction("change", "a-new-password", true).getStatus()).isEqualTo(302);
        assertThat(passwordEncoder.matches("a-new-password", passwordHash())).isTrue();
        assertThat(sessionCount()).isEqualTo(1);
    }

    @Test
    void duplicateConfirmationRollsBackExtensionAndBlockedForceKeepsOldSession() throws Exception {
        properties.setAllowMultiple(false);
        assertThat(login(LOGIN_ID, PASSWORD, false).getStatus()).isEqualTo(302);
        Timestamp old = Timestamp.valueOf("2020-01-01 00:00:00");
        jdbcTemplate.update("UPDATE kkdugi_user_base SET pwd_stat_cd = '20', pwd_expr_dtm = ? WHERE user_id = ?", old, USER_ID);
        assertThat(passwordAction("extend", null, false).getContentAsString()).contains("session.err.duplicate");
        assertThat(jdbcTemplate.queryForObject("SELECT pwd_expr_dtm FROM kkdugi_user_base WHERE user_id = ?", Timestamp.class, USER_ID)).isEqualTo(old);
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_stat_cd = '50' WHERE user_id = ?", USER_ID);
        assertThat(passwordAction("extend", null, true).getContentAsString()).contains("auth.err.suspended");
        assertThat(sessionCount()).isEqualTo(1);
    }

    @Test
    void normalPasswordCannotUseUnrequestedActions() throws Exception {
        for (String action : new String[]{"change", "extend", "unknown"}) {
            assertThat(passwordAction(action, "a-new-password", false).getContentAsString()).contains("auth.err.malformed_request");
        }
        assertThat(sessionCount()).isZero();
    }

}
