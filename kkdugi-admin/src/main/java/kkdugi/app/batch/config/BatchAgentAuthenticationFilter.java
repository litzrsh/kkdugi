package kkdugi.app.batch.config;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.app.batch.mapper.BatchCredentialMapper;
import kkdugi.app.batch.models.BatchAgentPrincipal;
import kkdugi.app.batch.service.BatchTokenService;

/**
 * {@code Authorization: Bearer {credentialId}.{secret}}을 검증한다. 검증에 실패하면 인증 없이 통과시키고
 * 뒤의 인가 단계가 401을 낸다. <b>@Component로 등록하지 않는다</b> — Spring Boot가 Filter 빈을 전역 서블릿
 * 필터로도 등록해서, Runner 체인 밖의 모든 요청에서 실행되기 때문이다({@link BatchAgentSecurityConfig}가 직접 생성).
 */
public class BatchAgentAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final BatchTokenService tokens;
    private final BatchCredentialMapper credentialMapper;
    private final BatchProperties properties;

    public BatchAgentAuthenticationFilter(BatchTokenService tokens, BatchCredentialMapper credentialMapper,
            BatchProperties properties) {
        this.tokens = tokens;
        this.credentialMapper = credentialMapper;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            BatchTokenService.ParsedToken parsed = tokens.parse(header.substring(PREFIX.length()).trim());
            if (parsed != null) {
                credentialMapper.findForAuth(parsed.getCredentialId())
                        .filter(c -> c.isValid() && tokens.matches(parsed.getSecret(), c.getSecretHash()))
                        .ifPresent(c -> {
                            credentialMapper.touchLastUsed(c.getId(), properties.getLastUsedTouchSeconds());
                            SecurityContextHolder.getContext().setAuthentication(new BatchAgentAuthentication(
                                    new BatchAgentPrincipal(c.getRunnerId(), c.getId(), c.getType())));
                        });
            }
        }
        chain.doFilter(request, response);
    }
}
