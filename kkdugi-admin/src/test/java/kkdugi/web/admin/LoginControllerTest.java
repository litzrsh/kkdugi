package kkdugi.web.admin;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.List;
import static org.mockito.Mockito.mock;
import kkdugi.web.admin.service.AdminLanguageService;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import kkdugi.app.code.models.Code;
import kkdugi.app.code.service.CodeService;
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
    private CodeService codes;
    private MockMvc languageMvc;
    private MockMvc mvc;
    private Code language(String value, String locale, String label) {
        Code code=new Code(); code.setCode(value); code.setExtra1(locale); code.setName(label); return code;
    }
    @BeforeEach void setup() {
        codes=mock(CodeService.class);
        languageMvc=MockMvcBuilders.standaloneSetup(new LoginController(new AdminLanguageService(codes)))
                .setViewResolvers((name, locale) -> context.getBean(ThymeleafViewResolver.class).resolveViewName(name, locale))
                .setLocaleResolver(context.getBean(LocaleResolver.class))
                .addInterceptors(context.getBean(LocaleChangeInterceptor.class)).build();
        for (String locale : List.of("ko_KR", "en_US")) {
            when(codes.findChildren("/SYS/LANG", locale)).thenReturn(List.of(language("KO", "ko_KR", "한국어"),language("EN", "en_US", "English")));
        }
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
    @Test void anonymousLoginUsesExtra1AndLocalizedLabelsInsteadOfFixedOptions() throws Exception {
        when(codes.findChildren("/SYS/LANG", "jp_JA")).thenReturn(List.of(language("JA", "jp_JA", "日本語"),language("FR", "fr_FR", "Français & langue"),language("EMPTY", null, "Invalid")));
        String html=languageMvc.perform(get("/kk/login").contextPath("/kk").param("lang","jp_JA"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("value=\"jp_JA\" selected=\"selected\"", "日本語", "value=\"fr_FR\"", "Français &amp; langue")
                .doesNotContain("value=\"ko_KR\"", "value=\"en_US\"", "value=\"JA\"", ">Invalid<", "vue.global");
        verify(codes).findChildren("/SYS/LANG", "jp_JA");
        java.nio.file.Path output=java.nio.file.Path.of("target", "login-test-output");
        java.nio.file.Files.createDirectories(output);java.nio.file.Files.writeString(output.resolve("dynamic-ja.html"),html);
    }
    @Test void equivalentHyphenatedLocaleRemainsSelectedWithoutChangingConfiguredValue() throws Exception {
        when(codes.findChildren("/SYS/LANG", "en_US")).thenReturn(List.of(language("EN", "en-US", "English")));
        languageMvc.perform(get("/login").param("lang","en-US")).andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"en-US\" selected=\"selected\"")));
    }
    @Test void emptyLanguageListStillRendersLoginAndHidesLanguagePicker() throws Exception {
        when(codes.findChildren("/SYS/LANG", "en_US")).thenReturn(List.of());
        String html=languageMvc.perform(get("/kk/login").contextPath("/kk").param("lang","en_US"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("class=\"language-picker\" hidden=\"hidden\"", "id=\"login-form\"")
                .doesNotContain("value=\"en_US\"", "value=\"ko_KR\"");
        java.nio.file.Path output=java.nio.file.Path.of("target", "login-test-output");
        java.nio.file.Files.createDirectories(output);java.nio.file.Files.writeString(output.resolve("dynamic-empty.html"),html);
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
    @Test void passwordDialogOffersChangeAndExtensionWithoutVue() throws Exception {
        mvc.perform(get("/login").param("lang","en_US")).andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"password-dialog\"")))
            .andExpect(content().string(containsString("id=\"extend-password\"")))
            .andExpect(content().string(containsString("Extend and continue")))
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
