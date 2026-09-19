package kkdugi.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import kkdugi.KkdugiAdminApplication;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class CodeControllerTest {

    private static final String URL = "/api/v1.0/code";
    private static final String ROOT_ID = "C_TEST_USER_API_ROOT";
    private static final String ROOT_PATH = "/TEST_USER_API_ROOT";
    private static final String CHILD_A = "C_TEST_USER_API_A";
    private static final String CHILD_C = "C_TEST_USER_API_C";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = kkdugi.support.TestAuthorization.mvc(webApplicationContext, "admin/code");

        insertCode(ROOT_ID, null, "TEST_USER_API_ROOT", 0, ROOT_PATH, 1, "Y");
        insertCode(CHILD_A, ROOT_ID, "CHILD_A", 1, ROOT_PATH + "/CHILD_A", 1, "Y");
        insertCode(CHILD_C, ROOT_ID, "CHILD_C", 1, ROOT_PATH + "/CHILD_C", 3, "Y");
        insertLang(CHILD_A, "ko_KR", "자식A");
        insertLang(CHILD_A, "en_US", "Child A");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_code_lang WHERE code_id LIKE 'C_TEST_USER_API_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_parent_id = ?", ROOT_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_id = ?", ROOT_ID);
    }

    @Test
    void codes_byExactPath_returnsLocalizedArray() throws Exception {
        mockMvc.perform(get(URL).param("path", ROOT_PATH + "/CHILD_A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(CHILD_A))
                .andExpect(jsonPath("$[0].parentId").value(ROOT_ID))
                .andExpect(jsonPath("$[0].code").value("CHILD_A"))
                .andExpect(jsonPath("$[0].name").value("자식A"))
                .andExpect(jsonPath("$[0].path").value(ROOT_PATH + "/CHILD_A"))
                // BaseModel 상속 필드는 응답에 노출되지 않는다.
                .andExpect(jsonPath("$[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$[0].creatorId").doesNotExist())
                .andExpect(jsonPath("$[0].rownum").doesNotExist());
    }

    @Test
    void children_withLangParam_usesRequestedLanguage() throws Exception {
        mockMvc.perform(get(URL).param("path", ROOT_PATH + "/CHILD_A").param("lang", "en_US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Child A"));
    }

    @Test
    void codes_requiresPath() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
    }

    private void insertCode(String id, String parentId, String value, int level, String path, int sort, String use) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_code_base (code_id, code_parent_id, code_val, code_lvl, code_path, sort_seq, "
                        + "use_yn, reg_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, parentId, value, level, path, sort, use, "SYSTEM");
    }

    private void insertLang(String codeId, String lang, String name) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_code_lang (code_id, lang_cd, code_nm, reg_id) VALUES (?, ?, ?, ?)",
                codeId, lang, name, "SYSTEM");
    }
}
