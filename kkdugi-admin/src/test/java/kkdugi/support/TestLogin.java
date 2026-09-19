package kkdugi.support;

import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 로그인 form submit을 흉내 내는 테스트 헬퍼 — 성공 응답의 토큰 쿠키에서 토큰을 꺼낸다. */
public final class TestLogin {

    public static final String LOGIN_URL = "/api/v1.0/auth/login";
    public static final String TOKEN_COOKIE = "KKDUGI_TOKEN";

    private TestLogin() {
    }

    public static MvcResult submit(MockMvc mockMvc, String username, String password, boolean force) throws Exception {
        return mockMvc.perform(post(LOGIN_URL).contentType(APPLICATION_FORM_URLENCODED)
                .param("username", username)
                .param("password", password)
                .param("force", String.valueOf(force)))
                .andReturn();
    }

    /** 로그인이 성공(302 → "/" + 토큰 쿠키)했다고 가정하고 토큰을 돌려준다. */
    public static String login(MockMvc mockMvc, String username, String password) throws Exception {
        MvcResult result = submit(mockMvc, username, password, false);
        if (result.getResponse().getCookie(TOKEN_COOKIE) == null) {
            throw new AssertionError("login failed, redirected to " + result.getResponse().getRedirectedUrl());
        }
        return result.getResponse().getCookie(TOKEN_COOKIE).getValue();
    }
}
