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

    @Test
    void enumCodes_returnsLocalizedCodesAndDoesNotCacheThePreviousLocale() throws Exception {
        mockMvc.perform(get(URL).param("path", "UserStatus").param("enum", "true").param("lang", "ko_KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].id").value("UserStatus.10"))
                .andExpect(jsonPath("$[0].code").value("10"))
                .andExpect(jsonPath("$[0].name").value("대기"))
                .andExpect(jsonPath("$[0].path").value("UserStatus"))
                .andExpect(jsonPath("$[0].sort").value(1))
                .andExpect(jsonPath("$[0].createdAt").doesNotExist());
        mockMvc.perform(get(URL).param("path", "UserStatus").param("enum", "true").param("lang", "en_US"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Pending"));
        mockMvc.perform(get(URL).param("path", "UserStatus").param("enum", "true").param("lang", "ko_KR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("대기"));
    }

    @Test
    void enumCodes_supportsEveryCurrentEnum() throws Exception {
        String[][] enums = {{"AuthorityType", "2", "ROLE", "Role"}, {"PasswordStatus", "3", "10", "Initial change required"},
                {"Rbac", "4", "10", "Read"}};
        for (String[] entry : enums) {
            mockMvc.perform(get(URL).param("path", entry[0]).param("enum", "true").param("lang", "en_US"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(Integer.parseInt(entry[1])))
                    .andExpect(jsonPath("$[0].code").value(entry[2])).andExpect(jsonPath("$[0].name").value(entry[3]));
        }
    }

    @Test
    void enumCodes_rejectsInvalidTypesAndBooleanWithoutFallingBackToDatabase() throws Exception {
        for (String name : new String[]{"", "Missing", "CodeEnums", "java.lang.Thread", "kkdugi.core.enums.UserStatus", "userstatus", ROOT_PATH}) {
            mockMvc.perform(get(URL).param("path", name).param("enum", "true"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("code.err.malformed_request"));
        }
        mockMvc.perform(get(URL).param("path", "UserStatus").param("enum", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("code.err.malformed_request"));
        mockMvc.perform(get(URL).param("enum", "true")).andExpect(status().isBadRequest());
    }

    @Test
    void enumCodes_falseAndOmittedKeepDatabaseBehavior() throws Exception {
        mockMvc.perform(get(URL).param("path", ROOT_PATH + "/CHILD_A").param("enum", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(CHILD_A));
        mockMvc.perform(get(URL).param("path", "UserStatus"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void enumCodes_requiresTheCallingMenusReadPermission() throws Exception {
        kkdugi.support.TestAuthorization.mvc(webApplicationContext, "admin/user", 0, kkdugi.core.Constants.SYS_ADMIN)
                .perform(get(URL).param("path", "UserStatus").param("enum", "true"))
                .andExpect(status().isForbidden());
        kkdugi.support.TestAuthorization.mvc(webApplicationContext, "admin/user", 1, kkdugi.core.Constants.SYS_ADMIN)
                .perform(get(URL).param("path", "UserStatus").param("enum", "true"))
                .andExpect(status().isOk());
    }


    @Test
    void childrenOptionReturnsOnlyEnabledDirectChildrenIncludingLocaleExtra() throws Exception {
        jdbcTemplate.update("UPDATE kkdugi_code_base SET etc_val1 = 'ko_KR' WHERE code_id = ?", CHILD_A);
        jdbcTemplate.update("UPDATE kkdugi_code_base SET use_yn = 'N' WHERE code_id = ?", CHILD_C);
        mockMvc.perform(get(URL).param("path", ROOT_PATH).param("children", "true").param("lang", "en_US"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(CHILD_A)).andExpect(jsonPath("$[0].name").value("Child A"))
                .andExpect(jsonPath("$[0].extra1").value("ko_KR"));
        mockMvc.perform(get(URL).param("path", ROOT_PATH))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(ROOT_ID));
        mockMvc.perform(get(URL).param("path", "UserStatus").param("children", "true").param("enum", "true"))
                .andExpect(status().isBadRequest());
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
