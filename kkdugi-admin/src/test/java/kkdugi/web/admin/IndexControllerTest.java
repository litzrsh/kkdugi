package kkdugi.web.admin;

import static org.hamcrest.Matchers.containsString;
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

import kkdugi.KkdugiAdminApplication;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class IndexControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void index_isPubliclyReachable_andRendersShellWithAdminUiConfig() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                // 화면 UI 문구(properties fallback)가 실제로 해석돼 들어갔는지 —
                // Thymeleaf JS 인라이닝이 non-ASCII를 유니코드 이스케이프로
                // 바꾸므로 ("실행"의 이스케이프된 값)로 비교한다.
                .andExpect(content().string(containsString("\\uC2E4\\uD589")))
                // UserStatus 코드가 CodeEnums를 통해 statuses로 내려갔는지
                .andExpect(content().string(containsString("\"20\"")))
                // application.yml의 등록 언어 목록이 languages로 내려갔는지
                .andExpect(content().string(containsString("ko_KR")))
                .andExpect(content().string(containsString("en_US")));
    }

    @Test
    void index_withLangParam_switchesResolvedMessageLocale() throws Exception {
        mockMvc.perform(get("/").param("lang", "en_US"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Execute")));
    }
}
