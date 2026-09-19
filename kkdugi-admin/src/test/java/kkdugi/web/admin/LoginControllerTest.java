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
    @Test void loginRendersFormPostingToLoginEndpointAndNoVueOrDemo() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
            .andExpect(content().string(containsString("action=\"/api/v1.0/auth/login\"")))
            .andExpect(content().string(containsString("enctype=\"application/x-www-form-urlencoded\"")))
            .andExpect(content().string(containsString("name=\"force\" value=\"false\"")))
            .andExpect(content().string(containsString("/js/auth/login.mjs")))
            .andExpect(content().string(not(containsString("duplicate-panel"))))
            .andExpect(content().string(not(containsString("vue.global"))))
            .andExpect(content().string(not(containsString("디자인 미리보기"))));
    }
    @Test void duplicateRedirectKeepsNormalFormAndOffersExplicitConfirmation() throws Exception {
        mvc.perform(get("/login").param("duplicate","").param("username","admin").param("lang","en_US")).andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"duplicate-dialog\"")))
            .andExpect(content().string(containsString("name=\"force\" value=\"false\"")))
            .andExpect(content().string(containsString("value=\"admin\"")))
            .andExpect(content().string(containsString("End previous session and sign in")))
            .andExpect(content().string(containsString("Do not sign in")))
            .andExpect(content().string(not(containsString("login.ui."))));
    }
    @Test void renderLoginFixtures() throws Exception {
        java.nio.file.Path output=java.nio.file.Path.of("target", "login-test-output");
        java.nio.file.Files.createDirectories(output);
        for (String lang : new String[]{"ko_KR", "en_US"}) {
            String html=mvc.perform(get("/kk/login").contextPath("/kk").param("lang",lang))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            java.nio.file.Files.writeString(output.resolve(lang+".html"),html);
        }
    }
    @Test void localeResolvesLoginLabels() throws Exception {
        mvc.perform(get("/login").param("lang","en_US")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Welcome back")))
            .andExpect(content().string(not(containsString("login.ui."))));
    }
    @Test void contextPathIsAppliedToAssetsAndEndpoints() throws Exception {
        mvc.perform(get("/kk/login").contextPath("/kk")).andExpect(status().isOk())
            .andExpect(content().string(containsString("/kk/api/v1.0/auth/login")))
            .andExpect(content().string(containsString("/kk/js/auth/login.mjs")));
    }
}
