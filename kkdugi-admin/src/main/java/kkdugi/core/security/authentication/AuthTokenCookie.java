package kkdugi.core.security.authentication;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.core.security.config.SecurityConfigurationProperties;

/**
 * 로그인 시 발급한 토큰을 담는 쿠키의 읽기/쓰기/삭제. 쿠키 이름은
 * {@link SecurityConfigurationProperties#getTokenCookieName()}.
 *
 * <p>{@code HttpOnly}를 <b>붙이지 않는다</b> — 이동 후 프론트(JS)가 쿠키에서 토큰을
 * 꺼내 {@code Authorization: Bearer} 헤더로 보내는 것이 요구사항이라 JS가 읽을
 * 수 있어야 한다. 대신 {@code SameSite=Lax}로 타 사이트발 POST에 쿠키가 실리지
 * 않게 한다. 만료 시각(Max-Age)도 두지 않는다 — 세션 만료는 서버(슬라이딩)가
 * 결정하므로 쿠키는 브라우저 세션 쿠키로 둔다.</p>
 */
@Component
public class AuthTokenCookie {

    private final SecurityConfigurationProperties properties;

    public AuthTokenCookie(SecurityConfigurationProperties properties) {
        this.properties = properties;
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.getTokenCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(request, token).maxAge(-1).build().toString());
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(request, "").maxAge(0).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder build(HttpServletRequest request, String value) {
        return ResponseCookie.from(properties.getTokenCookieName(), value)
                .path(request.getContextPath() + "/")
                .httpOnly(false)
                .secure(request.isSecure())
                .sameSite("Lax");
    }
}
