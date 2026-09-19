package kkdugi.api.admin.session;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.time.LocalDate;

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

/**
 * {@code SessionMenuController}이 세션의 flat {@code SessionMenu} 목록을
 * 실제로 트리(부모의 {@code children} 배열)로 바꿔 내려주는지, 로그인
 * 전체 경로(로그인 → 토큰 → 인증된 요청)를 통해 확인한다.
 */
@SpringBootTest(classes = KkdugiAdminApplication.class)
class SessionMenuControllerTest {

    private static final String LOGIN_URL = "/api/v1.0/auth/login";
    private static final String MENU_URL = "/api/v1.0/session/menu";
    private static final String USER_ID = "U_TEST_SESSION_MENU_1";
    private static final String AUTH_ID = "A_TEST_SESSION_MENU_1";
    private static final String ROOT_MENU_ID = "M_TEST_SESSION_MENU_ROOT";
    private static final String CHILD_MENU_ID = "M_TEST_SESSION_MENU_CHILD";
    private static final String LOGIN_ID = "test_session_menu_login";
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
                USER_ID, LOGIN_ID, passwordEncoder.encode(PASSWORD), "Test User",
                "test-session-menu@example.com", "20", "SYSTEM");

        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                ROOT_MENU_ID, "root_pgm", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                ROOT_MENU_ID, "ko_KR", "루트 메뉴", "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_parent_id, menu_pgm, sort_seq, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                CHILD_MENU_ID, ROOT_MENU_ID, "child_pgm", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                CHILD_MENU_ID, "ko_KR", "자식 메뉴", "SYSTEM");

        jdbcTemplate.update(
                "INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                AUTH_ID, "ROLE_SESSION_MENU_TEST", "ROLE", "Session Menu Test Role", "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                USER_ID, AUTH_ID, Date.valueOf(LocalDate.now().minusDays(1)),
                Date.valueOf(LocalDate.now().plusDays(1)), "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_auth_menu (auth_id, menu_id, auth_val, reg_id) VALUES (?, ?, ?, ?)",
                AUTH_ID, ROOT_MENU_ID, 0x01, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_auth_menu (auth_id, menu_id, auth_val, reg_id) VALUES (?, ?, ?, ?)",
                AUTH_ID, CHILD_MENU_ID, 0x01, "SYSTEM");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_auth_menu WHERE auth_id = ?", AUTH_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_id = ?", AUTH_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id IN (?, ?)", ROOT_MENU_ID, CHILD_MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id = ?", CHILD_MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id = ?", ROOT_MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id = ?", USER_ID);
    }

    @Test
    void menu_returnsGrantedMenusAsNestedTree() throws Exception {
        String loginBody = objectMapper.writeValueAsString(new LoginRequest(LOGIN_ID, PASSWORD, false));

        MvcResult loginResult = mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        LoginResponse loginResponse = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), LoginResponse.class);
        String token = loginResponse.getToken();

        mockMvc.perform(get(MENU_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ROOT_MENU_ID))
                .andExpect(jsonPath("$[0].title").value("루트 메뉴"))
                .andExpect(jsonPath("$[0].sort").value(1))
                // 세션 내부용 필드(program/authority)는 화면 응답에 노출되지 않는다.
                .andExpect(jsonPath("$[0].program").doesNotExist())
                .andExpect(jsonPath("$[0].authority").doesNotExist())
                .andExpect(jsonPath("$[0].children.length()").value(1))
                .andExpect(jsonPath("$[0].children[0].id").value(CHILD_MENU_ID))
                .andExpect(jsonPath("$[0].children[0].parentId").value(ROOT_MENU_ID))
                .andExpect(jsonPath("$[0].children[0].title").value("자식 메뉴"))
                .andExpect(jsonPath("$[0].children[0].program").doesNotExist())
                .andExpect(jsonPath("$[0].children[0].authority").doesNotExist());
    }

    @Test
    void menu_anonymousRequest_returnsEmptyArray() throws Exception {
        mockMvc.perform(get(MENU_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
