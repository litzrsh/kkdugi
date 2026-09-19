package kkdugi.core.security.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import kkdugi.core.Constants;
import kkdugi.core.security.mapper.SecurityUserDetailsMapper;
import kkdugi.core.security.models.Authority;
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

    // 세션 스냅샷의 메뉴 제목은 로그인 시점에 한 언어로 고정된다 — 로그인
    // 요청은 Security 필터 체인에서 DispatcherServlet의 LocaleResolver보다
    // 먼저 처리되어 LocaleContextHolder를 쓸 수 없다(PragmaController의
    // admin-ui 번들도 같은 이유로 ko_KR을 고정한다). 다국어 세션 스냅샷은
    // 필요해지면 그때 다룬다.
    private static final String SESSION_LANG = "ko_KR";

    private final SecurityUserDetailsMapper mapper;
    private final SysAdminMenuService sysAdminMenuService;

    public KkdugiUserDetailsService(SecurityUserDetailsMapper mapper, SysAdminMenuService sysAdminMenuService) {
        this.mapper = mapper;
        this.sysAdminMenuService = sysAdminMenuService;
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
        List<Authority> authorities = mapper.findAuthoritiesByUsername(username);
        user.setAuthorities(authorities);
        // SYS_ADMIN은 SessionUtils.hasAuthorityByRole()로 하는 다른 권한
        // 체크가 항상 전체 권한으로 우회되는 것과 동일하게, 메뉴 "목록"
        // 자체도 kkdugi_auth_menu 매핑 없이 전체를 본다 — 그렇지 않으면
        // 메뉴/권한 매핑이 아직 없는 상태에서는 SYS_ADMIN조차 빈 메뉴
        // 목록을 받는다. 이 관리자 우회 자체는 SysAdminMenuService(별도
        // 매퍼/서비스)로 분리돼 있다 — 일반 사용자 경로(mapper.findMenusByUsername)와
        // 관리자 경로가 하나의 매퍼/서비스에 섞이지 않도록.
        boolean isSysAdmin = authorities.stream()
                .map(Authority::getAuthority)
                .anyMatch(Constants.SYS_ADMIN::equals);
        user.setMenus(isSysAdmin
                ? sysAdminMenuService.findAllMenus(SESSION_LANG)
                : mapper.findMenusByUsername(username, SESSION_LANG));
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
