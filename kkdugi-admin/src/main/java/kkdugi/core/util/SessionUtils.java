package kkdugi.core.util;

import java.util.List;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import kkdugi.core.security.authentication.SessionAuthentication;
import kkdugi.core.security.models.Authority;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.security.models.SessionUser;
import kkdugi.core.security.service.SessionService;

/**
 * 현재 요청의 {@link SessionUser}에 접근하는 정적 헬퍼. {@code SerialUtils}/
 * {@code MessageUtils}와 달리 Spring 빈을 등록해두는 서비스 로케이터가
 * 아니다 — {@link SecurityContextHolder}가 이미 요청 스코프의 정적
 * 접근점이라({@code BearerTokenAuthenticationFilter}가 매 요청마다 채워둔다),
 * 그 위에 얇게 얹은 조회 헬퍼다. 단, attribute를 DB에 반영하는
 * {@link #setAttribute}만은 {@link SessionService}가 필요해 {@code SerialUtils}처럼
 * 서비스가 빈 초기화 때 스스로를 등록한다.
 */
public abstract class SessionUtils {

    private static SessionService sessionService;

    public static final String ANONYMOUS_ID = "ANONYMOUS";
    public static final String ANONYMOUS_USERNAME = "anonymous";
    private static final String ANONYMOUS_NAME_CODE = "user.anonymous.name";

    /**
     * 세션 사용자를 가져온다. 로그인하지 않은 요청(토큰이 없거나, 무효/만료된
     * 토큰이거나, 세션이 이미 끊긴 경우)이면 매번 새로 만든 익명 사용자
     * 정보를 반환한다 — DB 조회 없는 값 객체라 null을 돌려주는 것보다
     * 호출부가 로그인 여부를 매번 null 체크하지 않아도 되게 해준다.
     */
    public static SessionUser getUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionUser user)) {
            return anonymous();
        }
        return user;
    }

    /**
     * 세션 사용자가 주어진 권한(role) 중 하나라도 가지고 있는지 확인한다
     * (OR 조건). {@code role}은 {@code kkdugi_auth_base.auth_role_cd} 값과
     * 정확히 일치해야 한다 — "ROLE_" 같은 접두사를 자동으로 붙이지 않는다.
     */
    public static boolean hasAuthorityByRole(String... role) {
        if (role == null || role.length == 0) {
            return false;
        }
        Set<String> roles = Set.of(role);
        return getUser().getAuthorities().stream()
                .map(Authority::getAuthority)
                .anyMatch(roles::contains);
    }

    /**
     * 세션 메뉴 목록에서 주어진 ID의 {@link SessionMenu}를 찾아 그대로
     * 돌려준다(없으면 {@code null}). {@code SYS_ADMIN}을 별도로 취급하지
     * 않는다 — {@code KkdugiUserDetailsService}가 로그인 시점에 이미
     * {@code SYS_ADMIN}의 세션 메뉴 목록을 전체 메뉴(+ 전체 비트마스크)로
     * 채워두므로, 여기서는 그냥 목록에서 찾기만 하면 된다. RBAC 맵으로
     * 바꾸는 것(구 {@code getRBAC})은 호출부가 필요할 때
     * {@code Rbac.toMap(menu.getAuthority())}로 직접 한다 — Pragma처럼
     * {@code program} 등 메뉴의 다른 필드도 함께 써야 하는 호출부가 있기
     * 때문이다.
     */
    public static SessionMenu getMenu(String menuId) {
        return getUser().getMenus().stream()
                .filter(m -> m.getId().equals(menuId))
                .findAny()
                .orElse(null);
    }

    public static void setSessionService(SessionService service) {
        sessionService = service;
    }

    /** 세션 사용자의 attribute 값을 꺼낸다(없거나 익명이면 {@code null}). 값은 JSON을
     * 거쳐 복원되므로 숫자는 Integer/Long/Double, 객체는 Map, 배열은 List로 돌아온다. */
    public static Object getAttribute(String key) {
        return getUser().getAttributes().get(key);
    }

    /**
     * 세션 사용자의 attribute를 바꾸고 DB({@code kkdugi_session.user_dtl})에도 반영한다.
     * {@code value}가 null이면 해당 키를 제거한다. 이 요청의 {@link SessionUser}에도 즉시
     * 반영되므로 같은 요청 안에서는 {@link #getAttribute}로 바로 읽힌다 — 다른 요청은
     * 다음 요청에서 DB 스냅샷을 다시 읽으며 보게 된다.
     *
     * @throws IllegalStateException 토큰으로 인증된 요청이 아닌 경우(익명 등)
     */
    public static void setAttribute(String key, Object value) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof SessionAuthentication session)) {
            throw new IllegalStateException("Cannot update attribute: no authenticated session");
        }
        if (sessionService == null) {
            throw new IllegalStateException("SessionUtils is not initialized: SessionService is missing");
        }
        sessionService.updateAttribute(session.getSessionId(), key, value);

        SessionUser user = (SessionUser) session.getPrincipal();
        if (value == null) {
            user.getAttributes().remove(key);
        } else {
            user.getAttributes().put(key, value);
        }
    }

    private static SessionUser anonymous() {
        SessionUser user = new SessionUser();
        user.setId(ANONYMOUS_ID);
        user.setUsername(ANONYMOUS_USERNAME);
        user.setName(MessageUtils.getMessage(ANONYMOUS_NAME_CODE));
        user.setAuthorities(List.of());
        return user;
    }
}
