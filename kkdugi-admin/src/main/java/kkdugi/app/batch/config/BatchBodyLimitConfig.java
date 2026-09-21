package kkdugi.app.batch.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/**
 * 본문 한도 필터를 배치 경로에만 서블릿 필터로 등록한다. {@code FilterRegistrationBean}으로 감싸므로 필터 자체가 전역 빈이
 * 되지 않는다. 처리 순서는 인증(Spring Security 체인) → 본문 한도 → 프로토콜 버전/메뉴 권한이다.
 */
@Configuration
public class BatchBodyLimitConfig {

    /** Spring Security 서블릿 필터 순서(-100)보다 뒤. */
    private static final int AFTER_SECURITY = -90;

    @Bean
    FilterRegistrationBean<BatchBodyLimitFilter> batchBodyLimitFilterRegistration(BatchProperties properties,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<BatchBodyLimitFilter> registration = new FilterRegistrationBean<>(
                new BatchBodyLimitFilter(properties.getJsonBytes(), new BatchErrorWriter(objectMapper)));
        registration.addUrlPatterns(BatchAgentSecurityConfig.AGENT_PATH + "/*", "/api/v1.0/admin/batch/*");
        registration.setOrder(AFTER_SECURITY);
        return registration;
    }
}
