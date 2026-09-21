package kkdugi.app.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import kkdugi.app.batch.mapper.BatchCredentialMapper;
import kkdugi.app.batch.service.BatchTokenService;
import tools.jackson.databind.ObjectMapper;

/**
 * Runner API 전용 보안 체인. core.security의 사용자 JWT 체인은 {@code /api/**} 전체를 잡으므로
 * 이 체인이 더 높은 순위({@code @Order(1)})로 {@code /api/v1.0/batch-agent/**}만 먼저 가져간다.
 * core는 배치를 모른다 — 라이브러리로 분리할 때 이 클래스가 함께 나간다.
 */
@Configuration
public class BatchAgentSecurityConfig {

    public static final String AGENT_PATH = "/api/v1.0/batch-agent";

    @Bean
    @Order(1)
    SecurityFilterChain batchAgentFilterChain(HttpSecurity http, BatchTokenService tokens,
            BatchCredentialMapper credentialMapper, BatchProperties properties, ObjectMapper objectMapper)
            throws Exception {
        BatchAgentAuthenticationFilter filter = new BatchAgentAuthenticationFilter(tokens, credentialMapper, properties);
        BatchErrorWriter errors = new BatchErrorWriter(objectMapper);
        return http
                .securityMatcher(AGENT_PATH + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(errors::unauthorized)
                        .accessDeniedHandler(errors::forbidden))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, AGENT_PATH + "/registrations").hasAuthority("BATCH_ENROLLMENT")
                        .anyRequest().hasAuthority("BATCH_ACCESS"))
                .build();
    }
}
