package kkdugi.app.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.user.exceptions.AdminUserConflictException;
import kkdugi.app.admin.user.exceptions.AdminUserNotFoundException;
import kkdugi.app.admin.user.exceptions.AdminUserValidationException;
import kkdugi.app.admin.user.models.AdminUser;
import kkdugi.app.admin.user.models.AdminUserAuthoritiesRequest;
import kkdugi.app.admin.user.models.AdminUserAuthority;
import kkdugi.app.admin.user.models.AdminUserChangeStatusRequest;
import kkdugi.app.admin.user.models.AdminUserIds;
import kkdugi.app.admin.user.models.AdminUserParams;
import kkdugi.app.admin.user.models.AdminUserPersistRequest;
import kkdugi.core.models.Page;
import kkdugi.core.security.authentication.SessionAuthentication;
import kkdugi.core.security.models.SessionUser;
import kkdugi.support.TestAuthorization;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminUserServiceTest {

    private static final String USER_1 = "U_TEST_USR_SVC_1";
    private static final String USER_2 = "U_TEST_USR_SVC_2";

    @Autowired
    private AdminUserService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        wipe();
        insertUser(USER_1, "test_usr_svc_one", "Svc One", "20");
        insertUser(USER_2, "test_usr_svc_two", "Svc Two", "10");
        serviceLogger = (Logger) LoggerFactory.getLogger(AdminUserService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        serviceLogger.detachAppender(appender);
        wipe();
    }

    /** 등록 API가 만든(서버 채번) 사용자와 테스트 권한까지 로그인 ID/role 접두사로 정리한다. */
    private void wipe() {
        String userIds = "SELECT user_id FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_svc_%'";
        String authIds = "SELECT auth_id FROM kkdugi_auth_base WHERE auth_role_cd LIKE 'TEST_USR_SVC_%'";
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id IN (" + userIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id IN (" + userIds + ") OR auth_id IN (" + authIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_role_cd LIKE 'TEST_USR_SVC_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_svc_%'");
    }

    private void insertUser(String id, String loginId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, "SYSTEM");
    }

    private void insertAuthority(String id, String role) {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                + "VALUES (?, ?, 'ROLE', ?, 'SYSTEM')", id, role, "Name " + role);
    }

    /** 요청 항목 — 이름/role 등은 응답 전용이라 비워 둔다. */
    private static AdminUserAuthority item(String authorityId, LocalDate start, LocalDate end) {
        return new AdminUserAuthority(authorityId, null, null, null, null, null, start, end);
    }

    private static void assertAuthorityMalformed(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private static AdminUserPersistRequest request(String username, String name, String email, String status) {
        return new AdminUserPersistRequest(username, name, "memo", "img.png", email, status);
    }

    /** TODO(mail) 로그에서 (userId → 평문 임시 비밀번호)를 꺼낸다. */
    private Map<String, String> loggedTemporaryPasswords() {
        Map<String, String> result = new LinkedHashMap<>();
        for (ILoggingEvent event : appender.list) {
            if (event.getFormattedMessage().contains("TODO(mail)")) {
                Object[] args = event.getArgumentArray();
                result.put((String) args[0], (String) args[1]);
            }
        }
        return result;
    }

    private String storedPassword(String userId) {
        return jdbcTemplate.queryForObject("SELECT user_pwd FROM kkdugi_user_base WHERE user_id = ?", String.class, userId);
    }

    @Test
    void search_normalizesPaging_andFiltersByLoginId() {
        Page<AdminUser> page = service.search(new AdminUserParams("test_usr_svc_", null, null, 0, 0));

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getTotalItems()).isEqualTo(2L);
        assertThat(page.getContents()).extracting(AdminUser::getId).containsExactlyInAnyOrder(USER_1, USER_2);
    }

    @Test
    void search_filtersByStatusCode() {
        Page<AdminUser> page = service.search(new AdminUserParams("test_usr_svc_", null, "10", 1, 10));

        assertThat(page.getContents()).extracting(AdminUser::getId).containsExactly(USER_2);
        assertThat(page.getContents().get(0).getStatus()).isEqualTo("10");
    }

    @Test
    void search_returnsAnEmptyPageWhenNothingMatches() {
        Page<AdminUser> page = service.search(new AdminUserParams("test_usr_svc_nomatch", null, null, 1, 10));

        assertThat(page.getTotalItems()).isZero();
        assertThat(page.getContents()).isEmpty();
    }

    @Test
    void search_rejectsUnknownStatus() {
        assertThatThrownBy(() -> service.search(new AdminUserParams(null, null, "99", 1, 10)))
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
    }

    @Test
    void get_returnsTheUser_andThrowsNotFoundForUnknownId() {
        AdminUser user = service.get(USER_1);

        assertThat(user.getUsername()).isEqualTo("test_usr_svc_one");
        assertThat(user.getStatus()).isEqualTo("20");
        assertThat(user.getPasswordStatus()).isNull();
        assertThatThrownBy(() -> service.get("U_TEST_USR_SVC_MISSING"))
                .isInstanceOf(AdminUserNotFoundException.class)
                .hasMessage(AdminUserService.ERR_NOT_FOUND);
    }

    @Test
    void regist_createsUserWithSerialId_encodedTemporaryPassword_andLogsIt() {
        AdminUser created = service.regist(request("test_usr_svc_new", "New User", "test_usr_svc_new@example.com", null));

        assertThat(created.getId()).matches("U\\d{12}\\d{4}");
        assertThat(created.getStatus()).isEqualTo("20");
        assertThat(created.getPasswordStatus()).isEqualTo("10");
        assertThat(created.getRemarks()).isEqualTo("memo");
        assertThat(created.getImage()).isEqualTo("img.png");

        String stored = storedPassword(created.getId());
        assertThat(stored).startsWith("{bcrypt}");
        Map<String, String> logged = loggedTemporaryPasswords();
        assertThat(logged).containsOnlyKeys(created.getId());
        assertThat(passwordEncoder.matches(logged.get(created.getId()), stored)).isTrue();
    }

    @Test
    void regist_appliesTheGivenStatus() {
        AdminUser created = service.regist(request("test_usr_svc_pend", "Pending", "test_usr_svc_pend@example.com", "10"));

        assertThat(created.getStatus()).isEqualTo("10");
    }

    @Test
    void regist_rejectsMalformedRequests_andWritesNothing() {
        String tooLong = "x".repeat(101);
        for (AdminUserPersistRequest bad : java.util.List.of(
                request(null, "Name", "test_usr_svc_a@example.com", null),
                request("  ", "Name", "test_usr_svc_a@example.com", null),
                request("test_usr_svc_a", null, "test_usr_svc_a@example.com", null),
                request("test_usr_svc_a", "Name", null, null),
                request(tooLong, "Name", "test_usr_svc_a@example.com", null),
                request("test_usr_svc_a", "Name", "test_usr_svc_a@example.com", "99"))) {
            assertThatThrownBy(() -> service.regist(bad))
                    .isInstanceOf(AdminUserValidationException.class)
                    .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
        }
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_svc_a%'")).isZero();
        assertThat(loggedTemporaryPasswords()).isEmpty();
    }

    @Test
    void regist_rejectsDuplicateUsernameAndEmail() {
        assertThatThrownBy(() -> service.regist(request("test_usr_svc_one", "Dup", "test_usr_svc_dup@example.com", null)))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_DUPLICATE_USERNAME);
        assertThatThrownBy(() -> service.regist(request("test_usr_svc_dup", "Dup", "test_usr_svc_one@example.com", null)))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_DUPLICATE_EMAIL);
    }

    @Test
    void save_updatesEditableFields_keepsStatusWhenOmitted_andNeverTouchesThePassword() {
        // USER_2의 상태는 "10"(PEND) — 기본값(NORM "20")과 달라야 "유지"와 "기본값으로 초기화"를 구분할 수 있다.
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_pwd = 'stored-hash' WHERE user_id = ?", USER_2);

        AdminUser saved = service.save(USER_2,
                new AdminUserPersistRequest(null, "Renamed", "new memo", null, "test_usr_svc_renamed@example.com", null));

        assertThat(saved.getName()).isEqualTo("Renamed");
        assertThat(saved.getRemarks()).isEqualTo("new memo");
        assertThat(saved.getImage()).isNull();
        assertThat(saved.getEmail()).isEqualTo("test_usr_svc_renamed@example.com");
        assertThat(saved.getUsername()).isEqualTo("test_usr_svc_two");
        assertThat(saved.getStatus()).isEqualTo("10");
        assertThat(storedPassword(USER_2)).isEqualTo("stored-hash");
    }

    @Test
    void save_appliesAGivenStatus_andAllowsResavingTheOwnEmail() {
        AdminUser saved = service.save(USER_1,
                request("test_usr_svc_one", "Svc One", "test_usr_svc_one@example.com", "30"));

        assertThat(saved.getStatus()).isEqualTo("30");
    }

    @Test
    void save_rejectsChangingTheUsername() {
        assertThatThrownBy(() -> service.save(USER_1, request("test_usr_svc_other", "Svc One", "test_usr_svc_one@example.com", null)))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_IMMUTABLE);
        assertThat(service.get(USER_1).getUsername()).isEqualTo("test_usr_svc_one");
    }

    @Test
    void resetPassword_replacesEveryPassword_marksItNew_andLogsEachOne() {
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_pwd = 'old-hash', pwd_stat_cd = '30' WHERE user_id IN (?, ?)",
                USER_1, USER_2);

        service.resetPassword(new AdminUserIds(List.of(USER_1, USER_2, USER_1)));

        Map<String, String> logged = loggedTemporaryPasswords();
        assertThat(logged).containsOnlyKeys(USER_1, USER_2);
        for (String id : List.of(USER_1, USER_2)) {
            String stored = storedPassword(id);
            assertThat(stored).startsWith("{bcrypt}");
            assertThat(passwordEncoder.matches(logged.get(id), stored)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT pwd_stat_cd FROM kkdugi_user_base WHERE user_id = ?", String.class, id))
                    .isEqualTo("10");
            assertThat(service.get(id).getLastChangePasswordAt()).isNotNull();
        }
    }

    @Test
    void resetPassword_unknownId_throwsNotFound_andChangesNothing() {
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_pwd = 'old-hash' WHERE user_id = ?", USER_1);

        assertThatThrownBy(() -> service.resetPassword(new AdminUserIds(List.of(USER_1, "U_TEST_USR_SVC_MISSING"))))
                .isInstanceOf(AdminUserNotFoundException.class)
                .hasMessage(AdminUserService.ERR_NOT_FOUND);

        assertThat(storedPassword(USER_1)).isEqualTo("old-hash");
        assertThat(loggedTemporaryPasswords()).isEmpty();
    }

    @Test
    void resetPassword_andChangeStatus_rejectEmptyOrBlankIds() {
        for (List<String> bad : java.util.Arrays.asList(null, List.<String>of(), List.of(" "))) {
            assertThatThrownBy(() -> service.resetPassword(new AdminUserIds(bad)))
                    .isInstanceOf(AdminUserValidationException.class)
                    .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
            assertThatThrownBy(() -> service.changeStatus(new AdminUserChangeStatusRequest(bad, "20")))
                    .isInstanceOf(AdminUserValidationException.class)
                    .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
        }
    }

    @Test
    void changeStatus_updatesEveryUser_andIgnoresDuplicateIds() {
        service.changeStatus(new AdminUserChangeStatusRequest(List.of(USER_1, USER_2, USER_2), "50"));

        assertThat(service.get(USER_1).getStatus()).isEqualTo("50");
        assertThat(service.get(USER_2).getStatus()).isEqualTo("50");
    }

    @Test
    void changeStatus_unknownIdOrStatus_changesNothing() {
        assertThatThrownBy(() -> service.changeStatus(new AdminUserChangeStatusRequest(List.of(USER_1, "U_TEST_USR_SVC_MISSING"), "50")))
                .isInstanceOf(AdminUserNotFoundException.class);
        assertThatThrownBy(() -> service.changeStatus(new AdminUserChangeStatusRequest(List.of(USER_1), "99")))
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
        assertThatThrownBy(() -> service.changeStatus(new AdminUserChangeStatusRequest(List.of(USER_1), null)))
                .isInstanceOf(AdminUserValidationException.class);

        assertThat(service.get(USER_1).getStatus()).isEqualTo("20");
    }

    @Test
    void delete_removesTheUserItsSessionsAndItsAuthorityMappings_butNotTheAuthority() {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                + "VALUES ('A_TEST_USR_SVC_1', 'TEST_USR_SVC_ROLE', 'ROLE', 'Svc Role', 'SYSTEM')");
        jdbcTemplate.update("INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                + "VALUES (?, 'A_TEST_USR_SVC_1', CURRENT_DATE, DATE '9999-12-31', 'SYSTEM')", USER_1);
        jdbcTemplate.update("INSERT INTO kkdugi_session (sess_id, user_id, exp_dtm) VALUES ('S_TEST_USR_SVC_1', ?, ?)",
                USER_1, java.sql.Timestamp.valueOf("2099-01-01 00:00:00"));

        service.delete(USER_1);

        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_id = ?", USER_1)).isZero();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_auth WHERE user_id = ?", USER_1)).isZero();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_session WHERE sess_id = 'S_TEST_USR_SVC_1'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_base WHERE auth_id = 'A_TEST_USR_SVC_1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_id = ?", USER_2)).isEqualTo(1);
    }

    @Test
    void delete_isIdempotentForAnAlreadyMissingUser() {
        service.delete("U_TEST_USR_SVC_MISSING");
    }

    @Test
    void delete_rejectsDeletingTheCurrentSessionUser() {
        SessionAuthentication authentication = TestAuthorization.session("admin/user", 15, "SYS_ADMIN");
        ((SessionUser) authentication.getPrincipal()).setId(USER_1);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        assertThatThrownBy(() -> service.delete(USER_1))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_SELF_DELETE);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_id = ?", USER_1)).isEqualTo(1);
    }

    @Test
    void save_rejectsAnotherUsersEmail_unknownUser_andMalformedBody() {
        assertThatThrownBy(() -> service.save(USER_1, request(null, "Svc One", "test_usr_svc_two@example.com", null)))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_DUPLICATE_EMAIL);
        assertThatThrownBy(() -> service.save("U_TEST_USR_SVC_MISSING", request(null, "Name", "test_usr_svc_x@example.com", null)))
                .isInstanceOf(AdminUserNotFoundException.class)
                .hasMessage(AdminUserService.ERR_NOT_FOUND);
        assertThatThrownBy(() -> service.save(USER_1, request(null, "", "test_usr_svc_x@example.com", null)))
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
    }

    @Test
    void saveAuthorities_insertsWithDefaultDates_updatesPeriods_deletesMappings_andReturnsTheList() {
        insertAuthority("A_TEST_USR_SVC_1", "TEST_USR_SVC_A");
        insertAuthority("A_TEST_USR_SVC_2", "TEST_USR_SVC_B");
        insertAuthority("A_TEST_USR_SVC_3", "TEST_USR_SVC_C");

        List<AdminUserAuthority> first = service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(
                List.of(item("A_TEST_USR_SVC_1", null, null),
                        item("A_TEST_USR_SVC_2", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31))),
                null, null));

        assertThat(first).extracting(AdminUserAuthority::getId).containsExactly("A_TEST_USR_SVC_1", "A_TEST_USR_SVC_2");
        assertThat(first.get(0).getApplyStartDate()).isEqualTo(LocalDate.now());
        assertThat(first.get(0).getApplyEndDate()).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(first.get(0).getRole()).isEqualTo("TEST_USR_SVC_A");
        assertThat(first.get(0).getType()).isEqualTo("ROLE");
        assertThat(first.get(0).getUse()).isEqualTo("Y");

        List<AdminUserAuthority> second = service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(
                List.of(item("A_TEST_USR_SVC_3", null, null)),
                List.of(item("A_TEST_USR_SVC_2", LocalDate.of(2031, 1, 1), LocalDate.of(2031, 3, 31))),
                List.of(item("A_TEST_USR_SVC_1", null, null), item("A_TEST_USR_SVC_MISSING_MAPPING", null, null))));

        assertThat(second).extracting(AdminUserAuthority::getId).containsExactly("A_TEST_USR_SVC_2", "A_TEST_USR_SVC_3");
        assertThat(second.get(0).getApplyStartDate()).isEqualTo(LocalDate.of(2031, 1, 1));
        assertThat(second.get(0).getApplyEndDate()).isEqualTo(LocalDate.of(2031, 3, 31));
        assertThat(service.authorities(USER_1)).hasSize(2);
        assertThat(service.authorities(USER_2)).isEmpty();
    }

    @Test
    void saveAuthorities_anEmptyRequestChangesNothing() {
        insertAuthority("A_TEST_USR_SVC_1", "TEST_USR_SVC_A");
        service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(List.of(item("A_TEST_USR_SVC_1", null, null)), null, null));

        assertThat(service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(null, null, null))).hasSize(1);
    }

    @Test
    void saveAuthorities_unknownAuthority_throwsAuthorityNotFound_andWritesNothing() {
        insertAuthority("A_TEST_USR_SVC_1", "TEST_USR_SVC_A");

        assertThatThrownBy(() -> service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(
                List.of(item("A_TEST_USR_SVC_1", null, null), item("A_TEST_USR_SVC_MISSING", null, null)), null, null)))
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_AUTHORITY_NOT_FOUND);

        assertThat(service.authorities(USER_1)).isEmpty();
    }

    @Test
    void saveAuthorities_rejectsMalformedItems() {
        insertAuthority("A_TEST_USR_SVC_1", "TEST_USR_SVC_A");
        AdminUserAuthority ok = item("A_TEST_USR_SVC_1", null, null);

        // 빈 id, null 항목, 같은 id가 두 번(같은 목록/다른 목록), 적용기간 역전
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1,
                new AdminUserAuthoritiesRequest(List.of(item(" ", null, null)), null, null)));
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1,
                new AdminUserAuthoritiesRequest(java.util.Arrays.asList((AdminUserAuthority) null), null, null)));
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1,
                new AdminUserAuthoritiesRequest(List.of(ok, ok), null, null)));
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1,
                new AdminUserAuthoritiesRequest(List.of(ok), null, List.of(ok))));
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(
                List.of(item("A_TEST_USR_SVC_1", LocalDate.of(2030, 2, 1), LocalDate.of(2030, 1, 1))), null, null)));

        assertThat(service.authorities(USER_1)).isEmpty();
    }

    @Test
    void authorities_andSaveAuthorities_throwNotFoundForAnUnknownUser() {
        assertThatThrownBy(() -> service.authorities("U_TEST_USR_SVC_MISSING"))
                .isInstanceOf(AdminUserNotFoundException.class)
                .hasMessage(AdminUserService.ERR_NOT_FOUND);
        assertThatThrownBy(() -> service.saveAuthorities("U_TEST_USR_SVC_MISSING", new AdminUserAuthoritiesRequest(null, null, null)))
                .isInstanceOf(AdminUserNotFoundException.class);
    }

    private static DuplicateKeyException duplicateKey(String constraint) {
        return new DuplicateKeyException("insert failed",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    @Test
    void duplicateOf_mapsTheEmailConstraintToADuplicateEmailConflict() {
        RuntimeException result = AdminUserService.duplicateOf(duplicateKey("kkdugi_user_base_udx_email"));

        assertThat(result).isInstanceOf(AdminUserConflictException.class);
        assertThat(((AdminUserConflictException) result).getCode()).isEqualTo(AdminUserService.ERR_DUPLICATE_EMAIL);
    }

    @Test
    void duplicateOf_mapsTheLoginIdConstraintToADuplicateUsernameConflict() {
        RuntimeException result = AdminUserService.duplicateOf(duplicateKey("kkdugi_user_base_udx_login_id"));

        assertThat(result).isInstanceOf(AdminUserConflictException.class);
        assertThat(((AdminUserConflictException) result).getCode()).isEqualTo(AdminUserService.ERR_DUPLICATE_USERNAME);
    }

    /** PK 등 다른 제약 위반은 사용자 오류로 둔갑시키지 않고 원래 예외를 그대로 돌려준다. */
    @Test
    void duplicateOf_returnsTheOriginalExceptionForAnyOtherConstraint() {
        DuplicateKeyException pk = duplicateKey("kkdugi_user_base_pkey");

        assertThat(AdminUserService.duplicateOf(pk)).isSameAs(pk);
    }
}
