package kkdugi.core.security.authentication;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import kkdugi.core.security.models.SessionUser;

/**
 * 토큰으로 인증된 요청의 {@link org.springframework.security.core.Authentication}.
 * 어느 세션({@code kkdugi_session.sess_id})으로 인증됐는지를 함께 들고 있어,
 * 로그아웃 처리나 {@code SessionUtils}의 attribute 갱신이 요청 속성이나 토큰
 * 재파싱 없이 세션 ID를 얻을 수 있다.
 */
public class SessionAuthentication extends UsernamePasswordAuthenticationToken {

    private final String sessionId;

    public SessionAuthentication(SessionUser user, String sessionId) {
        super(user, null, user.getAuthorities());
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
