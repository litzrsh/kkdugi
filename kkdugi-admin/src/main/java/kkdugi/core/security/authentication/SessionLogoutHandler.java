package kkdugi.core.security.authentication;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.core.security.models.SessionUser;
import kkdugi.core.security.service.SessionService;

/**
 * 로그아웃 시 세션을 끊고 토큰 쿠키를 지운다. 어떤 세션을 끊을지는
 * {@link SessionService#logout}이 {@code allowMultiple}에 따라 정한다 —
 * false면 사용자 ID 기준으로 모든 세션, true면 이 요청의 세션 ID 하나만.
 * 세션 ID와 사용자 ID는 {@code BearerTokenAuthenticationFilter}가 이 요청을
 * 인증하면서 남긴 {@link SessionAuthentication}에서 꺼낸다.
 */
@Component
public class SessionLogoutHandler implements LogoutHandler {

    private final SessionService sessionService;
    private final AuthTokenCookie tokenCookie;

    public SessionLogoutHandler(SessionService sessionService, AuthTokenCookie tokenCookie) {
        this.sessionService = sessionService;
        this.tokenCookie = tokenCookie;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication instanceof SessionAuthentication session
                && session.getPrincipal() instanceof SessionUser user) {
            sessionService.logout(session.getSessionId(), user.getId());
        }
        tokenCookie.clear(request, response);
    }
}
