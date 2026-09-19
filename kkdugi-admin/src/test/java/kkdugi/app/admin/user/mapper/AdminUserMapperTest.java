package kkdugi.app.admin.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.user.models.UserAuthority;
import kkdugi.app.admin.user.models.UserBase;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.enums.PasswordStatus;
import kkdugi.core.enums.UserStatus;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminUserMapperTest {

    private static final String USER_1 = "U_TEST_USR_MAPPER_1";
    private static final String USER_2 = "U_TEST_USR_MAPPER_2";
    private static final String USER_3 = "U_TEST_USR_MAPPER_3";

    @Autowired
    private AdminUserMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        wipe();
        // reg_dtm을 고정해 "최신 등록순" 정렬을 결정적으로 검증한다.
        insertUser(USER_1, "test_usr_mapper_alpha", "Alpha Kim", "20", "2030-01-01 00:00:01");
        insertUser(USER_2, "test_usr_mapper_beta", "Beta Lee", "10", "2030-01-01 00:00:02");
        insertUser(USER_3, "test_usr_mapper_gamma", "Gamma Kim", "20", "2030-01-01 00:00:03");
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE auth_id LIKE 'A_TEST_USR_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_id LIKE 'A_TEST_USR_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id LIKE 'U_TEST_USR_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id LIKE 'U_TEST_USR_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id LIKE 'U_TEST_USR_MAPPER_%'");
    }

    private void insertUser(String id, String loginId, String name, String status, String regDtm) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_dtm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, Timestamp.valueOf(regDtm), "SYSTEM");
    }

    @Test
    void search_filtersByLoginIdNameAndStatus_caseInsensitively_newestFirst() {
        assertThat(mapper.search("TEST_USR_MAPPER", null, null, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_3, USER_2, USER_1);
        assertThat(mapper.search("ALPHA", null, null, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_1);
        assertThat(mapper.search("test_usr_mapper", "kim", null, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_3, USER_1);
        assertThat(mapper.search("test_usr_mapper", null, UserStatus.NORM, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_3, USER_1);
        assertThat(mapper.search("test_usr_mapper", null, UserStatus.PEND, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_2);
    }

    @Test
    void search_pagesAtQueryLevel_andReportsTheTotalOnEveryRow() {
        List<UserBase> rows = mapper.search("test_usr_mapper", null, null, 1, 1);

        assertThat(rows).extracting(UserBase::getId).containsExactly(USER_2);
        assertThat(rows.get(0).getTotalSize()).isEqualTo(3L);
    }

    @Test
    void findById_mapsColumns_neverFillsPassword_andIsEmptyForUnknownId() {
        jdbcTemplate.update(
                "UPDATE kkdugi_user_base SET user_pwd = ?, pwd_stat_cd = ?, last_login_dtm = ?, user_dc = ?, user_img_src = ? "
                        + "WHERE user_id = ?",
                "{bcrypt}secret", "30", Timestamp.valueOf("2030-01-02 03:04:05"), "memo", "img.png", USER_1);

        UserBase row = mapper.findById(USER_1).orElseThrow();

        assertThat(row.getUsername()).isEqualTo("test_usr_mapper_alpha");
        assertThat(row.getName()).isEqualTo("Alpha Kim");
        assertThat(row.getEmail()).isEqualTo("test_usr_mapper_alpha@example.com");
        assertThat(row.getRemarks()).isEqualTo("memo");
        assertThat(row.getImage()).isEqualTo("img.png");
        assertThat(row.getStatus()).isEqualTo(UserStatus.NORM);
        assertThat(row.getPasswordStatus()).isEqualTo(kkdugi.core.enums.PasswordStatus.NORM);
        assertThat(row.getLastLoginAt()).isEqualTo(java.time.LocalDateTime.of(2030, 1, 2, 3, 4, 5));
        assertThat(row.getPassword()).isNull();
        assertThat(mapper.findById("U_TEST_USR_MAPPER_MISSING")).isEmpty();
    }

    @Test
    void findByUsernameAndEmail_matchExactly() {
        assertThat(mapper.findByUsername("test_usr_mapper_alpha")).map(UserBase::getId).contains(USER_1);
        assertThat(mapper.findByUsername("TEST_USR_MAPPER_ALPHA")).isEmpty();
        assertThat(mapper.findByEmail("test_usr_mapper_beta@example.com")).map(UserBase::getId).contains(USER_2);
        assertThat(mapper.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void findExistingIds_returnsOnlyTheIdsThatExist() {
        assertThat(mapper.findExistingIds(List.of(USER_1, USER_3, "U_TEST_USR_MAPPER_MISSING")))
                .containsExactlyInAnyOrder(USER_1, USER_3);
    }

    @Test
    void updatePassword_setsPasswordStatusAndChangeTimestamp_forOneUserOnly() {
        LocalDateTime changedAt = LocalDateTime.of(2031, 2, 3, 4, 5, 6);

        assertThat(mapper.updatePassword(USER_1, "{bcrypt}new", PasswordStatus.NEWP, changedAt, "SYSTEM")).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("SELECT user_pwd FROM kkdugi_user_base WHERE user_id = ?", String.class, USER_1))
                .isEqualTo("{bcrypt}new");
        assertThat(jdbcTemplate.queryForObject("SELECT pwd_stat_cd FROM kkdugi_user_base WHERE user_id = ?", String.class, USER_1))
                .isEqualTo("10");
        assertThat(mapper.findById(USER_1).orElseThrow().getLastChangePasswordAt()).isEqualTo(changedAt);
        assertThat(mapper.findById(USER_2).orElseThrow().getPasswordStatus()).isNull();
    }

    @Test
    void updateStatus_changesEveryGivenUser() {
        assertThat(mapper.updateStatus(List.of(USER_1, USER_2), UserStatus.SUPD, LocalDateTime.now(), "SYSTEM")).isEqualTo(2);

        assertThat(mapper.findById(USER_1).orElseThrow().getStatus()).isEqualTo(UserStatus.SUPD);
        assertThat(mapper.findById(USER_2).orElseThrow().getStatus()).isEqualTo(UserStatus.SUPD);
        assertThat(mapper.findById(USER_3).orElseThrow().getStatus()).isEqualTo(UserStatus.NORM);
    }

    @Test
    void deleteQueries_removeSessionsAuthorityMappingsAndTheUserRow() {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                + "VALUES ('A_TEST_USR_MAPPER_1', 'TEST_USR_MAPPER_ROLE', 'ROLE', 'Mapper Role', 'SYSTEM')");
        jdbcTemplate.update("INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                + "VALUES (?, 'A_TEST_USR_MAPPER_1', CURRENT_DATE, DATE '9999-12-31', 'SYSTEM')", USER_1);
        jdbcTemplate.update("INSERT INTO kkdugi_session (sess_id, user_id, exp_dtm) VALUES ('S_TEST_USR_MAPPER_1', ?, ?)",
                USER_1, Timestamp.valueOf("2099-01-01 00:00:00"));
        // 정리는 @AfterEach의 wipe()가 맡는다(user_auth를 auth_base보다 먼저 지운다).
        assertThat(mapper.deleteSessionsByUserId(USER_1)).isEqualTo(1);
        assertThat(mapper.deleteUserAuthsByUserId(USER_1)).isEqualTo(1);
        assertThat(mapper.deleteById(USER_1)).isEqualTo(1);

        assertThat(mapper.findById(USER_1)).isEmpty();
        assertThat(mapper.findById(USER_2)).isPresent();
    }

    private void insertAuthority(String id, String role, String use) {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, auth_dc, use_yn, reg_id) "
                + "VALUES (?, ?, 'ROLE', ?, 'desc', ?, 'SYSTEM')", id, role, "Name " + role, use);
    }

    @Test
    void authorityQueries_upsertListAndDeleteMappings() {
        insertAuthority("A_TEST_USR_MAPPER_1", "TEST_USR_MAPPER_A", "Y");
        insertAuthority("A_TEST_USR_MAPPER_2", "TEST_USR_MAPPER_B", "N");

        UserAuthority first = new UserAuthority(USER_1, "A_TEST_USR_MAPPER_1", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31));
        first.setCreatedAt(LocalDateTime.now());
        first.setCreatorId("SYSTEM");
        mapper.upsertAuthority(first);
        UserAuthority second = new UserAuthority(USER_1, "A_TEST_USR_MAPPER_2", LocalDate.of(2030, 2, 1), LocalDate.of(9999, 12, 31));
        second.setCreatedAt(LocalDateTime.now());
        second.setCreatorId("SYSTEM");
        mapper.upsertAuthority(second);
        // 같은 매핑을 다시 넣으면 적용기간만 바뀐다(upsert)
        UserAuthority renewed = new UserAuthority(USER_1, "A_TEST_USR_MAPPER_1", LocalDate.of(2031, 1, 1), LocalDate.of(2031, 6, 30));
        renewed.setCreatedAt(LocalDateTime.now());
        renewed.setCreatorId("SYSTEM");
        mapper.upsertAuthority(renewed);

        List<UserAuthority> rows = mapper.findAuthoritiesByUserId(USER_1);
        assertThat(rows).extracting(UserAuthority::getAuthorityId).containsExactly("A_TEST_USR_MAPPER_1", "A_TEST_USR_MAPPER_2");
        assertThat(rows.get(0).getRole()).isEqualTo("TEST_USR_MAPPER_A");
        assertThat(rows.get(0).getType()).isEqualTo(AuthorityType.ROLE);
        assertThat(rows.get(0).getName()).isEqualTo("Name TEST_USR_MAPPER_A");
        assertThat(rows.get(0).getRemarks()).isEqualTo("desc");
        assertThat(rows.get(0).getUse()).isEqualTo("Y");
        assertThat(rows.get(0).getApplyStartDate()).isEqualTo(LocalDate.of(2031, 1, 1));
        assertThat(rows.get(0).getApplyEndDate()).isEqualTo(LocalDate.of(2031, 6, 30));
        assertThat(rows.get(1).getUse()).isEqualTo("N");
        assertThat(mapper.findAuthoritiesByUserId(USER_2)).isEmpty();

        assertThat(mapper.findExistingAuthorityIds(List.of("A_TEST_USR_MAPPER_1", "A_TEST_USR_MAPPER_MISSING")))
                .containsExactly("A_TEST_USR_MAPPER_1");

        assertThat(mapper.deleteAuthorities(USER_1, List.of("A_TEST_USR_MAPPER_2"))).isEqualTo(1);
        assertThat(mapper.findAuthoritiesByUserId(USER_1)).extracting(UserAuthority::getAuthorityId)
                .containsExactly("A_TEST_USR_MAPPER_1");
    }
}
