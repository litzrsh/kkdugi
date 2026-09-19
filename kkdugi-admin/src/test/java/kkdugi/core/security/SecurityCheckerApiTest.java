package kkdugi.core.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import kkdugi.KkdugiAdminApplication;
import kkdugi.support.TestAuthorization;

/** Actual filter chain, controller proxies, request binding, and exception response contract. */
@SpringBootTest(classes = KkdugiAdminApplication.class)
@Transactional
class SecurityCheckerApiTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousApiAndPragmaReturn401Json() throws Exception {
        mvc.perform(post("/api/v1.0/admin/code").contentType("application/json").content("{\"page\":1,\"pageSize\":20}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("auth.err.unauthorized"));
        mvc.perform(get("/pragma/M_TEST_API").accept("text/html").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("auth.err.unauthorized"));
    }

    @Test
    void missingHeaderAndWrongProgramReturn403BeforeService() throws Exception {
        mvc.perform(post("/api/v1.0/admin/code")
                .with(authentication(TestAuthorization.session("admin/code", 15)))
                .contentType("application/json").content("{\"page\":1,\"pageSize\":20}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("auth.err.access_denied"));
        mvc.perform(post("/api/v1.0/admin/code").header("X-Menu-Id", "M_TEST_API")
                .with(authentication(TestAuthorization.session("admin/message", 15)))
                .contentType("application/json").content("{\"page\":1,\"pageSize\":20}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void readOnlyUserCanReadButCannotPersist() throws Exception {
        mvc.perform(post("/api/v1.0/admin/code").header("X-Menu-Id", "M_TEST_API")
                .with(authentication(TestAuthorization.session("admin/code", 1)))
                .contentType("application/json").content("{\"page\":1,\"pageSize\":20}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1.0/admin/i18n/persist").header("X-Menu-Id", "M_TEST_API")
                .with(authentication(TestAuthorization.session("admin/message", 1)))
                .contentType("application/json").content("{\"insert\":[{}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteOnlyBatchIsAllowedButMixedWriteDeleteIsAtomic() throws Exception {
        jdbc.update("INSERT INTO kkdugi_code_base (code_id, code_val, code_lvl, code_path, sort_seq, reg_id) "
                + "VALUES ('C_TEST_SECURITY', 'SECURITY_TEST', 0, '/SECURITY_TEST', 1, 'SYSTEM')");
        mvc.perform(post("/api/v1.0/admin/code/persist").header("X-Menu-Id", "M_TEST_API")
                .with(authentication(TestAuthorization.session("admin/code", 2)))
                .contentType("application/json").content("""
                    {"insert":[{"code":"SECURITY_NEW","sort":1,"locale":{"en_US":{"name":"Test"}}}],
                     "delete":[{"id":"C_TEST_SECURITY"}]}
                    """))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_code_base WHERE code_id = 'C_TEST_SECURITY'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_code_base WHERE code_path = '/SECURITY_NEW'", Integer.class)).isZero();
        mvc.perform(post("/api/v1.0/admin/code/persist").header("X-Menu-Id", "M_TEST_API")
                .with(authentication(TestAuthorization.session("admin/code", 4)))
                .contentType("application/json").content("""
                    {"delete":[{"id":"C_TEST_SECURITY"}]}
                    """))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_code_base WHERE code_id = 'C_TEST_SECURITY'", Integer.class)).isZero();
    }

    @Test
    void menuAdministrationRequiresRoleAndCorrectMenu() throws Exception {
        mvc.perform(get("/api/v1.0/admin/menu").header("X-Menu-Id", "M_TEST_API")
                .with(authentication(TestAuthorization.session("admin/menu", 15))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1.0/admin/menu").header("X-Menu-Id", "M_TEST_API")
                .with(authentication(TestAuthorization.session("admin/code", 15, "SYS_ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1.0/admin/menu").header("X-Menu-Id", "M_TEST_API")
                .with(authentication(TestAuthorization.session("admin/menu", 15, "SYS_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void shellIsLimitedToAuthenticatedMenuTree() throws Exception {
        mvc.perform(get("/api/v1.0/menu").header("X-Menu-Id", "__shell__")
                .with(authentication(TestAuthorization.session("admin/code", 1))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1.0/admin/menu").header("X-Menu-Id", "__shell__")
                .with(authentication(TestAuthorization.session("admin/menu", 15, "SYS_ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1.0/code").param("path", "/SYSTEM").header("X-Menu-Id", "__shell__")
                .with(authentication(TestAuthorization.session("admin/code", 1))))
                .andExpect(status().isForbidden());
    }

    @Test
    void pragmaMismatchReturns403JsonEvenWithHtmlAccept() throws Exception {
        mvc.perform(get("/pragma/M_OTHER").accept("text/html")
                .header("X-Menu-Id", "M_TEST_API")
                .with(authentication(TestAuthorization.session("admin/code", 1))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("auth.err.access_denied"));
    }

    @Test
    void logoutNeedsNoMenuHeader() throws Exception {
        mvc.perform(post("/api/v1.0/auth/logout")).andExpect(status().isNoContent());
    }
}
