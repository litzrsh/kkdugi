package kkdugi.web.admin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class LoginControllerTest {
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
    @Test void loginRendersJsonEndpointAndNoVueOrDemo() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
            .andExpect(content().string(containsString("/api/v1.0/admin/auth/login")))
            .andExpect(content().string(containsString("login-live.mjs")))
            .andExpect(content().string(containsString("duplicate-panel")))
            .andExpect(content().string(not(containsString("vue.global"))))
            .andExpect(content().string(not(containsString("디자인 미리보기"))));
    }
    @Test void localeResolvesLoginAndDuplicateLabels() throws Exception {
        mvc.perform(get("/login").param("lang","en_US")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Welcome back")))
            .andExpect(content().string(containsString("End previous session and sign in")))
            .andExpect(content().string(not(containsString("login.ui."))));
    }
    @Test void contextPathIsAppliedToAssetsAndEndpoints() throws Exception {
        mvc.perform(get("/kk/login").contextPath("/kk")).andExpect(status().isOk())
            .andExpect(content().string(containsString("/kk/api/v1.0/admin/auth/login")))
            .andExpect(content().string(containsString("/kk/auth/login-live.mjs")))
            .andExpect(content().string(containsString("data-success-url=\"/kk/admin\"")));
    }
}
