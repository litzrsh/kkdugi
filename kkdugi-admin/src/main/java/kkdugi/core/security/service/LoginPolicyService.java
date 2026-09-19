package kkdugi.core.security.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Objects;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.core.enums.PasswordStatus;
import kkdugi.core.enums.UserStatus;
import kkdugi.core.exceptions.RestfulAuthenticationException;
import kkdugi.core.security.config.SecurityConfigurationProperties;
import kkdugi.core.security.mapper.SecurityUserDetailsMapper;
import kkdugi.core.security.models.Session;
import kkdugi.core.security.models.SessionUser;

/** 자격 증명 확인 뒤 상태 검사·비밀번호 처리·세션 생성을 하나의 트랜잭션으로 실행한다. */
@Service
public class LoginPolicyService {
    private final SecurityUserDetailsMapper mapper;
    private final SessionService sessions;
    private final PasswordEncoder encoder;
    private final SecurityConfigurationProperties properties;

    public LoginPolicyService(SecurityUserDetailsMapper mapper, SessionService sessions,
            PasswordEncoder encoder, SecurityConfigurationProperties properties) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.encoder = encoder;
        this.properties = properties;
    }

    @Transactional
    public Session complete(SessionUser authenticated, boolean force, String action, String newPassword) {
        SessionUser current = mapper.findByIdForUpdate(authenticated.getId())
                .orElseThrow(() -> new RestfulAuthenticationException("system.err.default"));
        // 인증 직후 관리자 초기화/삭제/상태 변경과 경합하면 이전 자격 증명을 사용하지 않는다.
        if (!Objects.equals(current.getPassword(), authenticated.getPassword())) {
            throw new RestfulAuthenticationException("system.err.default");
        }
        if (current.isPending()) throw new RestfulAuthenticationException("auth.err.pending");
        if (current.isDormant()) {
            // TODO(mail): 이메일 서비스 연결 후, 일회성·만료형 휴면 해제 링크 발급/발송/검증을 구현한다.
            // 현재는 메일을 보냈다고 응답하거나 휴면 상태를 자동 해제하지 않는다.
            throw new RestfulAuthenticationException("auth.err.dormant");
        }
        if (current.isResigned()) throw new RestfulAuthenticationException("auth.err.resigned");
        if (current.isSuspended()) throw new RestfulAuthenticationException("auth.err.suspended");
        if (current.getStatus() != UserStatus.NORM) throw new RestfulAuthenticationException("auth.err.user_unavailable");

        boolean required = current.isNewPassword();
        boolean expired = current.isPasswordExpired();
        boolean change = "change".equals(action), extend = "extend".equals(action);
        if (action != null && !action.isBlank() && !change && !extend) {
            throw new RestfulAuthenticationException("auth.err.malformed_request");
        }
        if (required && !change) throw new RestfulAuthenticationException("auth.err.password_required");
        if (!required && expired && !change && !extend) throw new RestfulAuthenticationException("auth.err.password_expired");
        if ((change || extend) && !required && !expired) {
            throw new RestfulAuthenticationException("auth.err.malformed_request");
        }
        if (change || extend) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiry = now.plus(properties.getPasswordValidity());
            if (change) {
                if (newPassword == null || newPassword.isBlank() || newPassword.length() < 8
                        || newPassword.getBytes(StandardCharsets.UTF_8).length > 72
                        || encoder.matches(newPassword, current.getPassword())) {
                    throw new RestfulAuthenticationException("auth.err.password_invalid");
                }
                String encoded = encoder.encode(newPassword);
                mapper.changeLoginPassword(current.getId(), encoded, now, expiry);
                authenticated.setPassword(encoded);
                authenticated.setLastChangePasswordAt(new Date());
            } else {
                mapper.extendLoginPassword(current.getId(), expiry);
            }
            authenticated.setPasswordStatus(PasswordStatus.NORM);
            authenticated.setPasswordExpiredAt(java.sql.Timestamp.valueOf(expiry));
        }
        authenticated.setStatus(current.getStatus());
        // 중복 로그인 확인이 필요하면 예외로 위 비밀번호 변경/연장도 롤백한다.
        Session session = sessions.createSession(authenticated, force);
        mapper.updateLastLoginAt(authenticated.getId(), LocalDateTime.now());
        return session;
    }
}
