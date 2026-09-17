package kkdugi.core.security.authentication;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import kkdugi.core.exceptions.ExceptionMessage;

/**
 * 인증은 됐지만 권한이 부족한 요청(403)에 대한 응답 —
 * {@link RestfulAuthenticationEntryPoint}와 같은 {@link ExceptionMessage}
 * 바디 형식을 쓴다.
 */
@Component
public class RestfulAccessDeniedHandler implements AccessDeniedHandler {

    public static final String ERR_ACCESS_DENIED = "auth.err.access_denied";

    private final ObjectMapper objectMapper;

    public RestfulAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new ExceptionMessage(ERR_ACCESS_DENIED));
    }
}
