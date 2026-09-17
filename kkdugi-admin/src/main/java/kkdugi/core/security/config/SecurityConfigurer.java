package kkdugi.core.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;

import jakarta.servlet.http.HttpServletResponse;
import kkdugi.core.security.authentication.RestfulAccessDeniedHandler;
import kkdugi.core.security.authentication.RestfulAuthenticationEntryPoint;
import kkdugi.core.security.authentication.SessionLogoutHandler;
import kkdugi.core.security.authentication.filter.AuthenticationProcessingFilter;
import kkdugi.core.security.authentication.filter.BearerTokenAuthenticationFilter;
import kkdugi.core.security.service.JwtTokenService;
import kkdugi.core.security.service.KkdugiUserDetailsService;
import kkdugi.core.security.service.SessionService;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfigurer {

    private static final String LOGOUT_URL = "/api/v1.0/admin/auth/logout";

    private final BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter;
    private final RestfulAuthenticationEntryPoint authenticationEntryPoint;
    private final RestfulAccessDeniedHandler accessDeniedHandler;
    private final SessionLogoutHandler sessionLogoutHandler;

    public SecurityConfigurer(BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
            RestfulAuthenticationEntryPoint authenticationEntryPoint,
            RestfulAccessDeniedHandler accessDeniedHandler,
            SessionLogoutHandler sessionLogoutHandler) {
        this.bearerTokenAuthenticationFilter = bearerTokenAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.sessionLogoutHandler = sessionLogoutHandler;
    }

    /**
     * 인가 규칙(authorizeHttpRequests)은 아직 전부 permitAll이다 — 사용자/메뉴/
     * 권한 조회 API(api-define-admin.md 4~5절)가 아직 구현되지 않아 실제로
     * 무엇을 보호해야 하는지가 정해지지 않았다. 로그인/로그아웃은 이제 붙었지만,
     * 기존 i18n/공통코드 API까지 지금 잠그면 그 컨트롤러 테스트가 전부 깨진다 —
     * 사용자/메뉴/권한 기능을 붙이는 시점에 실제 보호 대상 경로를 지정한다.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthenticationProcessingFilter authenticationProcessingFilter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // LogoutFilter는 UsernamePasswordAuthenticationFilter보다 앞선 순번이라,
                // 여기 기준으로 넣어두면 로그인 필터 자리(addFilterAt 아래)보다도
                // 먼저 실행되어 로그아웃 처리 시점에도 인증 컨텍스트/세션 ID가
                // 이미 채워져 있다.
                .addFilterBefore(bearerTokenAuthenticationFilter, LogoutFilter.class)
                .addFilterAt(authenticationProcessingFilter, UsernamePasswordAuthenticationFilter.class)
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_URL)
                        .addLogoutHandler(sessionLogoutHandler)
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

    @Bean
    AuthenticationProcessingFilter authenticationProcessingFilter(AuthenticationManager authenticationManager,
            SessionService sessionService, JwtTokenService jwtTokenService,
            KkdugiUserDetailsService userDetailsService, ObjectMapper objectMapper) {
        return new AuthenticationProcessingFilter(authenticationManager, sessionService, jwtTokenService,
                userDetailsService, objectMapper);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
