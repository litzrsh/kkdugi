package kkdugi.core.security.service;

import java.util.Date;

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
 * 세션 로그인 설계(ADR 예정)의 JWT는 {@code sess_id}와 만료 시각만 담는
 * 불투명 토큰이다 — 실제 사용자 데이터(권한 등)는 여기 담지 않고
 * {@code kkdugi_session.user_dtl}에만 저장해, 서버가 즉시 세션을 무효화할
 * 수 있게 한다(자체 완결형 JWT였다면 만료 전까지 강제로 끊을 수 없다).
 */
@Service
public class JwtTokenService {

    public static final String ERR_INVALID_TOKEN = "auth.err.invalid_token";

    private static final String CLAIM_SESSION_ID = "sess_id";

    private final SecretKey key;

    public JwtTokenService(SecurityConfigurationProperties properties) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.getJwtSecret()));
    }

    public String issue(String sessionId, Date expiresAt) {
        return Jwts.builder()
                .claim(CLAIM_SESSION_ID, sessionId)
                .expiration(expiresAt)
                .signWith(key)
                .compact();
    }

    /**
     * 서명/만료를 검증하고 {@code sess_id} 클레임을 꺼낸다. 서명이 잘못됐거나
     * 만료됐거나 형식이 깨진 토큰은 전부 {@link RestfulAuthenticationException}
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
            return claims.get(CLAIM_SESSION_ID, String.class);
        } catch (JwtException | IllegalArgumentException e) {
            throw new RestfulAuthenticationException(ERR_INVALID_TOKEN);
        }
    }
}
