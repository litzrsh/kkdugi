package kkdugi.core.security.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.core.security.models.Authority;
import kkdugi.core.security.models.SessionUser;

@Mapper
public interface SecurityUserDetailsMapper {

    Optional<SessionUser> findByUsername(@Param("username") String username);

    List<Authority> findAuthoritiesByUsername(@Param("username") String username);

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
