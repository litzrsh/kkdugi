package kkdugi.api.admin;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

import kkdugi.KkdugiAdminApplication;
import kkdugi.core.Constants;
import kkdugi.core.enums.Rbac;
import kkdugi.support.TestAuthorization;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminUserControllerTest {

    private static final String URL = "/api/v1.0/admin/user";
    private static final String USER_1 = "U_TEST_USR_API_1";
    private static final String USER_2 = "U_TEST_USR_API_2";

    /** 배치 RBAC 검사용 본문 — 없는 권한 id라 사용자가 없으면 404로 끝나 상태를 바꾸지 않는다. */
    private static final String AUTHORITY_INSERT_BODY = "{\"insert\":[{\"id\":\"A_TEST_USR_API_MISSING\"}]}";
    private static final String AUTHORITY_DELETE_BODY = "{\"delete\":[{\"id\":\"A_TEST_USR_API_MISSING\"}]}";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = TestAuthorization.mvc(webApplicationContext, "admin/user");
        wipe();
        insertUser(USER_1, "test_usr_api_one", "Api One", "20");
        insertUser(USER_2, "test_usr_api_two", "Api Two", "10");
        jdbcTemplate.update(
                "UPDATE kkdugi_user_base SET user_pwd = ?, pwd_stat_cd = ?, last_login_dtm = ? WHERE user_id = ?",
                "{bcrypt}secret-hash", "30", Timestamp.valueOf("2030-01-02 03:04:05"), USER_1);
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        String userIds = "SELECT user_id FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_api_%'";
        String authIds = "SELECT auth_id FROM kkdugi_auth_base WHERE auth_role_cd LIKE 'TEST_USR_API_%'";
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id IN (" + userIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id IN (" + userIds + ") OR auth_id IN (" + authIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_role_cd LIKE 'TEST_USR_API_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_api_%'");
    }

    private void insertUser(String id, String loginId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, "SYSTEM");
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    void search_returnsPage_withoutPasswordOrAuditFields() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(json(map("username", "test_usr_api_"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(200))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.contents.length()").value(2))
                .andExpect(jsonPath("$.contents[0].password").doesNotExist())
                .andExpect(jsonPath("$.contents[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.contents[0].creatorId").doesNotExist())
                .andExpect(jsonPath("$.contents[0].totalSize").doesNotExist());
    }

    @Test
    void search_filtersByStatus() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_", "status", "10"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.contents[0].id").value(USER_2));
    }

    @Test
    void get_returnsDetail_withFormattedDateTimes_andNoSecrets() throws Exception {
        mockMvc.perform(get(URL + "/" + USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_1))
                .andExpect(jsonPath("$.username").value("test_usr_api_one"))
                .andExpect(jsonPath("$.name").value("Api One"))
                .andExpect(jsonPath("$.email").value("test_usr_api_one@example.com"))
                .andExpect(jsonPath("$.status").value("20"))
                .andExpect(jsonPath("$.passwordStatus").value("30"))
                .andExpect(jsonPath("$.lastLoginAt").value("2030-01-02 03:04:05"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.userPwd").doesNotExist());
    }

    @Test
    void get_returns404ForUnknownUser() throws Exception {
        mockMvc.perform(get(URL + "/U_TEST_USR_API_MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user.err.not_found"));
    }

    @Test
    void search_requiresTheSysAdminRole() throws Exception {
        TestAuthorization.mvc(webApplicationContext, "admin/user", 15, "OPERATOR")
                .perform(post(URL).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void regist_returnsCreatedUser_andNeverExposesThePassword() throws Exception {
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("id", "ignored", "username", "test_usr_api_new", "name", "New Api",
                                "email", "test_usr_api_new@example.com", "remarks", "r", "image", "i.png"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.matchesPattern("U\\d{16}")))
                .andExpect(jsonPath("$.username").value("test_usr_api_new"))
                .andExpect(jsonPath("$.status").value("20"))
                .andExpect(jsonPath("$.passwordStatus").value("10"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void regist_returns400ForMalformedBody_and409ForDuplicates() throws Exception {
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_bad", "email", "test_usr_api_bad@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.err.malformed_request"));

        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_one", "name", "Dup", "email", "test_usr_api_dup@example.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.err.duplicate_username"));

        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_dup", "name", "Dup", "email", "test_usr_api_one@example.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.err.duplicate_email"));
    }

    @Test
    void save_updatesTheUser_rejectsUsernameChange_and404ForUnknownUser() throws Exception {
        mockMvc.perform(post(URL + "/" + USER_1).contentType(APPLICATION_JSON)
                        .content(json(map("id", USER_1, "username", "test_usr_api_one", "name", "Saved",
                                "email", "test_usr_api_one@example.com", "status", "30"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Saved"))
                .andExpect(jsonPath("$.status").value("30"))
                .andExpect(jsonPath("$.passwordStatus").value("30"));

        // status를 생략하면 기존 값을 유지한다 — USER_2는 "10"이라 기본값("20")과 구분된다.
        mockMvc.perform(post(URL + "/" + USER_2).contentType(APPLICATION_JSON)
                        .content(json(map("name", "Kept", "email", "test_usr_api_two@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kept"))
                .andExpect(jsonPath("$.status").value("10"));

        mockMvc.perform(post(URL + "/" + USER_1).contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_changed", "name", "Saved",
                                "email", "test_usr_api_one@example.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.err.immutable"));

        mockMvc.perform(post(URL + "/U_TEST_USR_API_MISSING").contentType(APPLICATION_JSON)
                        .content(json(map("name", "X", "email", "test_usr_api_x@example.com"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void resetPassword_returns200_404ForUnknown_and400ForEmpty() throws Exception {
        mockMvc.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of(USER_1, USER_2)))))
                .andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + USER_1))
                .andExpect(jsonPath("$.passwordStatus").value("10"))
                .andExpect(jsonPath("$.lastChangePasswordAt").isNotEmpty());

        mockMvc.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of("U_TEST_USR_API_MISSING")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user.err.not_found"));

        mockMvc.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.err.malformed_request"));
    }

    @Test
    void changeStatus_updatesUsers_andReturns400ForAnUnknownStatus() throws Exception {
        mockMvc.perform(post(URL + "/change-status").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of(USER_1, USER_2), "status", "50"))))
                .andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + USER_2))
                .andExpect(jsonPath("$.status").value("50"));

        mockMvc.perform(post(URL + "/change-status").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of(USER_1), "status", "99"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_removesTheUser_andIsIdempotent() throws Exception {
        mockMvc.perform(post(URL + "/" + USER_2 + "/delete")).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + USER_2)).andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/" + USER_2 + "/delete")).andExpect(status().isOk());
    }

    private void insertAuthority(String id, String role) {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                + "VALUES (?, ?, 'ROLE', ?, 'SYSTEM')", id, role, "Name " + role);
    }

    @Test
    void authorities_saveThenGet_ignoresResponseOnlyFields_andFormatsDates() throws Exception {
        insertAuthority("A_TEST_USR_API_1", "TEST_USR_API_A");

        mockMvc.perform(post(URL + "/" + USER_1 + "/authorities").contentType(APPLICATION_JSON)
                        .content(json(map("insert", List.of(map("id", "A_TEST_USR_API_1", "name", "ignored", "role", "IGNORED",
                                "applyStartDate", "2030-01-01", "applyEndDate", "2030-12-31"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("A_TEST_USR_API_1"))
                .andExpect(jsonPath("$[0].role").value("TEST_USR_API_A"))
                .andExpect(jsonPath("$[0].type").value("ROLE"))
                .andExpect(jsonPath("$[0].name").value("Name TEST_USR_API_A"))
                .andExpect(jsonPath("$[0].use").value("Y"))
                .andExpect(jsonPath("$[0].applyStartDate").value("2030-01-01"))
                .andExpect(jsonPath("$[0].applyEndDate").value("2030-12-31"));

        mockMvc.perform(get(URL + "/" + USER_1 + "/authorities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 응답을 그대로 delete 항목으로 돌려보내도 동작한다
        mockMvc.perform(post(URL + "/" + USER_1 + "/authorities").contentType(APPLICATION_JSON)
                        .content(json(map("delete", List.of(map("id", "A_TEST_USR_API_1", "role", "TEST_USR_API_A"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void authorities_returns400ForUnknownAuthority_and404ForUnknownUser() throws Exception {
        mockMvc.perform(post(URL + "/" + USER_1 + "/authorities").contentType(APPLICATION_JSON)
                        .content(json(map("insert", List.of(map("id", "A_TEST_USR_API_MISSING"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.err.authority_not_found"));

        mockMvc.perform(get(URL + "/U_TEST_USR_API_MISSING/authorities"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user.err.not_found"));
    }

    @Test
    void endpoints_requireAuthentication_theSysAdminRole_andTheMatchingRbacBit() throws Exception {
        String missing = URL + "/U_TEST_USR_API_MISSING";
        String userBody = json(map("username", "test_usr_api_rbac", "name", "Rbac", "email", "test_usr_api_rbac@example.com"));
        String idsBody = json(map("id", List.of(USER_2)));

        // 미인증 → 401
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                .perform(post(URL).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        // SYS_ADMIN 역할이 없으면 모든 비트가 있어도 403 — 등록/초기화/삭제는 권한 상승 경로다
        MockMvc operator = TestAuthorization.mvc(webApplicationContext, "admin/user", 15, "OPERATOR");
        operator.perform(post(URL).contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        operator.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON).content(idsBody))
                .andExpect(status().isForbidden());

        // READ만 있으면 조회는 되지만 쓰기 계열은 전부 403
        MockMvc readOnly = TestAuthorization.mvc(webApplicationContext, "admin/user", Rbac.READ.getValue(), Constants.SYS_ADMIN);
        readOnly.perform(post(URL).contentType(APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        readOnly.perform(get(missing + "/authorities")).andExpect(status().isNotFound());
        readOnly.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(userBody)).andExpect(status().isForbidden());
        readOnly.perform(post(missing).contentType(APPLICATION_JSON).content(userBody)).andExpect(status().isForbidden());
        readOnly.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON).content(idsBody)).andExpect(status().isForbidden());
        readOnly.perform(post(URL + "/change-status").contentType(APPLICATION_JSON).content("{\"id\":[\"x\"],\"status\":\"20\"}"))
                .andExpect(status().isForbidden());
        readOnly.perform(post(missing + "/authorities").contentType(APPLICATION_JSON).content(AUTHORITY_INSERT_BODY))
                .andExpect(status().isForbidden());
        readOnly.perform(post(missing + "/delete")).andExpect(status().isForbidden());

        // WRTE가 있으면 등록은 되지만 삭제는 DELT가 따로 필요하다
        MockMvc writer = TestAuthorization.mvc(webApplicationContext, "admin/user",
                Rbac.READ.getValue() | Rbac.WRTE.getValue(), Constants.SYS_ADMIN);
        String response = writer.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(userBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.id");
        writer.perform(post(URL + "/" + id + "/delete")).andExpect(status().isForbidden());

        MockMvc deleter = TestAuthorization.mvc(webApplicationContext, "admin/user",
                Rbac.READ.getValue() | Rbac.DELT.getValue(), Constants.SYS_ADMIN);
        deleter.perform(post(URL + "/" + id + "/delete")).andExpect(status().isOk());
        deleter.perform(get(URL + "/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void authorities_returns400ForAMalformedDate() throws Exception {
        for (String date : List.of("not-a-date", "2030-13-45")) {
            mockMvc.perform(post(URL + "/" + USER_1 + "/authorities").contentType(APPLICATION_JSON)
                            .content(json(map("insert", List.of(map("id", "A_TEST_USR_API_1", "applyStartDate", date))))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("user.err.malformed_request"));
        }
    }

    @Test
    void regist_returns400ForASyntacticallyBrokenJsonBody() throws Exception {
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.err.malformed_request"));
    }

    /** 상태를 바꾸지 않는 요청(없는 id/빈 본문)으로 엔드포인트 하나의 인증·역할·RBAC 비트 검사를 모두 확인한다. */
    private void assertGuards(String label, HttpMethod method, String path, String body, Rbac requiredBit,
            int expectedStatusWhenAuthorized) throws Exception {
        try {
            // (a) 미인증 → 401 (보안 필터 없이 만든 MockMvc)
            MockMvc anonymous = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
            anonymous.perform(request(method, path).contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());

            // (b) SYS_ADMIN 역할이 없으면 모든 비트가 있어도 403
            MockMvc noRole = TestAuthorization.mvc(webApplicationContext, "admin/user", 15, "OPERATOR");
            noRole.perform(request(method, path).contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());

            // (c) SYS_ADMIN이어도 필요한 비트만 빠지면 403
            MockMvc missingBit = TestAuthorization.mvc(webApplicationContext, "admin/user",
                    15 & ~requiredBit.getValue(), Constants.SYS_ADMIN);
            missingBit.perform(request(method, path).contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());

            // (d) SYS_ADMIN + 필요한 비트만 있으면 통과 — 구체적인 비즈니스 상태로 확인한다
            MockMvc allowed = TestAuthorization.mvc(webApplicationContext, "admin/user", requiredBit.getValue(),
                    Constants.SYS_ADMIN);
            allowed.perform(request(method, path).contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().is(expectedStatusWhenAuthorized));
        } catch (AssertionError e) {
            throw new AssertionError(label + ": " + e.getMessage(), e);
        }
    }

    @Test
    void everyEndpoint_requiresAuthentication_theSysAdminRole_andItsOwnRbacBit() throws Exception {
        String missing = URL + "/U_TEST_USR_API_MISSING";

        assertGuards("list", HttpMethod.POST, URL, "{}", Rbac.READ, 200);
        assertGuards("get", HttpMethod.GET, missing, "", Rbac.READ, 404);
        assertGuards("regist", HttpMethod.POST, URL + "/regist", "{}", Rbac.WRTE, 400);
        assertGuards("save", HttpMethod.POST, missing, "{}", Rbac.WRTE, 400);
        assertGuards("reset-password", HttpMethod.POST, URL + "/reset-password", "{}", Rbac.WRTE, 400);
        assertGuards("change-status", HttpMethod.POST, URL + "/change-status", "{}", Rbac.WRTE, 400);
        assertGuards("delete", HttpMethod.POST, missing + "/delete", "", Rbac.DELT, 200);
        assertGuards("authorities", HttpMethod.GET, missing + "/authorities", "", Rbac.READ, 404);
        // 배치 규약 — 비어 있지 않은 insert/update는 WRTE만 요구한다
        assertGuards("authorities-save", HttpMethod.POST, missing + "/authorities", AUTHORITY_INSERT_BODY, Rbac.WRTE, 404);
    }

    /**
     * 권한 저장은 프로젝트의 배치 RBAC 규약을 따른다 — 비어 있지 않은 delete 목록은 WRTE가 아니라 DELT를
     * 요구한다. 없는 사용자 id로 호출해 어느 경우에도 상태를 바꾸지 않는다(허용되면 404).
     */
    @Test
    void authoritiesSave_followsBatchRbac_deleteListNeedsDelt() throws Exception {
        String path = URL + "/U_TEST_USR_API_MISSING/authorities";
        int readWrite = Rbac.READ.getValue() | Rbac.WRTE.getValue();

        // 삭제 항목이 있는데 DELT가 없으면 WRTE가 있어도 403
        TestAuthorization.mvc(webApplicationContext, "admin/user", readWrite, Constants.SYS_ADMIN)
                .perform(post(path).contentType(APPLICATION_JSON).content(AUTHORITY_DELETE_BODY))
                .andExpect(status().isForbidden());
        // DELT만 있으면 삭제뿐인 요청은 통과한다
        TestAuthorization.mvc(webApplicationContext, "admin/user", Rbac.DELT.getValue(), Constants.SYS_ADMIN)
                .perform(post(path).contentType(APPLICATION_JSON).content(AUTHORITY_DELETE_BODY))
                .andExpect(status().isNotFound());
        // insert만 있는 요청은 DELT만으로는 403 — WRTE가 필요하다
        TestAuthorization.mvc(webApplicationContext, "admin/user", Rbac.DELT.getValue(), Constants.SYS_ADMIN)
                .perform(post(path).contentType(APPLICATION_JSON).content(AUTHORITY_INSERT_BODY))
                .andExpect(status().isForbidden());
        // insert와 delete가 함께 있으면 둘 다 필요하다
        String both = "{\"insert\":[{\"id\":\"A_TEST_USR_API_MISSING\"}],\"delete\":[{\"id\":\"A_TEST_USR_API_MISSING_2\"}]}";
        TestAuthorization.mvc(webApplicationContext, "admin/user", readWrite, Constants.SYS_ADMIN)
                .perform(post(path).contentType(APPLICATION_JSON).content(both))
                .andExpect(status().isForbidden());
        TestAuthorization.mvc(webApplicationContext, "admin/user", Rbac.WRTE.getValue() | Rbac.DELT.getValue(), Constants.SYS_ADMIN)
                .perform(post(path).contentType(APPLICATION_JSON).content(both))
                .andExpect(status().isNotFound());
    }
}
