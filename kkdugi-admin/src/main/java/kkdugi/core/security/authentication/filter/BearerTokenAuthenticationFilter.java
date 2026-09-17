package kkdugi.core.security.authentication.filter;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import kkdugi.core.security.models.Session;
import kkdugi.core.security.models.SessionUser;
import kkdugi.core.security.service.JwtTokenService;
import kkdugi.core.security.service.SessionService;

/**
 * 매 요청마다 {@code Authorization: Bearer <jwt>} 헤더를 읽어 인증한다
 * (쿠키가 아니라 헤더 — API는 헤더로 통일). JWT에서 {@code sess_id}만
 * 꺼내고, 실제 사용자 스냅샷은 {@link SessionService}로 DB에서 조회한다 —
 * 이렇게 해야 세션 무효화(로그아웃, 강제 종료)가 즉시 반영된다.
 *
 * <p>토큰이 없거나, 서명/만료 검증에 실패하거나, 세션이 이미 만료/삭제된
 * 경우엔 이 필터가 직접 401을 응답하지 않고 그냥 인증 없이 다음 필터로
 * 넘긴다 — 보호된 엔드포인트라면 뒤의 인가 단계에서 걸려
 * {@code RestfulAuthenticationEntryPoint}가 401을 내려준다. 공개
 * 엔드포인트라면 그대로 익명으로 통과한다.</p>
 */
@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    private static final String SESSION_ID_ATTRIBUTE = BearerTokenAuthenticationFilter.class.getName() + ".SESSION_ID";

    private final JwtTokenService jwtTokenService;
    private final SessionService sessionService;

    public BearerTokenAuthenticationFilter(JwtTokenService jwtTokenService, SessionService sessionService) {
        this.jwtTokenService = jwtTokenService;
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            authenticate(token, request);
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length());
        }
        return null;
    }

    private void authenticate(String token, HttpServletRequest request) {
        String sessionId;
        try {
            sessionId = jwtTokenService.extractSessionId(token);
        } catch (RuntimeException e) {
            return;
        }

        Session session = sessionService.findSession(sessionId);
        if (session == null) {
            return;
        }

        SessionUser user = session.getDetails();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(SESSION_ID_ATTRIBUTE, session.getId());
    }

    /** 로그아웃 처리(SessionLogoutHandler)가 현재 요청의 세션 ID를 다시 JWT를
     * 파싱하지 않고 재사용할 수 있도록 요청 속성에 남겨둔 값을 꺼낸다. */
    public static String getSessionId(HttpServletRequest request) {
        Object value = request.getAttribute(SESSION_ID_ATTRIBUTE);
        return value instanceof String sessionId ? sessionId : null;
    }
}
