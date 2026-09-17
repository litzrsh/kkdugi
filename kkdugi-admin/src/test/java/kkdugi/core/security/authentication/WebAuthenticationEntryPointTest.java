package kkdugi.core.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class WebAuthenticationEntryPointTest {

    private final WebAuthenticationEntryPoint entryPoint = new WebAuthenticationEntryPoint();

    @Test
    void commence_redirectsToLogin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("no session"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FOUND.value());
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }

    @Test
    void commence_prefixesContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/kk/admin");
        request.setContextPath("/kk");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("no session"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/kk/login");
    }
}
