package kkdugi.core.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.MediaType;
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
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import jakarta.servlet.http.HttpServletResponse;
import kkdugi.core.security.authentication.AuthTokenCookie;
import kkdugi.core.security.authentication.RestfulAccessDeniedHandler;
import kkdugi.core.security.authentication.RestfulAuthenticationEntryPoint;
import kkdugi.core.security.authentication.SessionLogoutHandler;
import kkdugi.core.security.authentication.WebAuthenticationEntryPoint;
import kkdugi.core.security.authentication.filter.AuthenticationProcessingFilter;
import kkdugi.core.security.authentication.filter.BearerTokenAuthenticationFilter;
import kkdugi.core.security.service.JwtTokenService;
import kkdugi.core.security.service.LoginPolicyService;

@EnableAspectJAutoProxy
@Configuration
public class SecurityConfigurer {

    private static final String LOGOUT_URL = "/api/v1.0/auth/logout";

    private final BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter;
    private final RestfulAuthenticationEntryPoint authenticationEntryPoint;
    private final WebAuthenticationEntryPoint webAuthenticationEntryPoint;
    private final RestfulAccessDeniedHandler accessDeniedHandler;
    private final SessionLogoutHandler sessionLogoutHandler;

    public SecurityConfigurer(BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
            RestfulAuthenticationEntryPoint authenticationEntryPoint,
            WebAuthenticationEntryPoint webAuthenticationEntryPoint,
            RestfulAccessDeniedHandler accessDeniedHandler,
            SessionLogoutHandler sessionLogoutHandler) {
        this.bearerTokenAuthenticationFilter = bearerTokenAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.webAuthenticationEntryPoint = webAuthenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.sessionLogoutHandler = sessionLogoutHandler;
    }

    /** API/Pragma authentication is enforced here; annotated methods enforce menu and role permissions. */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthenticationProcessingFilter authenticationProcessingFilter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        // 브라우저 HTML 내비게이션(Accept: text/html)은 /login으로
                        // 리다이렉트하고, 그 외(fetch/JSON API 호출)는 기존
                        // JSON ExceptionMessage 응답을 그대로 쓴다.
                        .defaultAuthenticationEntryPointFor(webAuthenticationEntryPoint,
                                request -> !"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))
                                        && new MediaTypeRequestMatcher(MediaType.TEXT_HTML).matches(request))
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
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1.0/auth/login", LOGOUT_URL).permitAll()
                        .requestMatchers("/api/**", "/pragma/**").authenticated()
                        .anyRequest().permitAll())
                .build();
    }

    @Bean
    AuthenticationProcessingFilter authenticationProcessingFilter(AuthenticationManager authenticationManager,
            JwtTokenService jwtTokenService,
            LoginPolicyService loginPolicy, AuthTokenCookie tokenCookie) {
        return new AuthenticationProcessingFilter(authenticationManager, jwtTokenService,
                loginPolicy, tokenCookie);
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
