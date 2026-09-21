package kkdugi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.Filter;
import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.service.BatchEnrollmentService;
import kkdugi.support.BatchTestData;

/** 실제 보안 필터 체인(사용자 체인 + 배치 Runner 체인)을 통과하는 Runner API 계약 테스트. */
@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchAgentApiTest {

    private static final String BASE = "/api/v1.0/batch-agent";
    private static final String REGISTER_BODY =
            "{\"runnerCode\":\"tb-api-1\",\"agentVersion\":\"0.1.0\",\"hostname\":\"host-1\",\"os\":\"LINUX\",\"architecture\":\"AMD64\"}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BatchEnrollmentService enrollment;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BatchTestData.wipe(jdbc);
        // MockMvc는 서블릿 필터 빈을 자동 등록하지 않는다. 운영과 같은 순서(보안 체인 → 본문 한도)로 직접 등록한다.
        Filter bodyLimit = context.getBean("batchBodyLimitFilterRegistration", FilterRegistrationBean.class).getFilter();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(bodyLimit).build();
    }

    @AfterEach
    void cleanUp() {
        BatchTestData.wipe(jdbc);
    }

    private static MockHttpServletRequestBuilder agentPost(String path, String bearer, String body) {
        MockHttpServletRequestBuilder builder = post(BASE + path).header("X-Protocol-Version", "1")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return bearer == null ? builder : builder.header("Authorization", "Bearer " + bearer);
    }

    private String enrollmentToken(String code) {
        String runnerId = BatchTestData.insertRunner(jdbc, code, "REGISTERING");
        return enrollment.issue(runnerId, BatchTestData.USER).getEnrollmentToken();
    }

    private String registerAndGetAccessToken(String code) throws Exception {
        String token = enrollmentToken(code);
        MvcResult result = mvc.perform(agentPost("/registrations", token, REGISTER_BODY.replace("tb-api-1", code)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void aRunnerRegistersOpensASessionAndSendsHeartbeats() throws Exception {
        String enrollmentToken = enrollmentToken("tb-api-1");

        MvcResult registered = mvc.perform(agentPost("/registrations", enrollmentToken, REGISTER_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.session").value("0"))
                .andExpect(jsonPath("$.runnerId").exists())
                .andExpect(jsonPath("$.credentialId").exists())
                .andExpect(jsonPath("$.tokenExpiresAt").exists())
                .andReturn();
        String accessToken = JsonPath.read(registered.getResponse().getContentAsString(), "$.accessToken");

        mvc.perform(agentPost("/sessions", accessToken,
                "{\"bootId\":\"" + UUID.randomUUID() + "\",\"expectedSession\":\"0\",\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.session").value("1"))
                .andExpect(jsonPath("$.heartbeatSeconds").value(10))
                .andExpect(jsonPath("$.pollSeconds").value(3))
                .andExpect(jsonPath("$.leaseSeconds").value(60))
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpect(jsonPath("$.limits.jsonBytes").value(1048576))
                .andExpect(jsonPath("$.limits.logChunkBytes").value(32768))
                .andExpect(jsonPath("$.serverTime").exists());

        mvc.perform(agentPost("/heartbeat", accessToken,
                "{\"observedAt\":\"2026-09-20T02:00:00Z\",\"mode\":\"ACCEPTING\",\"freeSlots\":1,\"assignments\":[]}")
                .header("X-Runner-Session", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingAssignments").value(true))
                .andExpect(jsonPath("$.assignments").isEmpty());
    }

    @Test
    void requestsWithoutACredentialAreUnauthorized() throws Exception {
        mvc.perform(agentPost("/sessions", null, "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("batch.credential.invalid"))
                .andExpect(jsonPath("$.message").exists());
        mvc.perform(agentPost("/heartbeat", "garbage", "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("batch.credential.invalid"));
    }

    @Test
    void aUserStyleJwtIsNotAValidRunnerCredential() throws Exception {
        mvc.perform(agentPost("/sessions", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl", "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("batch.credential.invalid"));
    }

    @Test
    void credentialTypesAreNotInterchangeable() throws Exception {
        String enrollmentToken = enrollmentToken("tb-api-1");
        // 등록 토큰은 등록에만 쓸 수 있다.
        mvc.perform(agentPost("/sessions", enrollmentToken, "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("batch.runner.forbidden"));
        // ACCESS 토큰은 등록에 쓸 수 없다.
        String accessToken = registerAndGetAccessToken("tb-api-2");
        mvc.perform(agentPost("/registrations", accessToken, REGISTER_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("batch.runner.forbidden"));
    }

    @Test
    void theProtocolVersionHeaderIsRequired() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-3");

        mvc.perform(post(BASE + "/heartbeat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.protocol.unsupported"));
        mvc.perform(post(BASE + "/heartbeat").header("Authorization", "Bearer " + accessToken)
                .header("X-Protocol-Version", "2").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.protocol.unsupported"));
    }

    @Test
    void unknownRequestFieldsAndMalformedBodiesAreBadRequests() throws Exception {
        String token = enrollmentToken("tb-api-1");

        mvc.perform(agentPost("/registrations", token, REGISTER_BODY.replace("}", ",\"extra\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        mvc.perform(agentPost("/registrations", token, "{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    @Test
    void domainErrorsUseTheContractStatusAndCode() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-4");

        // 세션 개설 전에는 세대 0이라 heartbeat의 세션 헤더 1은 낡은 세션이다.
        mvc.perform(agentPost("/heartbeat", accessToken,
                "{\"observedAt\":\"2026-09-20T02:00:00Z\",\"mode\":\"ACCEPTING\",\"freeSlots\":0,\"assignments\":[]}")
                .header("X-Runner-Session", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.session.stale"));
        mvc.perform(agentPost("/sessions", accessToken,
                "{\"bootId\":\"nope\",\"expectedSession\":\"0\",\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    @Test
    void aRevokedAccessTokenStopsWorking() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-5");
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET revoked_dtm = now() WHERE credential_type = 'ACCESS' "
                + "AND runner_id IN (SELECT runner_id FROM kkdugi_batch_runner WHERE runner_cd = 'tb-api-5')");

        mvc.perform(agentPost("/sessions", accessToken,
                "{\"bootId\":\"" + UUID.randomUUID() + "\",\"expectedSession\":\"0\",\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("batch.credential.invalid"));
    }

    @Test
    void aRunnerTokenDoesNotAuthenticateOnTheUserApi() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-6");

        mvc.perform(get("/api/v1.0/admin/authority/BA0000000000000001").header("Authorization", "Bearer " + accessToken)
                .header("X-Menu-Id", "M_TEST_API"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.err.unauthorized"));
    }

    private static String paddedRegisterBody(int totalBytes) {
        return REGISTER_BODY + " ".repeat(totalBytes - REGISTER_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void bodiesOverOneMibAreRejectedAfterAuthentication() throws Exception {
        String token = enrollmentToken("tb-api-1");

        mvc.perform(agentPost("/registrations", token, paddedRegisterBody(1_048_577)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("batch.payload.too_large"));
        // 인증이 먼저다: 자격증명이 없으면 크기와 무관하게 401이다.
        mvc.perform(agentPost("/registrations", null, paddedRegisterBody(1_048_577)))
                .andExpect(status().isUnauthorized());
        // 거절된 요청은 등록 토큰을 소비하지 않는다.
        assertThat(jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_cd = 'tb-api-1'",
                String.class)).isEqualTo("REGISTERING");
    }

    @Test
    void aBodyOfExactlyOneMibIsAccepted() throws Exception {
        String token = enrollmentToken("tb-api-1");

        mvc.perform(agentPost("/registrations", token, paddedRegisterBody(1_048_576)))
                .andExpect(status().isCreated());
    }

    @Test
    void wrongJsonTypesAreBadRequests() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-8");
        String boot = "\"bootId\":\"" + UUID.randomUUID() + "\",\"agentVersion\":\"0.1.0\"";
        // 세션 세대는 십진 문자열이어야 한다. 숫자 토큰은 "0"으로 바뀌지 않고 거절된다.
        mvc.perform(agentPost("/sessions", accessToken, "{" + boot + ",\"expectedSession\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        String heartbeat = "\"observedAt\":\"2026-09-20T02:00:00Z\",\"mode\":\"ACCEPTING\",\"assignments\":[]";
        for (String freeSlots : new String[] { "\"1\"", "1.5", "true" }) {
            mvc.perform(agentPost("/heartbeat", accessToken, "{" + heartbeat + ",\"freeSlots\":" + freeSlots + "}")
                    .header("X-Runner-Session", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        }
        // phase가 없는 항목은 500이 아니라 400이다.
        mvc.perform(agentPost("/heartbeat", accessToken,
                "{\"observedAt\":\"2026-09-20T02:00:00Z\",\"mode\":\"ACCEPTING\",\"freeSlots\":0,\"assignments\":[{\"id\":\"BA1\"}]}")
                .header("X-Runner-Session", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    @Test
    void successfulCallsRecordCredentialUsage() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-7");

        mvc.perform(agentPost("/sessions", accessToken,
                "{\"bootId\":\"" + UUID.randomUUID() + "\",\"expectedSession\":\"0\",\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential c "
                + "JOIN kkdugi_batch_runner r ON r.runner_id = c.runner_id "
                + "WHERE r.runner_cd = 'tb-api-7' AND c.credential_type = 'ACCESS' AND c.last_used_dtm IS NOT NULL",
                Integer.class)).isEqualTo(1);
    }
}
