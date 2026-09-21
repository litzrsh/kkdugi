package kkdugi.app.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;

import kkdugi.KkdugiAdminApplication;

/**
 * 본문 한도 필터가 서블릿 컨테이너에서 Spring Security 필터 체인 <b>뒤</b>에 실행되도록 등록됐는지 검사한다
 * (인증 → 본문 한도 순서). MockMvc 테스트는 필터 순서를 직접 흉내 내므로, 운영 컨테이너가 사용하는 등록 순서는 여기서 확인한다.
 *
 * <p>실제 Tomcat에 HTTP 요청을 보내는 검증(인증 없는 1 MiB 초과 요청이 401, 인증된 초과 요청과 chunked 초과 요청이 413,
 * 정확히 한도인 본문은 통과)은 이 프로젝트의 테스트 JVM에서 자동화할 수 없다 — {@code MessageUtils}/{@code SerialUtils} 같은
 * core의 static 싱글톤이 두 번째 {@code ApplicationContext}를 거부해서 {@code RANDOM_PORT} 컨텍스트를 만들 수 없다.
 * 그 검증은 S1 구현 때 실제 서버로 한 차례 수행했다(docs/superpowers/plans의 Task 8 참고).</p>
 */
@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchBodyLimitOrderTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theBodyLimitFilterRunsAfterTheSpringSecurityFilterChain() {
        Map<String, DelegatingFilterProxyRegistrationBean> security =
                context.getBeansOfType(DelegatingFilterProxyRegistrationBean.class);
        FilterRegistrationBean<?> bodyLimit =
                context.getBean("batchBodyLimitFilterRegistration", FilterRegistrationBean.class);

        assertThat(security).as("Spring Security 필터 체인 등록").isNotEmpty();
        security.forEach((name, registration) -> assertThat(bodyLimit.getOrder())
                .as("본문 한도 필터 순서는 %s(%d)보다 뒤여야 한다", name, registration.getOrder())
                .isGreaterThan(registration.getOrder()));
    }

    @Test
    void theBodyLimitFilterOnlyCoversTheBatchPaths() {
        FilterRegistrationBean<?> bodyLimit =
                context.getBean("batchBodyLimitFilterRegistration", FilterRegistrationBean.class);

        assertThat(bodyLimit.getUrlPatterns()).containsExactlyInAnyOrder(
                "/api/v1.0/batch-agent/*", "/api/v1.0/admin/batch/*");
    }
}
