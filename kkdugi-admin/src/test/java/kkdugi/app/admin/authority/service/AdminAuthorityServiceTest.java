package kkdugi.app.admin.authority.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityConflictException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityNotFoundException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityValidationException;
import kkdugi.app.admin.authority.mapper.AdminAuthorityMapper;
import kkdugi.app.admin.authority.models.AdminAuthority;
import kkdugi.app.admin.authority.models.AdminAuthorityCandidate;
import kkdugi.app.admin.authority.models.AdminAuthorityMenu;
import kkdugi.app.admin.authority.models.AdminAuthorityMenuNode;
import kkdugi.app.admin.authority.models.AdminAuthorityParams;
import kkdugi.app.admin.authority.models.AdminAuthorityPersistRequest;
import kkdugi.app.admin.authority.models.AdminAuthorityUser;
import kkdugi.app.admin.authority.models.AuthorityBase;
import kkdugi.core.Constants;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.enums.Rbac;
import kkdugi.core.models.Page;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminAuthorityServiceTest {

    private static final String ROLE_PREFIX = "TEST_AUTHZ_SVC_";
    private static final String USER_1 = "U_TEST_AUTHZ_SVC_1";
    private static final String USER_2 = "U_TEST_AUTHZ_SVC_2";
    private static final String USER_3 = "U_TEST_AUTHZ_SVC_3";
    private static final String MENU_1 = "M_TEST_AUTHZ_SVC_1";
    private static final String MENU_2 = "M_TEST_AUTHZ_SVC_2";

    @Autowired
    private AdminAuthorityService service;

    @Autowired
    private AdminAuthorityMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        wipe();
        insertUser(USER_1, "test_authz_svc_one", "Svc One", "20");
        insertUser(USER_2, "test_authz_svc_two", "Svc Two", "20");
        insertUser(USER_3, "test_authz_svc_three", "Svc Three", "10");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "authz_svc_1", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_parent_id, menu_pgm, sort_seq, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                MENU_2, MENU_1, "authz_svc_2", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "ko_KR", "서비스 테스트 메뉴", "SYSTEM");
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        String ids = "SELECT auth_id FROM kkdugi_auth_base WHERE auth_role_cd LIKE '" + ROLE_PREFIX + "%'";
        jdbcTemplate.update("DELETE FROM kkdugi_auth_menu WHERE auth_id IN (" + ids + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE auth_id IN (" + ids + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_role_cd LIKE '" + ROLE_PREFIX + "%'");
        // 방어용: 버그 있는 구현이 예약된 SYS_ADMIN을 다른 유형으로 만들어 버렸을 때만 걸린다.
        // ROLE/SYS_ADMIN 시드 행은 절대 건드리지 않는다(auth_tp_cd = 'PLAN' 조건).
        String strayIds = "SELECT auth_id FROM kkdugi_auth_base WHERE auth_tp_cd = 'PLAN' AND auth_role_cd = 'SYS_ADMIN'";
        jdbcTemplate.update("DELETE FROM kkdugi_auth_menu WHERE auth_id IN (" + strayIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE auth_id IN (" + strayIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_tp_cd = 'PLAN' AND auth_role_cd = 'SYS_ADMIN'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id LIKE 'M_TEST_AUTHZ_SVC_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id LIKE 'M_TEST_AUTHZ_SVC_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id LIKE 'U_TEST_AUTHZ_SVC_%'");
    }

    private void insertUser(String id, String loginId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, "SYSTEM");
    }

    private static AdminAuthorityPersistRequest request(String roleSuffix, String type,
            List<AdminAuthorityUser> users, List<AdminAuthorityMenu> menus) {
        return new AdminAuthorityPersistRequest(ROLE_PREFIX + roleSuffix, type, "Name " + roleSuffix,
                "remarks", null, users, menus);
    }

    /** 요청용 users 항목 — 이름/이미지는 응답 전용이라 요청에서는 비워 둔다. */
    private static AdminAuthorityUser user(String id, LocalDate start, LocalDate end) {
        return new AdminAuthorityUser(id, null, null, start, end);
    }

    /** {@code codes}에 든 RBAC 코드만 true, 나머지는 false인 메뉴 부여 항목. */
    private static AdminAuthorityMenu grant(String menuId, String... codes) {
        Map<String, Boolean> authorities = new LinkedHashMap<>();
        for (Rbac rbac : Rbac.values()) {
            authorities.put(rbac.getCode(), Arrays.asList(codes).contains(rbac.getCode()));
        }
        return new AdminAuthorityMenu(menuId, authorities);
    }

    private void assertMalformed(AdminAuthorityPersistRequest request) {
        assertThatThrownBy(() -> service.regist(request))
                .isInstanceOf(AdminAuthorityValidationException.class)
                .hasMessage(AdminAuthorityService.ERR_MALFORMED_REQUEST);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    // ---- regist -------------------------------------------------------

    @Test
    void regist_createsAuthority_withGeneratedIdAndDefaults() {
        AdminAuthority created = service.regist(request("BASIC", "ROLE", null, null));

        assertThat(created.getId()).startsWith("A");
        assertThat(created.getRole()).isEqualTo(ROLE_PREFIX + "BASIC");
        assertThat(created.getType()).isEqualTo("ROLE");
        assertThat(created.getName()).isEqualTo("Name BASIC");
        assertThat(created.getRemarks()).isEqualTo("remarks");
        assertThat(created.getUse()).isEqualTo("Y");
        assertThat(created.getUsers()).isEmpty();
    }

    @Test
    void regist_rejectsMalformedRequests() {
        List<AdminAuthorityPersistRequest> bad = List.of(
                new AdminAuthorityPersistRequest(null, "ROLE", "n", null, null, null, null),
                new AdminAuthorityPersistRequest("  ", "ROLE", "n", null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "ROLE", null, null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "BOGUS", "n", null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", null, "n", null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "ROLE", "n", null, "MAYBE", null, null),
                new AdminAuthorityPersistRequest("R".repeat(61), "ROLE", "n", null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "ROLE", "n".repeat(201), null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "ROLE", "n", "r".repeat(1001), null, null, null));

        for (AdminAuthorityPersistRequest request : bad) {
            assertMalformed(request);
        }
    }

    @Test
    void regist_duplicateTypeAndRole_conflicts_butSameRoleInOtherTypeIsAllowed() {
        service.regist(request("DUP", "ROLE", null, null));

        assertThatThrownBy(() -> service.regist(request("DUP", "ROLE", null, null)))
                .isInstanceOf(AdminAuthorityConflictException.class)
                .hasMessage(AdminAuthorityService.ERR_DUPLICATE);

        AdminAuthority plan = service.regist(request("DUP", "PLAN", null, null));
        assertThat(plan.getType()).isEqualTo("PLAN");
    }

    @Test
    void regist_rejectsReservedSysAdminRoleOfAnyOtherType() {
        assertThatThrownBy(() -> service.regist(
                new AdminAuthorityPersistRequest("SYS_ADMIN", "PLAN", "n", null, null, null, null)))
                .isInstanceOf(AdminAuthorityConflictException.class)
                .hasMessage(AdminAuthorityService.ERR_IMMUTABLE);

        assertThat(mapper.findByTypeAndRole(AuthorityType.PLAN, Constants.SYS_ADMIN)).isEmpty();
    }

    @Test
    void regist_rejectsBadUsersAndMenus_andPersistsNothing() {
        LocalDate day = LocalDate.of(2030, 1, 1);

        assertMalformed(request("V1", "ROLE",
                List.of(user(USER_1, day.plusDays(1), day)), null));
        assertMalformed(request("V2", "ROLE",
                List.of(user(USER_1, null, null), user(USER_1, null, null)), null));
        assertMalformed(request("V3", "ROLE", List.of(user(" ", null, null)), null));
        assertMalformed(request("V4", "ROLE", null, List.of(new AdminAuthorityMenu(MENU_1, Map.of("99", true)))));
        assertMalformed(request("V5", "ROLE", null, List.of(new AdminAuthorityMenu(MENU_1, null))));
        assertMalformed(request("V6", "ROLE", null, List.of(grant(MENU_1, "10"), grant(MENU_1, "20"))));

        assertThatThrownBy(() -> service.regist(request("V7", "ROLE",
                List.of(user("U_TEST_AUTHZ_SVC_MISSING", null, null)), null)))
                .isInstanceOf(AdminAuthorityValidationException.class)
                .hasMessage(AdminAuthorityService.ERR_USER_NOT_FOUND);
        assertThatThrownBy(() -> service.regist(request("V8", "ROLE", null,
                List.of(grant("M_TEST_AUTHZ_SVC_MISSING", "10")))))
                .isInstanceOf(AdminAuthorityValidationException.class)
                .hasMessage(AdminAuthorityService.ERR_MENU_NOT_FOUND);

        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_base WHERE auth_role_cd LIKE ?", ROLE_PREFIX + "%"))
                .isZero();
    }

    @Test
    void regist_persistsUsersAndMenus_withDefaultPeriodAndSkipsAllFalseGrants() {
        AdminAuthority created = service.regist(request("FULL", "ROLE",
                List.of(user(USER_1, null, null),
                        user(USER_2, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31))),
                List.of(grant(MENU_1, "10", "20"), grant(MENU_2))));

        assertThat(created.getUsers()).hasSize(2);
        AdminAuthorityUser first = created.getUsers().stream()
                .filter(user -> USER_1.equals(user.getId())).findFirst().orElseThrow();
        assertThat(first.getApplyStartDate()).isEqualTo(LocalDate.now());
        assertThat(first.getApplyEndDate()).isEqualTo(LocalDate.of(9999, 12, 31));
        AdminAuthorityUser second = created.getUsers().stream()
                .filter(user -> USER_2.equals(user.getId())).findFirst().orElseThrow();
        assertThat(second.getApplyStartDate()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(second.getApplyEndDate()).isEqualTo(LocalDate.of(2030, 12, 31));

        assertThat(count("SELECT auth_val FROM kkdugi_auth_menu WHERE auth_id = ? AND menu_id = ?",
                created.getId(), MENU_1)).isEqualTo(Rbac.READ.getValue() | Rbac.WRTE.getValue());
        // MENU_2는 전부 false라 행이 저장되지 않는다.
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isEqualTo(1);
    }

    @Test
    void regist_returnsTheStoredNameAndProfileImage_ignoringWhatTheClientSent() {
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_img_src = ? WHERE user_id = ?",
                "https://img.example/svc-one.png", USER_1);

        AdminAuthority created = service.regist(request("PROFILE", "ROLE",
                List.of(new AdminAuthorityUser(USER_1, "Client Name", "client.png", null, null),
                        user(USER_2, null, null)),
                null));

        AdminAuthorityUser first = created.getUsers().stream()
                .filter(user -> USER_1.equals(user.getId())).findFirst().orElseThrow();
        assertThat(first.getName()).isEqualTo("Svc One");
        assertThat(first.getImage()).isEqualTo("https://img.example/svc-one.png");
        AdminAuthorityUser second = created.getUsers().stream()
                .filter(user -> USER_2.equals(user.getId())).findFirst().orElseThrow();
        assertThat(second.getName()).isEqualTo("Svc Two");
        assertThat(second.getImage()).isNull();

        // 이후 상세 조회도 저장된 값(사용자 테이블)을 그대로 돌려준다.
        assertThat(service.get(created.getId()).getUsers())
                .extracting(AdminAuthorityUser::getName).containsExactlyInAnyOrder("Svc One", "Svc Two");
    }

    // ---- get / search -------------------------------------------------

    @Test
    void get_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.get("A_TEST_AUTHZ_SVC_MISSING"))
                .isInstanceOf(AdminAuthorityNotFoundException.class)
                .hasMessage(AdminAuthorityService.ERR_NOT_FOUND);
    }

    @Test
    void search_normalizesParams_pagesAndOmitsUsers() {
        service.regist(request("P1", "ROLE", null, null));
        service.regist(request("P2", "ROLE", null, null));
        service.regist(request("P3", "ROLE", null, null));

        AdminAuthorityParams params = new AdminAuthorityParams(ROLE_PREFIX, null, null, 0, 2);
        Page<AdminAuthority> page = service.search(params);

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(2);
        assertThat(page.getTotalItems()).isEqualTo(3);
        assertThat(page.getContents()).hasSize(2);
        assertThat(page.getContents().get(0).getRole()).isEqualTo(ROLE_PREFIX + "P1");
        assertThat(page.getContents().get(0).getUsers()).isNull();
    }

    // ---- save ---------------------------------------------------------

    @Test
    void save_updatesFields_andLeavesMappingsAlone_whenUsersAndMenusAreNull() {
        AdminAuthority created = service.regist(request("KEEP", "ROLE",
                List.of(user(USER_1, null, null)), List.of(grant(MENU_1, "10"))));

        AdminAuthority saved = service.save(created.getId(), new AdminAuthorityPersistRequest(
                ROLE_PREFIX + "KEEP", "ROLE", "Renamed", "new remarks", "N", null, null));

        assertThat(saved.getName()).isEqualTo("Renamed");
        assertThat(saved.getRemarks()).isEqualTo("new remarks");
        assertThat(saved.getUse()).isEqualTo("N");
        assertThat(saved.getUsers()).extracting(AdminAuthorityUser::getId).containsExactly(USER_1);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isEqualTo(1);
        AuthorityBase row = mapper.findById(created.getId()).orElseThrow();
        assertThat(row.getUpdatedAt()).isNotNull();
        assertThat(row.getUpdaterId()).isEqualTo("SYSTEM");
    }

    @Test
    void save_keepsExistingUse_whenRequestOmitsIt() {
        AdminAuthority created = service.regist(new AdminAuthorityPersistRequest(
                ROLE_PREFIX + "USE", "ROLE", "n", null, "N", null, null));

        AdminAuthority saved = service.save(created.getId(), new AdminAuthorityPersistRequest(
                ROLE_PREFIX + "USE", "ROLE", "n2", null, null, null, null));

        assertThat(saved.getUse()).isEqualTo("N");
    }

    @Test
    void save_replacesUsersAndMenus_andEmptyListsClearThem() {
        AdminAuthority created = service.regist(request("REPL", "ROLE",
                List.of(user(USER_1, null, null)),
                List.of(grant(MENU_1, "10"), grant(MENU_2, "10"))));

        AdminAuthority replaced = service.save(created.getId(), request("REPL", "ROLE",
                List.of(user(USER_2, null, null)),
                List.of(grant(MENU_1, "10", "20", "30", "40"))));

        assertThat(replaced.getUsers()).extracting(AdminAuthorityUser::getId).containsExactly(USER_2);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isEqualTo(1);
        assertThat(count("SELECT auth_val FROM kkdugi_auth_menu WHERE auth_id = ? AND menu_id = ?",
                created.getId(), MENU_1)).isEqualTo(15);

        AdminAuthority cleared = service.save(created.getId(), request("REPL", "ROLE", List.of(), List.of()));

        assertThat(cleared.getUsers()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isZero();
    }

    @Test
    void save_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.save("A_TEST_AUTHZ_SVC_MISSING", request("NOPE", "ROLE", null, null)))
                .isInstanceOf(AdminAuthorityNotFoundException.class)
                .hasMessage(AdminAuthorityService.ERR_NOT_FOUND);
    }

    @Test
    void save_toAnotherAuthoritysRole_conflicts_butKeepingOwnRoleIsFine() {
        AdminAuthority first = service.regist(request("S1", "ROLE", null, null));
        AdminAuthority second = service.regist(request("S2", "ROLE", null, null));

        assertThatThrownBy(() -> service.save(second.getId(), request("S1", "ROLE", null, null)))
                .isInstanceOf(AdminAuthorityConflictException.class)
                .hasMessage(AdminAuthorityService.ERR_DUPLICATE);

        AdminAuthority sameRole = service.save(second.getId(), request("S2", "ROLE", null, null));
        assertThat(sameRole.getRole()).isEqualTo(ROLE_PREFIX + "S2");
        // regist()가 돌려준 값이 아니라 DB에서 다시 읽어, 실패한 save가 첫 권한을 건드리지 않았음을 증명한다.
        assertThat(mapper.findById(first.getId()).orElseThrow().getRole()).isEqualTo(ROLE_PREFIX + "S1");
    }

    @Test
    void save_rejectsRenamingToReservedSysAdminRole() {
        AdminAuthority created = service.regist(request("RSV", "ROLE", null, null));

        assertThatThrownBy(() -> service.save(created.getId(),
                new AdminAuthorityPersistRequest("SYS_ADMIN", "PLAN", "n", null, null, null, null)))
                .isInstanceOf(AdminAuthorityConflictException.class)
                .hasMessage(AdminAuthorityService.ERR_IMMUTABLE);

        AuthorityBase after = mapper.findById(created.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(ROLE_PREFIX + "RSV");
        assertThat(after.getType()).isEqualTo(AuthorityType.ROLE);
    }

    @Test
    void sysAdmin_cannotBeDeleted_renamedRetypedOrDeactivated() {
        AuthorityBase sysAdmin = mapper.findByTypeAndRole(AuthorityType.ROLE, Constants.SYS_ADMIN).orElseThrow();
        List<AdminAuthorityPersistRequest> forbidden = List.of(
                new AdminAuthorityPersistRequest("OTHER_ROLE", "ROLE", sysAdmin.getName(), null, null, null, null),
                new AdminAuthorityPersistRequest(Constants.SYS_ADMIN, "PLAN", sysAdmin.getName(), null, null, null, null),
                new AdminAuthorityPersistRequest(Constants.SYS_ADMIN, "ROLE", sysAdmin.getName(), null, "N", null, null));

        for (AdminAuthorityPersistRequest request : forbidden) {
            assertThatThrownBy(() -> service.save(sysAdmin.getId(), request))
                    .isInstanceOf(AdminAuthorityConflictException.class)
                    .hasMessage(AdminAuthorityService.ERR_IMMUTABLE);
        }
        assertThatThrownBy(() -> service.delete(sysAdmin.getId()))
                .isInstanceOf(AdminAuthorityConflictException.class)
                .hasMessage(AdminAuthorityService.ERR_IMMUTABLE);

        AuthorityBase after = mapper.findById(sysAdmin.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(Constants.SYS_ADMIN);
        assertThat(after.getType()).isEqualTo(AuthorityType.ROLE);
        assertThat(after.getUse()).isEqualTo("Y");
    }

    @Test
    void sysAdmin_allowsNameAndRemarksChange_keepingRoleTypeAndUse() {
        AuthorityBase original = mapper.findByTypeAndRole(AuthorityType.ROLE, Constants.SYS_ADMIN).orElseThrow();
        String originalName = original.getName();
        String originalRemarks = original.getRemarks();
        String originalUse = original.getUse();
        LocalDateTime originalUpdatedAt = original.getUpdatedAt();
        String originalUpdaterId = original.getUpdaterId();

        try {
            AdminAuthority saved = service.save(original.getId(), new AdminAuthorityPersistRequest(
                    Constants.SYS_ADMIN, "ROLE", "Renamed system administrator", "edited remarks", "Y", null, null));

            assertThat(saved.getName()).isEqualTo("Renamed system administrator");
            assertThat(saved.getRemarks()).isEqualTo("edited remarks");
            assertThat(saved.getRole()).isEqualTo(Constants.SYS_ADMIN);
            assertThat(saved.getType()).isEqualTo("ROLE");
            assertThat(saved.getUse()).isEqualTo("Y");
        } finally {
            // 시드 행이 테스트 전과 정확히 같은 상태로 끝나게 되돌린다(감사 필드 포함).
            AuthorityBase restore = new AuthorityBase(original.getId(), Constants.SYS_ADMIN, AuthorityType.ROLE,
                    originalName, originalRemarks, originalUse);
            restore.setUpdatedAt(originalUpdatedAt);
            restore.setUpdaterId(originalUpdaterId);
            mapper.update(restore);
        }

        AuthorityBase after = mapper.findByTypeAndRole(AuthorityType.ROLE, Constants.SYS_ADMIN).orElseThrow();
        assertThat(after.getId()).isEqualTo(original.getId());
        assertThat(after.getName()).isEqualTo(originalName);
        assertThat(after.getRemarks()).isEqualTo(originalRemarks);
        assertThat(after.getUse()).isEqualTo(originalUse);
    }

    // ---- delete -------------------------------------------------------

    @Test
    void delete_removesAuthorityUsersAndMenus_andMissingIdIsNoOp() {
        AdminAuthority created = service.regist(request("DEL", "ROLE",
                List.of(user(USER_1, null, null)), List.of(grant(MENU_1, "10"))));

        service.delete(created.getId());

        assertThat(mapper.findById(created.getId())).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_auth WHERE auth_id = ?", created.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isZero();
        // 사용자/메뉴 자체는 남아 있다.
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_id = ?", USER_1)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_menu_base WHERE menu_id = ?", MENU_1)).isEqualTo(1);

        service.delete("A_TEST_AUTHZ_SVC_MISSING");
    }

    // ---- candidates / menus -------------------------------------------

    @Test
    void searchCandidates_excludesMappedAndNonNormalUsers_andUnknownAuthorityIsNotFound() {
        AdminAuthority created = service.regist(request("CAND", "ROLE",
                List.of(user(USER_1, null, null)), null));

        List<AdminAuthorityCandidate> candidates = service.searchCandidates(created.getId(), "TEST_AUTHZ_SVC");

        assertThat(candidates).extracting(AdminAuthorityCandidate::getId).containsExactly(USER_2);
        assertThat(candidates.get(0).getUsername()).isEqualTo("test_authz_svc_two");
        assertThat(candidates.get(0).getName()).isEqualTo("Svc Two");
        assertThat(service.searchCandidates(created.getId(), "  ")).isNotEmpty();

        assertThatThrownBy(() -> service.searchCandidates("A_TEST_AUTHZ_SVC_MISSING", null))
                .isInstanceOf(AdminAuthorityNotFoundException.class);
    }

    @Test
    void menus_returnsTreeWithGrantsAndLabels_andUnknownAuthorityIsNotFound() {
        AdminAuthority created = service.regist(request("TREE", "ROLE", null,
                List.of(grant(MENU_1, "10", "20"))));

        List<AdminAuthorityMenuNode> tree = service.menus(created.getId());

        AdminAuthorityMenuNode root = tree.stream().filter(node -> MENU_1.equals(node.getId()))
                .findFirst().orElseThrow();
        assertThat(root.getAuthorities())
                .containsEntry("10", true).containsEntry("20", true)
                .containsEntry("30", false).containsEntry("40", false);
        assertThat(root.getLocale()).containsKey("ko_KR");
        assertThat(root.getLocale().get("ko_KR").getLabel()).isEqualTo("서비스 테스트 메뉴");
        assertThat(root.getProgram()).isEqualTo("authz_svc_1");
        assertThat(root.getChildren()).hasSize(1);
        AdminAuthorityMenuNode child = root.getChildren().get(0);
        assertThat(child.getId()).isEqualTo(MENU_2);
        assertThat(child.getParentId()).isEqualTo(MENU_1);
        assertThat(child.getAuthorities()).containsEntry("10", false);

        assertThatThrownBy(() -> service.menus("A_TEST_AUTHZ_SVC_MISSING"))
                .isInstanceOf(AdminAuthorityNotFoundException.class);
    }
}
