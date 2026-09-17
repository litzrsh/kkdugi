package kkdugi.core.security.service;

import java.time.LocalDateTime;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import kkdugi.core.security.mapper.SecurityUserDetailsMapper;
import kkdugi.core.security.models.SessionUser;

/**
 * {@link UserDetailsService}(로그인 시 사용자 조회)와
 * {@link UserDetailsPasswordService}(비밀번호 인코딩 업그레이드 시 재저장)를
 * 하나로 통합한 구현체 — {@link org.springframework.security.authentication.dao.DaoAuthenticationProvider}가
 * 이 하나의 빈으로 두 역할을 모두 처리한다.
 */
@Service
public class KkdugiUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    public static final String ERR_NOT_FOUND = "user.err.not_found";

    private final SecurityUserDetailsMapper mapper;

    public KkdugiUserDetailsService(SecurityUserDetailsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // DaoAuthenticationProvider의 hideUserNotFoundExceptions(기본 true)가
        // 이 예외를 "잘못된 자격 증명"으로 뭉뚱그려 바깥으로 내보내므로,
        // 아이디 존재 여부가 클라이언트에 노출되지 않는다 - 여기서 다른
        // 종류의 AuthenticationException을 던지면 이 보호가 우회되니
        // 주의한다.
        SessionUser user = mapper.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(ERR_NOT_FOUND));
        user.setAuthorities(mapper.findAuthoritiesByUsername(username));
        return user;
    }

    @Override
    public UserDetails updatePassword(UserDetails userDetails, String newPassword) {
        SessionUser user = (SessionUser) userDetails;
        mapper.updatePassword(user.getId(), newPassword);
        user.setPassword(newPassword);
        return user;
    }

    /** 로그인 필터(Phase 4)가 인증 성공 직후 호출해 마지막 로그인 시각을 남긴다. */
    public void recordSuccessfulLogin(String userId) {
        mapper.updateLastLoginAt(userId, LocalDateTime.now());
    }
}
