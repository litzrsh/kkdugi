package kkdugi.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.Filter;
import kkdugi.KkdugiAdminApplication;
import kkdugi.support.BatchTestData;
import kkdugi.support.TestAuthorization;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminBatchRunnerControllerTest {

    private static final String URL = "/api/v1.0/admin/batch/runners";
    private static final String PROGRAM = "admin/batch/runner";
    private static final int READ = 1;
    private static final int WRTE = 2;
    private static final int DELT = 4;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BatchTestData.wipe(jdbc);
        mvc = TestAuthorization.mvc(context, PROGRAM); // SYS_ADMIN + 모든 RBAC 비트
    }

    @AfterEach
    void cleanUp() {
        BatchTestData.wipe(jdbc);
    }

    private static String body(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"Admin " + code + "\",\"capacity\":2}";
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
    }

    private static MockHttpServletRequestBuilder keyed(MockHttpServletRequestBuilder builder, String key, String createdAt) {
        return builder.header("Idempotency-Key", key).header("X-Request-Created-At", createdAt);
    }

    private static MockHttpServletRequestBuilder keyed(MockHttpServletRequestBuilder builder) {
        return keyed(builder, UUID.randomUUID().toString(), now());
    }

    private String createRunner(String code) throws Exception {
        MvcResult result = mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body(code))))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private int runnerCount(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner WHERE runner_cd = ?", Integer.class, code);
    }

    // ---- 생성·조회 -----------------------------------------------------

    @Test
    void createReturns201WithLocationAndTheRunner() throws Exception {
        MvcResult result = mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("tb-adm-1"))
                .andExpect(jsonPath("$.name").value("Admin tb-adm-1"))
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpect(jsonPath("$.status").value("REGISTERING"))
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.session").value("0"))
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist())
                .andExpect(jsonPath("$.bootRef").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result.getResponse().getHeader("Location")).endsWith(URL + "/" + id);
    }

    @Test
    void writeCommandsRequireIdempotencyHeaders() throws Exception {
        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        mvc.perform(post(URL).header("Idempotency-Key", "k1").contentType(APPLICATION_JSON).content(body("tb-adm-2")))
                .andExpect(status().isBadRequest());
        assertThat(runnerCount("tb-adm-2")).isZero();
    }

    @Test
    void replayingACreateReturnsTheSameRunnerWithoutCreatingAnother() throws Exception {
        String key = UUID.randomUUID().toString();
        String createdAt = now();

        MvcResult first = mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-3")), key, createdAt))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-3")), key, createdAt))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(
                        (String) JsonPath.read(first.getResponse().getContentAsString(), "$.id"))))
                .andReturn();

        assertThat((String) JsonPath.read(replay.getResponse().getContentAsString(), "$.id"))
                .isEqualTo(JsonPath.read(first.getResponse().getContentAsString(), "$.id"));
        assertThat(runnerCount("tb-adm-3")).isEqualTo(1);

        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-3x")), key, createdAt))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.idempotency.conflict"));
    }

    @Test
    void duplicateCodeAndInvalidBodiesAreRejected() throws Exception {
        createRunner("tb-adm-4");

        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-4"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.runner.duplicate_code"));
        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content("{\"code\":\"tb-adm-5\",\"name\":\"n\",\"capacity\":0}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content("{\"code\":\"tb-adm-5\",\"name\":\"n\",\"capacity\":1,\"extra\":true}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    @Test
    void listFiltersAndDetailAndNotFound() throws Exception {
        String id = createRunner("tb-adm-6");
        createRunner("tb-adm-7");

        mvc.perform(get(URL).param("code", "tb-adm-6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(200))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.contents[0].id").value(id));
        mvc.perform(get(URL).param("code", "tb-adm-").param("status", "REGISTERING").param("page", "1").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.contents.length()").value(1));
        mvc.perform(get(URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("tb-adm-6"));
        mvc.perform(get(URL + "/BRNOSUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("batch.runner.not_found"));
        mvc.perform(get(URL).param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    // ---- 수정·삭제 -----------------------------------------------------

    @Test
    void putRequiresIfMatchAndDetectsStaleVersions() throws Exception {
        String id = createRunner("tb-adm-8");
        String putBody = "{\"code\":\"tb-adm-8\",\"name\":\"Renamed\",\"capacity\":3}";

        mvc.perform(keyed(put(URL + "/" + id).contentType(APPLICATION_JSON).content(putBody)))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("batch.version.required"));
        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "\"1\"").contentType(APPLICATION_JSON).content(putBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.version").value("2"));
        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "\"1\"").contentType(APPLICATION_JSON).content(putBody)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("batch.version.conflict"));
        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "abc").contentType(APPLICATION_JSON).content(putBody)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replayingAPutAfterTheVersionMovedStillReturnsTheOriginalResponse() throws Exception {
        String id = createRunner("tb-adm-9");
        String putBody = "{\"code\":\"tb-adm-9\",\"name\":\"Once\",\"capacity\":2}";
        String key = UUID.randomUUID().toString();
        String createdAt = now();

        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "\"1\"").contentType(APPLICATION_JSON).content(putBody), key, createdAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2"));
        // 응답이 유실돼 같은 key·같은 If-Match로 재전송: version이 이미 올라갔어도 최초 응답이 재현된다.
        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "\"1\"").contentType(APPLICATION_JSON).content(putBody), key, createdAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2"))
                .andExpect(jsonPath("$.name").value("Once"));
    }

    @Test
    void deleteReturns204AndReplaysWithoutError() throws Exception {
        String id = createRunner("tb-adm-10");
        String key = UUID.randomUUID().toString();
        String createdAt = now();

        mvc.perform(keyed(delete(URL + "/" + id), key, createdAt)).andExpect(status().isNoContent());
        mvc.perform(keyed(delete(URL + "/" + id), key, createdAt)).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/" + id)).andExpect(status().isNotFound());
        // 새 key로 다시 지우면 이미 없으므로 404다(멱등 재현이 아니라 새 명령).
        mvc.perform(keyed(delete(URL + "/" + id))).andExpect(status().isNotFound());
    }

    @Test
    void anActiveRunnerCannotBeDeletedUntilRevoked() throws Exception {
        String id = createRunner("tb-adm-11");
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'ACTIVE' WHERE runner_id = ?", id);

        mvc.perform(keyed(delete(URL + "/" + id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.state.conflict"));
    }

    // ---- 등록 토큰·폐기 ------------------------------------------------

    @Test
    void enrollmentIssuesANoStoreTokenWithoutIdempotencyHeaders() throws Exception {
        String id = createRunner("tb-adm-12");

        mvc.perform(post(URL + "/" + id + "/enrollment"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.credentialId").exists())
                .andExpect(jsonPath("$.enrollmentToken").value(containsString(".")))
                .andExpect(jsonPath("$.expiresAt").exists());
        mvc.perform(post(URL + "/BRNOSUCH/enrollment"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("batch.runner.not_found"));
    }

    @Test
    void revokeMarksTheRunnerRevokedAndValidatesTheReason() throws Exception {
        String id = createRunner("tb-adm-13");
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'ACTIVE' WHERE runner_id = ?", id);

        mvc.perform(keyed(post(URL + "/" + id + "/revoke").contentType(APPLICATION_JSON).content("{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        mvc.perform(keyed(post(URL + "/" + id + "/revoke").contentType(APPLICATION_JSON).content("{\"reason\":\"retired\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    // ---- 본문 한도·JSON 타입 -------------------------------------------

    @Test
    void bodiesOverOneMibAreRejectedWithoutSideEffects() throws Exception {
        Filter bodyLimit = context.getBean("batchBodyLimitFilterRegistration", FilterRegistrationBean.class).getFilter();
        MockMvc limited = TestAuthorization.mvcWithFilters(context, PROGRAM, 15, new Filter[] { bodyLimit }, "SYS_ADMIN");
        String base = body("tb-adm-19");
        String over = base + " ".repeat(1_048_577 - base.length()); // ASCII라 문자 수 == byte 수
        String exact = base + " ".repeat(1_048_576 - base.length());

        limited.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(over)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("batch.payload.too_large"));

        // 거절된 요청은 runner·멱등 기록·event를 만들지 않는다.
        assertThat(runnerCount("tb-adm-19")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_api_request WHERE subject_id = ?",
                Integer.class, BatchTestData.API_USER)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE actor_id = ?",
                Integer.class, BatchTestData.API_USER)).isZero();

        // 정확히 한도인 본문은 통과한다.
        limited.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(exact)))
                .andExpect(status().isCreated());
    }

    @Test
    void wrongJsonTypesAreBadRequests() throws Exception {
        for (String capacity : new String[] { "1.9", "\"1\"", "true", "null", "[1]" }) {
            mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON)
                    .content("{\"code\":\"tb-adm-20\",\"name\":\"n\",\"capacity\":" + capacity + "}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        }
        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON)
                .content("{\"code\":\"tb-adm-20\",\"name\":123,\"capacity\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        assertThat(runnerCount("tb-adm-20")).isZero();
    }

    // ---- 권한 ----------------------------------------------------------

    @Test
    void readOnlyUsersCannotWrite() throws Exception {
        MockMvc readOnly = TestAuthorization.mvc(context, PROGRAM, READ, "SYS_ADMIN");

        readOnly.perform(get(URL)).andExpect(status().isOk());
        readOnly.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-14"))))
                .andExpect(status().isForbidden());
        assertThat(runnerCount("tb-adm-14")).isZero();
    }

    @Test
    void anotherMenusWritePermissionCannotCreateRunners() throws Exception {
        MockMvc otherMenu = TestAuthorization.mvc(context, "admin/code", 15, "SYS_ADMIN");

        otherMenu.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-15"))))
                .andExpect(status().isForbidden());
        otherMenu.perform(get(URL)).andExpect(status().isForbidden());
        assertThat(runnerCount("tb-adm-15")).isZero();
    }

    @Test
    void deleteNeedsTheDeletePermissionBit() throws Exception {
        String id = createRunner("tb-adm-16");
        MockMvc noDelete = TestAuthorization.mvc(context, PROGRAM, READ | WRTE, "SYS_ADMIN");

        noDelete.perform(keyed(delete(URL + "/" + id))).andExpect(status().isForbidden());
        TestAuthorization.mvc(context, PROGRAM, READ | DELT, "SYS_ADMIN")
                .perform(keyed(delete(URL + "/" + id))).andExpect(status().isNoContent());
    }

    @Test
    void enrollmentAndRevokeRequireTheSysAdminRole() throws Exception {
        String id = createRunner("tb-adm-17");
        MockMvc notSysAdmin = TestAuthorization.mvc(context, PROGRAM, 15); // 역할 없음

        notSysAdmin.perform(post(URL + "/" + id + "/enrollment")).andExpect(status().isForbidden());
        notSysAdmin.perform(keyed(post(URL + "/" + id + "/revoke").contentType(APPLICATION_JSON).content("{\"reason\":\"x\"}")))
                .andExpect(status().isForbidden());
        // 일반 설정 관리는 역할 없이도 메뉴 권한만으로 가능하다.
        notSysAdmin.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-18"))))
                .andExpect(status().isCreated());
    }
}
