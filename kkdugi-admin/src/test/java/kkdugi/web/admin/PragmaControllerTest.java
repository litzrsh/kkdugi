package kkdugi.web.admin;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import kkdugi.KkdugiAdminApplication;
import kkdugi.support.TestLogin;

/**
 * {@code /pragma/{menuId}}가 세션 메뉴의 {@code program} 값으로
 * {@code templates/pragma/*.vue}를 찾아 렌더링하고, {@code authority}
 * 비트마스크에 따라 {@code th:if}로 조건부 렌더링되는지 확인한다.
 * 픽스처는 {@code src/test/resources/templates/pragma/test_program.vue}(실제
 * 화면 산출물이 아니라 이 렌더링 파이프라인만 검증하기 위한 테스트 전용
 * 파일)를 쓴다.
 */
@SpringBootTest(classes = KkdugiAdminApplication.class)
class PragmaControllerTest {

    private static final String USER_ID = "U_TEST_PRAGMA_1";
    private static final String AUTH_ID = "A_TEST_PRAGMA_1";
    private static final String MENU_ID = "M_TEST_PRAGMA_1";
    private static final String NO_PROGRAM_MENU_ID = "M_TEST_PRAGMA_2";
    private static final String NESTED_MENU_ID = "M_TEST_PRAGMA_3";
    private static final String TRAVERSAL_MENU_ID = "M_TEST_PRAGMA_4";
    private static final String LOGIN_ID = "test_pragma_login";
    private static final String PASSWORD = "correct-password";

    @Autowired
    private WebApplicationContext webApplicationContext;

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
                "test-pragma@example.com", "20", "SYSTEM");

        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                MENU_ID, "test_program", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                MENU_ID, "ko_KR", "테스트 화면", "SYSTEM");

        // program이 없는 메뉴(그룹/폴더 노드) — 조회 시 404가 돼야 한다.
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, sort_seq, reg_id) VALUES (?, ?, ?)",
                NO_PROGRAM_MENU_ID, 2, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                NO_PROGRAM_MENU_ID, "ko_KR", "폴더", "SYSTEM");

        // 폴더 하위 program(admin/nested_program → templates/pragma/admin/nested_program.vue)과,
        // templates/pragma/ 밖으로 나가려는 program(..) — 후자는 실제 파일로 해석되더라도 404여야 한다.
        for (String[] extra : new String[][] {
                {NESTED_MENU_ID, "admin/nested_program"}, {TRAVERSAL_MENU_ID, "../pragma/test_program"}}) {
            jdbcTemplate.update(
                    "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                    extra[0], extra[1], 3, "SYSTEM");
            jdbcTemplate.update(
                    "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                    extra[0], "ko_KR", "추가 화면", "SYSTEM");
        }

        jdbcTemplate.update(
                "INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                AUTH_ID, "ROLE_PRAGMA_TEST", "ROLE", "Pragma Test Role", "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                USER_ID, AUTH_ID, Date.valueOf(LocalDate.now().minusDays(1)),
                Date.valueOf(LocalDate.now().plusDays(1)), "SYSTEM");
        // READ(0x01)만 부여 — WRTE(0x02)는 없음.
        jdbcTemplate.update(
                "INSERT INTO kkdugi_auth_menu (auth_id, menu_id, auth_val, reg_id) VALUES (?, ?, ?, ?)",
                AUTH_ID, MENU_ID, 0x01, "SYSTEM");
        for (String extraId : new String[] {NESTED_MENU_ID, TRAVERSAL_MENU_ID}) {
            jdbcTemplate.update(
                    "INSERT INTO kkdugi_auth_menu (auth_id, menu_id, auth_val, reg_id) VALUES (?, ?, ?, ?)",
                    AUTH_ID, extraId, 0x01, "SYSTEM");
        }
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_auth_menu WHERE auth_id = ?", AUTH_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_id = ?", AUTH_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id IN (?, ?, ?, ?)",
                MENU_ID, NO_PROGRAM_MENU_ID, NESTED_MENU_ID, TRAVERSAL_MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id IN (?, ?, ?, ?)",
                MENU_ID, NO_PROGRAM_MENU_ID, NESTED_MENU_ID, TRAVERSAL_MENU_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id = ?", USER_ID);
    }

    private String login() throws Exception {
        return TestLogin.login(mockMvc, LOGIN_ID, PASSWORD);
    }

    @Test
    void pragma_rendersFragment_withAuthoritiesReflectingGrantedBitmask() throws Exception {
        String token = login();

        mockMvc.perform(get("/pragma/" + MENU_ID).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("READ_OK")))
                .andExpect(content().string(not(containsString("WRTE_OK"))));
    }

    @Test
    void pragma_programInSubfolder_isRendered() throws Exception {
        String token = login();

        mockMvc.perform(get("/pragma/" + NESTED_MENU_ID).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("NESTED_READ_OK")));
    }

    @Test
    void pragma_programEscapingPragmaRoot_returns404() throws Exception {
        String token = login();

        mockMvc.perform(get("/pragma/" + TRAVERSAL_MENU_ID).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void pragma_menuWithoutProgram_returns404() throws Exception {
        String token = login();

        mockMvc.perform(get("/pragma/" + NO_PROGRAM_MENU_ID).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void pragma_menuNotInSession_returns404() throws Exception {
        String token = login();

        mockMvc.perform(get("/pragma/M_NO_SUCH_MENU").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishedScreens_renderRealSfcWithPerRequestPermissions() throws Exception {
        Path output = Path.of("target", "pragma-test-output");
        Files.createDirectories(output);
        for (String program : new String[]{"admin/code", "admin/message", "admin/menu"}) {
            for (int authority : new int[]{1, 3, 5, 15}) {
                jdbcTemplate.update("UPDATE kkdugi_menu_base SET menu_pgm = ? WHERE menu_id = ?", program, MENU_ID);
                jdbcTemplate.update("UPDATE kkdugi_auth_menu SET auth_val = ? WHERE auth_id = ?", authority, AUTH_ID);
                jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id = ?", USER_ID);
                String token = login();
                String rendered = mockMvc.perform(get("/pragma/" + MENU_ID).param("lang", "en_US")
                        .header(HttpHeaders.ACCEPT, "application/json, text/html;q=0.9")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().string(containsString("@vue/pages/BatchPage.vue")))
                        .andExpect(content().string(not(containsString("th:if"))))
                        .andReturn().getResponse().getContentAsString();
                String compact = rendered.replaceAll("\\s", "");
                assertTrue(compact.contains("\"20\":" + ((authority & 2) != 0)));
                assertTrue(compact.contains("\"30\":" + ((authority & 4) != 0)));
                Files.writeString(output.resolve(program.replace('/', '_') + "-" + authority + ".vue"), rendered);
            }
        }
    }

    @Test
    void publishedScreen_withoutRead_usesRequestedMessageLocale() throws Exception {
        jdbcTemplate.update("UPDATE kkdugi_menu_base SET menu_pgm = 'admin/code' WHERE menu_id = ?", MENU_ID);
        jdbcTemplate.update("UPDATE kkdugi_auth_menu SET auth_val = 2 WHERE auth_id = ?", AUTH_ID);
        String token = login();
        mockMvc.perform(get("/pragma/" + MENU_ID).param("lang", "en_US")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<BatchPage"))))
                .andExpect(content().string(containsString("You do not have permission")));
    }

    @Test
    void pragma_anonymousRequest_returns404() throws Exception {
        mockMvc.perform(get("/pragma/" + MENU_ID))
                .andExpect(status().isNotFound());
    }
}
