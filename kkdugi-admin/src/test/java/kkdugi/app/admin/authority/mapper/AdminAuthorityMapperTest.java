package kkdugi.app.admin.authority.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.authority.models.AuthorityBase;
import kkdugi.app.admin.authority.models.AuthorityMenu;
import kkdugi.app.admin.authority.models.AuthorityMenuLang;
import kkdugi.app.admin.authority.models.AuthorityMenuRow;
import kkdugi.app.admin.authority.models.AuthorityUser;
import kkdugi.app.admin.authority.models.CandidateUser;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.enums.UserStatus;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminAuthorityMapperTest {

    private static final String ROLE_PREFIX = "TEST_AUTHZ_MAPPER_";
    private static final String AUTH_1 = "A_TEST_AUTHZ_MAPPER_1";
    private static final String AUTH_2 = "A_TEST_AUTHZ_MAPPER_2";
    private static final String AUTH_3 = "A_TEST_AUTHZ_MAPPER_3";
    private static final String USER_1 = "U_TEST_AUTHZ_MAPPER_1";
    private static final String USER_2 = "U_TEST_AUTHZ_MAPPER_2";
    private static final String USER_3 = "U_TEST_AUTHZ_MAPPER_3";
    private static final String MENU_1 = "M_TEST_AUTHZ_MAPPER_1";
    private static final String MENU_2 = "M_TEST_AUTHZ_MAPPER_2";

    @Autowired
    private AdminAuthorityMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        wipe();
        insertUser(USER_1, "test_authz_mapper_one", "Mapper One", "20");
        insertUser(USER_2, "test_authz_mapper_two", "Mapper Two", "20");
        insertUser(USER_3, "test_authz_mapper_three", "Mapper Three", "10");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "authz_pgm_1", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_parent_id, menu_pgm, sort_seq, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                MENU_2, MENU_1, "authz_pgm_2", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "ko_KR", "테스트 메뉴", "SYSTEM");
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        jdbcTemplate.update("DELETE FROM kkdugi_auth_menu WHERE auth_id LIKE 'A_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE auth_id LIKE 'A_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_id LIKE 'A_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id LIKE 'M_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id LIKE 'M_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id LIKE 'U_TEST_AUTHZ_MAPPER_%'");
    }

    private void insertUser(String id, String loginId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, "SYSTEM");
    }

    private void insertAuthority(String id, String role, AuthorityType type) {
        AuthorityBase row = new AuthorityBase(id, role, type, "Name " + role, "remarks", "Y");
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatorId("SYSTEM");
        mapper.insert(row);
    }

    private AuthorityUser userRow(String userId, String authId, LocalDate start, LocalDate end) {
        AuthorityUser row = new AuthorityUser(userId, authId, start, end);
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatorId("SYSTEM");
        return row;
    }

    private AuthorityMenu menuRow(String authId, String menuId, int rbac) {
        AuthorityMenu row = new AuthorityMenu(authId, menuId, rbac);
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatorId("SYSTEM");
        return row;
    }

    @Test
    void insertAndFindById_roundTripsEnumAndFields() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "A", AuthorityType.PLAN);

        AuthorityBase found = mapper.findById(AUTH_1).orElseThrow();

        assertThat(found.getRole()).isEqualTo(ROLE_PREFIX + "A");
        assertThat(found.getType()).isEqualTo(AuthorityType.PLAN);
        assertThat(found.getName()).isEqualTo("Name " + ROLE_PREFIX + "A");
        assertThat(found.getRemarks()).isEqualTo("remarks");
        assertThat(found.getUse()).isEqualTo("Y");
        assertThat(found.getCreatorId()).isEqualTo("SYSTEM");
        assertThat(mapper.findById("A_TEST_AUTHZ_MAPPER_MISSING")).isEmpty();
    }

    @Test
    void insert_sameTypeAndRole_violatesUniqueConstraint_butOtherTypeIsAllowed() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "DUP", AuthorityType.ROLE);

        assertThatThrownBy(() -> insertAuthority(AUTH_2, ROLE_PREFIX + "DUP", AuthorityType.ROLE))
                .isInstanceOf(DuplicateKeyException.class);

        insertAuthority(AUTH_3, ROLE_PREFIX + "DUP", AuthorityType.PLAN);
        assertThat(mapper.findByTypeAndRole(AuthorityType.ROLE, ROLE_PREFIX + "DUP").orElseThrow().getId())
                .isEqualTo(AUTH_1);
        assertThat(mapper.findByTypeAndRole(AuthorityType.PLAN, ROLE_PREFIX + "DUP").orElseThrow().getId())
                .isEqualTo(AUTH_3);
        assertThat(mapper.findByTypeAndRole(AuthorityType.PLAN, ROLE_PREFIX + "NONE")).isEmpty();
    }

    @Test
    void search_filtersByRoleAndType_pagesAndReportsTotalSize() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "C", AuthorityType.ROLE);
        insertAuthority(AUTH_2, ROLE_PREFIX + "A", AuthorityType.ROLE);
        insertAuthority(AUTH_3, ROLE_PREFIX + "B", AuthorityType.PLAN);

        List<AuthorityBase> firstPage = mapper.search(ROLE_PREFIX, null, null, 0, 2);
        assertThat(firstPage).extracting(AuthorityBase::getRole)
                .containsExactly(ROLE_PREFIX + "A", ROLE_PREFIX + "B");
        assertThat(firstPage.get(0).getTotalSize()).isEqualTo(3L);

        List<AuthorityBase> secondPage = mapper.search(ROLE_PREFIX, null, null, 2, 2);
        assertThat(secondPage).extracting(AuthorityBase::getRole).containsExactly(ROLE_PREFIX + "C");

        List<AuthorityBase> plans = mapper.search(ROLE_PREFIX, "PLAN", null, 0, 10);
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getType()).isEqualTo(AuthorityType.PLAN);

        List<AuthorityBase> byName = mapper.search(null, null, "Name " + ROLE_PREFIX + "A", 0, 10);
        assertThat(byName).extracting(AuthorityBase::getId).containsExactly(AUTH_2);
    }

    @Test
    void update_changesFieldsAndAuditColumns() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "OLD", AuthorityType.ROLE);

        AuthorityBase changed = new AuthorityBase(AUTH_1, ROLE_PREFIX + "NEW", AuthorityType.PLAN, "New name", null, "N");
        changed.setUpdatedAt(LocalDateTime.now());
        changed.setUpdaterId("SYSTEM");
        assertThat(mapper.update(changed)).isEqualTo(1);

        AuthorityBase found = mapper.findById(AUTH_1).orElseThrow();
        assertThat(found.getRole()).isEqualTo(ROLE_PREFIX + "NEW");
        assertThat(found.getType()).isEqualTo(AuthorityType.PLAN);
        assertThat(found.getName()).isEqualTo("New name");
        assertThat(found.getRemarks()).isNull();
        assertThat(found.getUse()).isEqualTo("N");
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getUpdaterId()).isEqualTo("SYSTEM");
    }

    @Test
    void userMapping_upsertUpdatesPeriod_deleteNotInKeepsListed_deleteAllClears() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "U", AuthorityType.ROLE);
        LocalDate start = LocalDate.of(2030, 1, 1);

        mapper.upsertUser(userRow(USER_1, AUTH_1, start, LocalDate.of(2030, 6, 30)));
        mapper.upsertUser(userRow(USER_2, AUTH_1, start, LocalDate.of(9999, 12, 31)));
        mapper.upsertUser(userRow(USER_1, AUTH_1, start, LocalDate.of(2031, 6, 30)));

        List<AuthorityUser> rows = mapper.findUsersByAuthorityId(AUTH_1);
        assertThat(rows).extracting(AuthorityUser::getUserId).containsExactly(USER_1, USER_2);
        assertThat(rows.get(0).getApplyStartDate()).isEqualTo(start);
        assertThat(rows.get(0).getApplyEndDate()).isEqualTo(LocalDate.of(2031, 6, 30));
        assertThat(rows.get(0).getUpdatedAt()).isNotNull();
        assertThat(rows.get(1).getUpdatedAt()).isNull();

        assertThat(mapper.deleteUsersNotIn(AUTH_1, List.of(USER_2))).isEqualTo(1);
        assertThat(mapper.findUsersByAuthorityId(AUTH_1)).extracting(AuthorityUser::getUserId)
                .containsExactly(USER_2);

        assertThat(mapper.deleteUsersByAuthorityId(AUTH_1)).isEqualTo(1);
        assertThat(mapper.findUsersByAuthorityId(AUTH_1)).isEmpty();
    }

    @Test
    void findUsersByAuthorityId_includesTheUsersNameAndProfileImage() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "N", AuthorityType.ROLE);
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_img_src = ? WHERE user_id = ?",
                "https://img.example/one.png", USER_1);
        LocalDate start = LocalDate.of(2030, 1, 1);
        mapper.upsertUser(userRow(USER_1, AUTH_1, start, LocalDate.of(9999, 12, 31)));
        mapper.upsertUser(userRow(USER_2, AUTH_1, start, LocalDate.of(9999, 12, 31)));

        List<AuthorityUser> rows = mapper.findUsersByAuthorityId(AUTH_1);

        assertThat(rows).extracting(AuthorityUser::getUserName).containsExactly("Mapper One", "Mapper Two");
        assertThat(rows.get(0).getUserImage()).isEqualTo("https://img.example/one.png");
        assertThat(rows.get(1).getUserImage()).isNull();
    }

    @Test
    void findExistingUserIds_returnsOnlyExistingIds() {
        assertThat(mapper.findExistingUserIds(List.of(USER_1, USER_2, "U_TEST_AUTHZ_MAPPER_MISSING")))
                .containsExactlyInAnyOrder(USER_1, USER_2);
    }

    @Test
    void searchCandidates_excludesMappedAndNonNormalUsers_andMatchesCaseInsensitively() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "C", AuthorityType.ROLE);
        mapper.upsertUser(userRow(USER_1, AUTH_1, LocalDate.of(2030, 1, 1), LocalDate.of(9999, 12, 31)));

        List<CandidateUser> candidates = mapper.searchCandidates(AUTH_1, UserStatus.NORM, "test_authz_mapper", 50);
        assertThat(candidates).extracting(CandidateUser::getId).containsExactly(USER_2);
        assertThat(candidates.get(0).getUsername()).isEqualTo("test_authz_mapper_two");
        assertThat(candidates.get(0).getName()).isEqualTo("Mapper Two");

        assertThat(mapper.searchCandidates(AUTH_1, UserStatus.NORM, "MAPPER TWO", 50))
                .extracting(CandidateUser::getId).containsExactly(USER_2);
        assertThat(mapper.searchCandidates(AUTH_1, UserStatus.NORM, "MAPPER_TWO@EXAMPLE", 50))
                .extracting(CandidateUser::getId).containsExactly(USER_2);
        assertThat(mapper.searchCandidates(AUTH_1, UserStatus.NORM, "no-such-user-xyz", 50)).isEmpty();
        assertThat(mapper.searchCandidates(AUTH_1, UserStatus.PEND, "test_authz_mapper", 50))
                .extracting(CandidateUser::getId).containsExactly(USER_3);
    }

    @Test
    void menuMapping_grantJoin_upsert_deleteNotIn_deleteAll() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "M", AuthorityType.ROLE);

        mapper.upsertMenu(menuRow(AUTH_1, MENU_1, 3));
        mapper.upsertMenu(menuRow(AUTH_1, MENU_1, 7));

        List<AuthorityMenuRow> rows = mapper.findMenusWithGrant(AUTH_1);
        AuthorityMenuRow root = rows.stream().filter(r -> MENU_1.equals(r.getId())).findFirst().orElseThrow();
        AuthorityMenuRow child = rows.stream().filter(r -> MENU_2.equals(r.getId())).findFirst().orElseThrow();
        assertThat(root.getRbac()).isEqualTo(7);
        assertThat(root.getProgram()).isEqualTo("authz_pgm_1");
        assertThat(root.getParentId()).isNull();
        assertThat(child.getRbac()).isEqualTo(0);
        assertThat(child.getParentId()).isEqualTo(MENU_1);
        assertThat(child.getUse()).isEqualTo("Y");

        List<AuthorityMenuLang> langs = mapper.findAllMenuLangs();
        assertThat(langs).anySatisfy(lang -> {
            assertThat(lang.getMenuId()).isEqualTo(MENU_1);
            assertThat(lang.getLangCode()).isEqualTo("ko_KR");
            assertThat(lang.getLabel()).isEqualTo("테스트 메뉴");
        });

        assertThat(mapper.findExistingMenuIds(List.of(MENU_1, MENU_2, "M_TEST_AUTHZ_MAPPER_MISSING")))
                .containsExactlyInAnyOrder(MENU_1, MENU_2);

        mapper.upsertMenu(menuRow(AUTH_1, MENU_2, 1));
        assertThat(mapper.deleteMenusNotIn(AUTH_1, List.of(MENU_2))).isEqualTo(1);
        assertThat(mapper.deleteMenusByAuthorityId(AUTH_1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", Integer.class, AUTH_1)).isZero();
    }

    @Test
    void deleteById_removesRow() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "D", AuthorityType.ROLE);

        assertThat(mapper.deleteById(AUTH_1)).isEqualTo(1);
        assertThat(mapper.findById(AUTH_1)).isEmpty();
    }
}
