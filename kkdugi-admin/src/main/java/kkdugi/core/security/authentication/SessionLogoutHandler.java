package kkdugi.core.security.authentication;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.core.security.authentication.filter.BearerTokenAuthenticationFilter;
import kkdugi.core.security.service.SessionService;

/**
 * 로그아웃 시 이 요청을 인증했던 세션 하나만 끊는다({@code allowMultiple=true}일
 * 때 같은 사용자의 다른 세션까지 함께 끊으면 안 되므로 사용자 ID가 아니라
 * 세션 ID 기준). 세션 ID는 {@link BearerTokenAuthenticationFilter}가 이번
 * 요청을 인증하면서 이미 검증해 요청 속성에 남겨둔 값을 그대로 쓴다.
 */
@Component
public class SessionLogoutHandler implements LogoutHandler {

    private final SessionService sessionService;

    public SessionLogoutHandler(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        String sessionId = BearerTokenAuthenticationFilter.getSessionId(request);
        if (sessionId != null) {
            sessionService.invalidate(sessionId);
        }
    }
}
