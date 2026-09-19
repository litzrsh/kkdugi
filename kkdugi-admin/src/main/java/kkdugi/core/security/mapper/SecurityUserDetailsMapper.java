package kkdugi.core.security.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.core.security.models.Authority;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.security.models.SessionUser;

@Mapper
public interface SecurityUserDetailsMapper {

    Optional<SessionUser> findByUsername(@Param("username") String username);

    List<Authority> findAuthoritiesByUsername(@Param("username") String username);

    /**
     * 사용자가 가진 활성 권한들이 {@code kkdugi_auth_menu}에 부여한 RBAC
     * 비트마스크를 메뉴별로 BIT_OR 합산해 돌려준다(여러 권한을 동시에 갖고
     * 있으면 권한이 누적된다). 어느 활성 권한으로도 권한값이 0인 메뉴는
     * 결과에서 제외된다. SYS_ADMIN 우회(권한 매핑 없이 전체 메뉴)는 이
     * 매퍼가 아니라 {@link kkdugi.core.security.mapper.SysAdminMenuMapper}
     * (그리고 그걸 감싼 {@code SysAdminMenuService}) — 일반 사용자 경로와
     * 관리자 경로를 매퍼/서비스 단계에서부터 분리했다.
     */
    List<SessionMenu> findMenusByUsername(@Param("username") String username, @Param("langCode") String langCode);

    /**
     * {@link org.springframework.security.authentication.dao.DaoAuthenticationProvider}의
     * 인코딩 업그레이드 흐름({@code UserDetailsPasswordService})에서만 쓴다 —
     * 같은 비밀번호를 더 강한 방식으로 재해시하는 것뿐이라 pwd_stat_cd/
     * pwd_expr_dtm/last_chg_pwd_dtm은 건드리지 않는다. 사용자가 실제로
     * 비밀번호를 변경하는 기능은 별도(아직 미구현)다.
     */
    int updatePassword(@Param("userId") String userId, @Param("password") String password);

    int updateLastLoginAt(@Param("userId") String userId, @Param("lastLoginAt") LocalDateTime lastLoginAt);
}
