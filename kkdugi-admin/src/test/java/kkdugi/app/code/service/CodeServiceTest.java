package kkdugi.app.code.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.code.models.Code;
import kkdugi.app.code.models.CodeParams;
import kkdugi.core.models.Page;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class CodeServiceTest {

    private static final String ROOT_ID = "C_TEST_USER_SVC_ROOT";
    private static final String ROOT_PATH = "/TEST_USER_SVC_ROOT";
    private static final String CHILD_A = "C_TEST_USER_SVC_A";
    private static final String CHILD_B = "C_TEST_USER_SVC_B";
    private static final String CHILD_C = "C_TEST_USER_SVC_C";

    @Autowired
    private CodeService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        insertCode(ROOT_ID, null, "TEST_USER_SVC_ROOT", 0, ROOT_PATH, 1, "Y");
        insertCode(CHILD_A, ROOT_ID, "CHILD_A", 1, ROOT_PATH + "/CHILD_A", 1, "Y");
        insertCode(CHILD_B, ROOT_ID, "CHILD_B", 1, ROOT_PATH + "/CHILD_B", 2, "N");
        insertCode(CHILD_C, ROOT_ID, "CHILD_C", 1, ROOT_PATH + "/CHILD_C", 3, "Y");
        insertLang(ROOT_ID, "ko_KR", "루트");
        insertLang(ROOT_ID, "en_US", "Root");
        insertLang(CHILD_A, "ko_KR", "자식A");
        insertLang(CHILD_A, "en_US", "Child A");
        insertLang(CHILD_B, "ko_KR", "자식B");
        // CHILD_C는 언어 행이 없다 — 이름이 코드 값으로 대체되는지 확인하기 위함.
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_code_lang WHERE code_id LIKE 'C_TEST_USER_SVC_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_parent_id = ?", ROOT_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_id = ?", ROOT_ID);
    }

    @Test
    void findChildren_byParentId_returnsUsedChildrenInSortOrderWithLocalizedName() {
        Page<Code> page = service.findChildren(new CodeParams(ROOT_ID, null, 1, 200), "ko_KR");

        assertThat(page.getContents()).extracting(Code::getId).containsExactly(CHILD_A, CHILD_C);
        assertThat(page.getContents()).extracting(Code::getName).containsExactly("자식A", "CHILD_C");
        assertThat(page.getContents().get(0).getCode()).isEqualTo("CHILD_A");
        assertThat(page.getContents().get(0).getPath()).isEqualTo(ROOT_PATH + "/CHILD_A");
        assertThat(page.getContents().get(0).getLevel()).isEqualTo(1);
        assertThat(page.getContents().get(1).getRemarks()).isNull();
        assertThat(page.getTotalItems()).isEqualTo(2);
    }

    @Test
    void findChildren_byPath_resolvesParentFromPathAndUsesRequestedLanguage() {
        Page<Code> page = service.findChildren(new CodeParams(null, ROOT_PATH, 1, 200), "en_US");

        assertThat(page.getContents()).extracting(Code::getName).containsExactly("Child A", "CHILD_C");
    }

    @Test
    void findChildren_withUnknownPath_returnsEmptyInsteadOfRoots() {
        Page<Code> page = service.findChildren(new CodeParams(null, "/NO_SUCH_PATH", 1, 200), "ko_KR");

        assertThat(page.getContents()).isEmpty();
        assertThat(page.getTotalItems()).isZero();
    }

    @Test
    void findChildren_withoutParentOrPath_returnsRootsOnly() {
        Page<Code> page = service.findChildren(new CodeParams(null, null, 1, 200), "ko_KR");

        assertThat(page.getContents()).extracting(Code::getId).contains(ROOT_ID).doesNotContain(CHILD_A);
    }

    @Test
    void findChildren_pagesAtQueryLevelAndReportsResolvedPageParams() {
        Page<Code> page = service.findChildren(new CodeParams(ROOT_ID, null, 2, 1), "ko_KR");

        assertThat(page.getContents()).extracting(Code::getId).containsExactly(CHILD_C);
        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(1);
        assertThat(page.getTotalItems()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void findChildren_withUnresolvedPageParams_reportsDefaults() {
        Page<Code> page = service.findChildren(new CodeParams(ROOT_ID, null, 0, 0), "ko_KR");

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
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
