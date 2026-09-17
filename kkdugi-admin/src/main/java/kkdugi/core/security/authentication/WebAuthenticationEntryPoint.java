package kkdugi.core.security.authentication;

import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 브라우저의 일반 페이지 이동(Accept: text/html)이 인증 없이 보호된 경로에
 * 닿았을 때 {@code /login}으로 리다이렉트한다.
 *
 * <p>{@link RestfulAuthenticationEntryPoint}(JSON {@link kkdugi.core.exceptions.ExceptionMessage}
 * 응답)는 fetch 기반 API 호출용이다 — 로그인 이후 관리자 API는 매 요청 Bearer
 * 헤더를 붙이고, 401을 받으면 클라이언트 JS(api.mjs)가 직접 토큰을 지우고
 * {@code /login}으로 이동한다(ADR-0008). 하지만 HTML 페이지 자체를 서버가
 * 보호하는 경로가 생기면(현재 {@code /admin}은 공개다) 그 경로로 로그인 없이
 * 들어온 브라우저 내비게이션에 JSON 바디를 그대로 보여주는 건 깨진 화면이다 —
 * 이 경우엔 이 엔트리포인트가 대신 사용돼야 한다.
 *
 * <p>어느 쪽을 쓸지는 {@code SecurityConfigurer}가 요청의 {@code Accept}
 * 헤더(text/html) 기준으로 위임한다.</p>
 */
@Component
public class WebAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {

    public WebAuthenticationEntryPoint() {
        super("/login");
    }
}
