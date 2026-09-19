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
    private static final String MENU_URL = "/api/v1.0/session/menu";
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

        mockMvc.perform(get(MENU_URL).cookie(new Cookie(COOKIE, token))).andExpect(status().isOk());

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

        mockMvc.perform(get(MENU_URL).cookie(new Cookie(COOKIE, token))).andExpect(status().isOk());

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
}
