package kkdugi.app.admin.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;

/**
 * V10__insert_default_menu.sql이 만든 기본 메뉴 트리를 확인한다. ID는 fn_get_serial로
 * 채번한 {@code M{yyyyMMddHHmm}{0000}} 형식이어야 하고, 트리 구조/path/level은
 * {@code AdminMenuService.insertOne}이 만드는 것과 같은 규칙이어야 한다.
 */
@SpringBootTest(classes = KkdugiAdminApplication.class)
class DefaultMenuSeedTest {

    private static final String ID_FORMAT = "M\\d{12}\\d{4}";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Map<String, Object> menuByProgram(String program) {
        return jdbcTemplate.queryForMap(
                "SELECT menu_id, menu_parent_id, menu_lvl, menu_path, sort_seq, use_yn "
                        + "FROM kkdugi_menu_base WHERE menu_pgm = ?", program);
    }

    private Map<String, Object> systemFolder() {
        return jdbcTemplate.queryForMap(
                "SELECT b.menu_id, b.menu_parent_id, b.menu_lvl, b.menu_path, b.sort_seq, b.menu_pgm "
                        + "FROM kkdugi_menu_base b JOIN kkdugi_menu_lang l ON l.menu_id = b.menu_id "
                        + "WHERE l.lang_cd = 'ko_KR' AND l.menu_nm = '시스템관리'");
    }

    @Test
    void home_isRootMenuWithProgram() {
        Map<String, Object> home = menuByProgram("home");

        assertThat((String) home.get("menu_id")).matches(ID_FORMAT);
        assertThat(home.get("menu_parent_id")).isNull();
        assertThat(home.get("menu_lvl")).isEqualTo(0);
        assertThat(home.get("menu_path")).isEqualTo("/" + home.get("menu_id"));
    }

    @Test
    void systemFolder_isRootMenuWithoutProgram() {
        Map<String, Object> system = systemFolder();

        assertThat((String) system.get("menu_id")).matches(ID_FORMAT);
        assertThat(system.get("menu_parent_id")).isNull();
        assertThat(system.get("menu_lvl")).isEqualTo(0);
        assertThat(system.get("menu_pgm")).isNull();
        assertThat(system.get("menu_path")).isEqualTo("/" + system.get("menu_id"));
    }

    @Test
    void adminMenus_areChildrenOfSystemFolderInOrder() {
        Map<String, Object> system = systemFolder();
        List<String> programs = List.of(
                "admin/code", "admin/message", "admin/menu", "admin/authority", "admin/user");

        for (int i = 0; i < programs.size(); i++) {
            Map<String, Object> menu = menuByProgram(programs.get(i));

            assertThat((String) menu.get("menu_id")).matches(ID_FORMAT);
            assertThat(menu.get("menu_parent_id")).isEqualTo(system.get("menu_id"));
            assertThat(menu.get("menu_lvl")).isEqualTo(1);
            assertThat(menu.get("menu_path")).isEqualTo(system.get("menu_path") + "/" + menu.get("menu_id"));
            assertThat(menu.get("sort_seq")).isEqualTo(i + 1);
            assertThat(menu.get("use_yn")).isEqualTo("Y");
        }
    }

    @Test
    void everySeededMenu_hasKoreanAndEnglishNames() {
        List<String> programs = List.of("home", "admin/code", "admin/message", "admin/menu", "admin/authority",
                "admin/user");
        for (String program : programs) {
            Long languages = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kkdugi_menu_lang l JOIN kkdugi_menu_base b ON b.menu_id = l.menu_id "
                            + "WHERE b.menu_pgm = ? AND l.lang_cd IN ('ko_KR', 'en_US')", Long.class, program);
            assertThat(languages).as(program).isEqualTo(2L);
        }
    }
}
