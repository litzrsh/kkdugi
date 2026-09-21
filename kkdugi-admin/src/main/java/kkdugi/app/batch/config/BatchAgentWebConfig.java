package kkdugi.app.batch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Runner API 경로에 {@link BatchAgentInterceptor}(프로토콜 버전·no-store)를 등록한다. */
@Configuration
public class BatchAgentWebConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BatchAgentInterceptor()).addPathPatterns(BatchAgentSecurityConfig.AGENT_PATH + "/**");
    }
}
