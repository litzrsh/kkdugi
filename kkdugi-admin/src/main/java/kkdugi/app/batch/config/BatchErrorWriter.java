package kkdugi.app.batch.config;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.core.exceptions.ExceptionMessage;
import tools.jackson.databind.ObjectMapper;

/** Runner 체인의 401/403과 본문 한도 필터의 413을 계약의 {@code {code, message}} JSON으로 쓴다. */
public class BatchErrorWriter {

    private final ObjectMapper objectMapper;

    public BatchErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void unauthorized(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, BatchErrors.CREDENTIAL_INVALID);
    }

    public void forbidden(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, BatchErrors.RUNNER_FORBIDDEN);
    }

    public void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), new ExceptionMessage(code));
    }
}
