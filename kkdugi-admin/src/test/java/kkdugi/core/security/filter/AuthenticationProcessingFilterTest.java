package kkdugi.core.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import kkdugi.KkdugiAdminApplication;
import kkdugi.core.security.models.LoginRequest;
import kkdugi.core.security.models.LoginResponse;
import kkdugi.core.security.service.SessionService;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AuthenticationProcessingFilterTest {

    private static final String LOGIN_URL = "/api/v1.0/admin/auth/login";
    private static final String LOGOUT_URL = "/api/v1.0/admin/auth/logout";
    private static final String USER_ID = "U_TEST_LOGIN_1";
    private static final String LOGIN_ID = "test_login_filter";
    private static final String PASSWORD = "correct-password";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
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
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id = ?", USER_ID);
    }

    private String loginBody(boolean force) {
        return objectMapper.writeValueAsString(new LoginRequest(LOGIN_ID, PASSWORD, force));
    }

    @Test
    void login_withValidCredentials_returnsTokenAndCreatesSession() throws Exception {
        mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_JSON).content(loginBody(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.username").value(LOGIN_ID));

        Long sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kkdugi_session WHERE user_id = ?", Long.class, USER_ID);
        assertThat(sessionCount).isEqualTo(1L);
    }

    @Test
    void login_withWrongPassword_returns401() throws Exception {
        mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(LOGIN_ID, "wrong", false))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withUnknownUsername_returns401() throws Exception {
        mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("no_such_login", PASSWORD, false))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_secondLoginWithoutForce_returns409() throws Exception {
        mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_JSON).content(loginBody(false)))
                .andExpect(status().isOk());

        mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_JSON).content(loginBody(false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(SessionService.ERR_DUPLICATE));
    }

    @Test
    void login_secondLoginWithForce_replacesSession() throws Exception {
        MvcResult first = mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_JSON).content(loginBody(false)))
                .andExpect(status().isOk())
                .andReturn();
        String firstToken = readToken(first);

        mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_JSON).content(loginBody(true)))
                .andExpect(status().isOk());

        Long sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kkdugi_session WHERE user_id = ?", Long.class, USER_ID);
        assertThat(sessionCount).isEqualTo(1L);
        assertThat(firstToken).isNotBlank();
    }

    @Test
    void logout_invalidatesSession() throws Exception {
        MvcResult loginResult = mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_JSON).content(loginBody(false)))
                .andExpect(status().isOk())
                .andReturn();
        String token = readToken(loginResult);

        mockMvc.perform(post(LOGOUT_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        Long sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kkdugi_session WHERE user_id = ?", Long.class, USER_ID);
        assertThat(sessionCount).isEqualTo(0L);
    }

    private String readToken(MvcResult result) throws Exception {
        LoginResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class);
        return response.getToken();
    }
}
