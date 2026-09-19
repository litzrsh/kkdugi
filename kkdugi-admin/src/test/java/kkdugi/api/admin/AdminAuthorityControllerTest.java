package kkdugi.api.admin;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

import kkdugi.KkdugiAdminApplication;
import kkdugi.core.Constants;
import kkdugi.core.enums.Rbac;
import kkdugi.core.security.SecurityChecker;
import kkdugi.support.TestAuthorization;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminAuthorityControllerTest {

    private static final String URL = "/api/v1.0/admin/authority";
    private static final String ROLE_PREFIX = "TEST_AUTHZ_API_";
    private static final String USER_1 = "U_TEST_AUTHZ_API_1";
    private static final String USER_2 = "U_TEST_AUTHZ_API_2";
    private static final String MENU_1 = "M_TEST_AUTHZ_API_1";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // SYS_ADMIN 역할 + 모든 RBAC 비트를 가진 세션으로 admin/authority 메뉴에서 호출한다.
        mockMvc = TestAuthorization.mvc(webApplicationContext, "admin/authority");
        wipe();
        insertUser(USER_1, "test_authz_api_one", "Api One");
        insertUser(USER_2, "test_authz_api_two", "Api Two");
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_img_src = ? WHERE user_id = ?",
                "https://img.example/api-one.png", USER_1);
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "authz_api_1", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "ko_KR", "API 테스트 메뉴", "SYSTEM");
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
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id LIKE 'M_TEST_AUTHZ_API_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id LIKE 'M_TEST_AUTHZ_API_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id LIKE 'U_TEST_AUTHZ_API_%'");
    }

    private void insertUser(String id, String loginId, String name) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", "20", "SYSTEM");
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

    private Map<String, Object> fullBody(String roleSuffix) {
        return map("role", ROLE_PREFIX + roleSuffix, "type", "ROLE", "name", "API " + roleSuffix,
                "remarks", "r", "use", "Y",
                // name/image는 응답 전용이라 요청에 실어 보내도 무시되고 사용자 테이블의 값이 내려온다.
                "users", List.of(map("id", USER_1, "name", "Client Supplied", "image", "client.png",
                        "applyStartDate", "2030-01-01", "applyEndDate", "2030-12-31")),
                "menus", List.of(map("id", MENU_1,
                        "authorities", map("10", true, "20", false, "30", false, "40", false))));
    }

    /** 지정한 RBAC 비트/역할의 세션으로 admin/authority 메뉴에서 호출하는 MockMvc — 어노테이션 적용을 검증한다. */
    private MockMvc mvcAs(int bits, String... roles) {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .defaultRequest(MockMvcRequestBuilders.get("/").header(SecurityChecker.MENU_ID_HEADER, "M_TEST_API"))
                .addFilters((request, response, chain) -> {
                    var previous = SecurityContextHolder.getContext();
                    var security = SecurityContextHolder.createEmptyContext();
                    security.setAuthentication(TestAuthorization.session("admin/authority", bits, roles));
                    SecurityContextHolder.setContext(security);
                    try {
                        chain.doFilter(request, response);
                    } finally {
                        SecurityContextHolder.setContext(previous);
                    }
                }).build();
    }

    /** regist를 호출하고 생성된 권한 id를 돌려준다. */
    private String regist(Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    @Test
    void endpoints_requireAuthentication_theSysAdminRole_andTheMatchingRbacBit() throws Exception {
        String body = json(fullBody("RBAC"));
        String missing = URL + "/A_TEST_AUTHZ_API_MISSING";

        // 미인증 → 401
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                .perform(post(URL).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        // SYS_ADMIN 역할이 없으면 모든 RBAC 비트를 가져도 403 (권한 상승 경로 차단)
        mvcAs(15, "OPERATOR").perform(post(URL).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        // READ만 있으면 조회는 되지만 등록/저장/삭제는 403
        MockMvc readOnly = mvcAs(Rbac.READ.getValue(), Constants.SYS_ADMIN);
        readOnly.perform(post(URL).contentType(APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        readOnly.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        readOnly.perform(post(missing).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        readOnly.perform(post(missing + "/delete")).andExpect(status().isForbidden());

        // WRTE가 있으면 등록은 되지만 삭제는 DELT가 따로 필요하다
        MockMvc writer = mvcAs(Rbac.READ.getValue() | Rbac.WRTE.getValue(), Constants.SYS_ADMIN);
        String response = writer.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.id");
        writer.perform(post(URL + "/" + id + "/delete")).andExpect(status().isForbidden());

        MockMvc deleter = mvcAs(Rbac.READ.getValue() | Rbac.DELT.getValue(), Constants.SYS_ADMIN);
        deleter.perform(post(URL + "/" + id + "/delete")).andExpect(status().isOk());
        deleter.perform(get(URL + "/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void regist_returnsCreatedAuthority_withObjectUsers_andHidesAuditFields() throws Exception {
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(json(fullBody("ONE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.role").value(ROLE_PREFIX + "ONE"))
                .andExpect(jsonPath("$.type").value("ROLE"))
                .andExpect(jsonPath("$.name").value("API ONE"))
                .andExpect(jsonPath("$.remarks").value("r"))
                .andExpect(jsonPath("$.use").value("Y"))
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].id").value(USER_1))
                .andExpect(jsonPath("$.users[0].name").value("Api One"))
                .andExpect(jsonPath("$.users[0].image").value("https://img.example/api-one.png"))
                .andExpect(jsonPath("$.users[0].applyStartDate").value("2030-01-01"))
                .andExpect(jsonPath("$.users[0].applyEndDate").value("2030-12-31"))
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.creatorId").doesNotExist())
                .andExpect(jsonPath("$.totalSize").doesNotExist());
    }

    @Test
    void get_returnsDetailWithUsers_and404ForUnknownId() throws Exception {
        String id = regist(fullBody("GET"));

        mockMvc.perform(get(URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.users[0].id").value(USER_1));

        mockMvc.perform(get(URL + "/A_TEST_AUTHZ_API_MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("authority.err.not_found"));
    }

    @Test
    void list_returnsPageWithoutUsers() throws Exception {
        regist(fullBody("L1"));
        regist(fullBody("L2"));
        regist(fullBody("L3"));

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .content(json(map("role", ROLE_PREFIX, "page", 1, "pageSize", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.contents.length()").value(2))
                .andExpect(jsonPath("$.contents[0].role").value(ROLE_PREFIX + "L1"))
                .andExpect(jsonPath("$.contents[0].users").doesNotExist());

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .content(json(map("role", ROLE_PREFIX + "L2", "type", "ROLE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(200))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void save_replacesFields_andUsers() throws Exception {
        String id = regist(fullBody("SAVE"));

        Map<String, Object> body = fullBody("SAVE");
        body.put("id", "IGNORED_BODY_ID");
        body.put("name", "Renamed");
        body.put("users", List.of(map("id", USER_2)));
        mockMvc.perform(post(URL + "/" + id).contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].id").value(USER_2))
                .andExpect(jsonPath("$.users[0].applyEndDate").value("9999-12-31"));

        mockMvc.perform(post(URL + "/A_TEST_AUTHZ_API_MISSING").contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_removesAuthority_andIsIdempotent() throws Exception {
        String id = regist(fullBody("DEL"));

        mockMvc.perform(post(URL + "/" + id + "/delete")).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/" + id + "/delete")).andExpect(status().isOk());
    }

    @Test
    void sysAdmin_deleteIsRejectedWith409() throws Exception {
        String sysAdminId = jdbcTemplate.queryForObject(
                "SELECT auth_id FROM kkdugi_auth_base WHERE auth_tp_cd = 'ROLE' AND auth_role_cd = 'SYS_ADMIN'",
                String.class);

        mockMvc.perform(post(URL + "/" + sysAdminId + "/delete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("authority.err.immutable"));
    }

    @Test
    void regist_returns400_forMalformedRequest_and409_forDuplicate() throws Exception {
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("role", ROLE_PREFIX + "BAD", "type", "BOGUS", "name", "n"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("authority.err.malformed_request"));

        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("role", ROLE_PREFIX + "BAD2", "type", "ROLE", "name", "n",
                                "users", List.of(map("id", "U_TEST_AUTHZ_API_MISSING"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("authority.err.user_not_found"));

        regist(fullBody("DUP"));
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(json(fullBody("DUP"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("authority.err.duplicate"));
    }

    @Test
    void candidates_listsUnmappedNormalUsers_matchingTheQuery() throws Exception {
        String id = regist(fullBody("CAND"));

        mockMvc.perform(post(URL + "/" + id + "/user").contentType(APPLICATION_JSON)
                        .content(json(map("query", "TEST_AUTHZ_API"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(USER_2))
                .andExpect(jsonPath("$[0].username").value("test_authz_api_two"))
                .andExpect(jsonPath("$[0].name").value("Api Two"));

        // 본문 없이도 호출할 수 있다.
        mockMvc.perform(post(URL + "/" + id + "/user")).andExpect(status().isOk());

        mockMvc.perform(post(URL + "/A_TEST_AUTHZ_API_MISSING/user").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void menus_returnsTreeWithRbacMapKeyedByCode() throws Exception {
        String id = regist(fullBody("MENU"));

        mockMvc.perform(post(URL + "/" + id + "/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + MENU_1 + "')].authorities['10']").value(true))
                .andExpect(jsonPath("$[?(@.id=='" + MENU_1 + "')].authorities['20']").value(false))
                .andExpect(jsonPath("$[?(@.id=='" + MENU_1 + "')].locale.ko_KR.label").value("API 테스트 메뉴"))
                .andExpect(jsonPath("$[?(@.id=='" + MENU_1 + "')].program").value("authz_api_1"));

        mockMvc.perform(post(URL + "/A_TEST_AUTHZ_API_MISSING/menu")).andExpect(status().isNotFound());
    }
}
