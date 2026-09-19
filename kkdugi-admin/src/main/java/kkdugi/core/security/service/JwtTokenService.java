package kkdugi.core.security.service;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import kkdugi.core.exceptions.RestfulAuthenticationException;
import kkdugi.core.security.config.SecurityConfigurationProperties;

/**
 * 세션 로그인 설계(ADR 예정)의 JWT는 {@code sess_id}만 담는 불투명 토큰이다 —
 * 실제 사용자 데이터(권한 등)는 여기 담지 않고 {@code kkdugi_session.user_dtl}에만
 * 저장해, 서버가 즉시 세션을 무효화할 수 있게 한다(자체 완결형 JWT였다면 만료
 * 전까지 강제로 끊을 수 없다).
 *
 * <p>{@code exp} 클레임도 없다 — 슬라이딩 세션이라 만료 시각은 요청마다 뒤로
 * 밀리는 {@code kkdugi_session.exp_dtm}이 유일한 기준이고, 토큰에 고정 만료를
 * 박으면 세션이 연장돼도 토큰이 먼저 죽거나 매번 재발급해야 한다.</p>
 */
@Service
public class JwtTokenService {

    public static final String ERR_INVALID_TOKEN = "auth.err.invalid_token";

    private static final String CLAIM_SESSION_ID = "sess_id";

    private final SecretKey key;

    public JwtTokenService(SecurityConfigurationProperties properties) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.getJwtSecret()));
    }

    public String issue(String sessionId) {
        return Jwts.builder()
                .claim(CLAIM_SESSION_ID, sessionId)
                .signWith(key)
                .compact();
    }

    /**
     * 서명을 검증하고 {@code sess_id} 클레임을 꺼낸다. 서명이 잘못됐거나
     * 형식이 깨졌거나 {@code sess_id}가 없는 토큰은 전부 {@link RestfulAuthenticationException}
     * 하나로 통일해서 던진다 — 호출부(BearerTokenAuthenticationFilter)가
     * 실패 사유별로 분기할 필요가 없게 하기 위함이다.
     */
    public String extractSessionId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String sessionId = claims.get(CLAIM_SESSION_ID, String.class);
            if (sessionId == null) {
                throw new RestfulAuthenticationException(ERR_INVALID_TOKEN);
            }
            return sessionId;
        } catch (JwtException | IllegalArgumentException e) {
            throw new RestfulAuthenticationException(ERR_INVALID_TOKEN);
        }
    }
}
