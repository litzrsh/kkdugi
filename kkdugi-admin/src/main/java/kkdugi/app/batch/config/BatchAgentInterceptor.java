package kkdugi.app.batch.config;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;

/** 모든 Runner API 응답에 {@code no-store}를 붙이고 {@code X-Protocol-Version: 1}을 요구한다. */
public class BatchAgentInterceptor implements HandlerInterceptor {
    static final String PROTOCOL_HEADER = "X-Protocol-Version";
    static final String SUPPORTED_PROTOCOL = "1";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store");
        if (!SUPPORTED_PROTOCOL.equals(request.getHeader(PROTOCOL_HEADER))) {
            throw BatchException.conflict(BatchErrors.PROTOCOL_UNSUPPORTED);
        }
        return true;
    }
}
