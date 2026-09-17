package kkdugi.core.security.authentication;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.exceptions.RestfulAuthenticationException;

/**
 * 인증되지 않은 요청이 보호된 엔드포인트에 닿았을 때(401) 호출된다.
 * {@code RestfulExceptionAdvice}는 컨트롤러 진입 이후 예외만 잡으므로
 * (필터에서 던진 예외는 대상이 아니다), Security 필터 체인 쪽 401/403은
 * 이 클래스와 {@link RestfulAccessDeniedHandler}가 같은
 * {@link ExceptionMessage} 바디 형식으로 직접 응답한다.
 */
@Component
public class RestfulAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String ERR_UNAUTHORIZED = "auth.err.unauthorized";

    private final ObjectMapper objectMapper;

    public RestfulAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ExceptionMessage message = authException instanceof RestfulAuthenticationException restfulEx
                ? restfulEx.getExceptionMessage()
                : new ExceptionMessage(ERR_UNAUTHORIZED);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), message);
    }
}
