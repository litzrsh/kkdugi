package kkdugi.app.code.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.code.models.Code;

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

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @BeforeEach
    void seed() {
        cacheManager.getCache(CodeService.CACHE_NAME).clear();
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
        cacheManager.getCache(CodeService.CACHE_NAME).clear();
        jdbcTemplate.update("DELETE FROM kkdugi_code_lang WHERE code_id LIKE 'C_TEST_USER_SVC_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_parent_id = ?", ROOT_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_id = ?", ROOT_ID);
    }

    @Test
    void findCodes_matchesExactPathAndLanguage() {
        List<Code> list = service.findCodes(ROOT_PATH + "/CHILD_A", "ko_KR");
        assertThat(list).extracting(Code::getId).containsExactly(CHILD_A);
        assertThat(list).extracting(Code::getName).containsExactly("자식A");
        assertThat(service.findCodes(ROOT_PATH + "/CHILD_A", "en_US"))
                .extracting(Code::getName).containsExactly("Child A");
        assertThat(service.findCodes(ROOT_PATH, "ko_KR"))
                .extracting(Code::getId).containsExactly(ROOT_ID);
    }

    @Test
    void findCodes_excludesDisabledAndUnknownPaths() {
        assertThat(service.findCodes(ROOT_PATH + "/CHILD_B", "ko_KR")).isEmpty();
        assertThat(service.findCodes("/NO_SUCH_PATH", "ko_KR")).isEmpty();
    }

    @Test
    void findCodes_fallsBackToCodeWithoutTranslation() {
        List<Code> list = service.findCodes(ROOT_PATH + "/CHILD_C", "ko_KR");
        assertThat(list).extracting(Code::getName).containsExactly("CHILD_C");
        assertThat(list.get(0).getRemarks()).isNull();
    }

    @Test
    void findCodes_cachesByPathAndLanguage() {
        service.findCodes(ROOT_PATH + "/CHILD_A", "ko_KR");
        org.springframework.cache.Cache cache=cacheManager.getCache(CodeService.CACHE_NAME);
        assertThat(cache.get(ROOT_PATH + "/CHILD_A@@ko_KR")).isNotNull();
        assertThat(cache.get(ROOT_PATH + "/CHILD_A@@en_US")).isNull();
    }

    @Autowired
    private kkdugi.app.admin.code.service.AdminCodeService adminService;

    @Test
    void adminPersist_invalidatesPreviouslyCachedTranslations() {
        assertThat(service.findCodes(ROOT_PATH, "ko_KR")).extracting(Code::getName).containsExactly("루트");
        adminService.persist(new kkdugi.app.admin.code.models.AdminCodePersistRequest(null,
                List.of(new kkdugi.app.admin.code.models.AdminCode(ROOT_ID, null, "TEST_USER_SVC_ROOT",
                        java.util.Map.of("ko_KR", new kkdugi.app.admin.code.models.AdminCodeLocale("수정된 루트", "")),
                        "Y", null, null, null, null, null, ROOT_PATH, 0, 1)), null));
        assertThat(service.findCodes(ROOT_PATH, "ko_KR")).extracting(Code::getName).containsExactly("수정된 루트");
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
