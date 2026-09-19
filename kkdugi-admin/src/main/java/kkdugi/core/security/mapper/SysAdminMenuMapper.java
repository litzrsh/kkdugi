package kkdugi.core.security.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import kkdugi.core.security.models.SessionMenu;

/**
 * SYS_ADMIN 세션 메뉴 로딩 전용 — {@link SecurityUserDetailsMapper}(일반
 * 사용자가 로그인/세션에서 쓰는 쿼리)와 분리했다. {@code kkdugi_auth_menu}
 * 매핑을 완전히 우회해 전체 메뉴를 돌려주는 것 자체가 일반 사용자 경로와는
 * 별개의 관리자 전용 동작이기 때문이다.
 */
@Mapper
public interface SysAdminMenuMapper {

    List<SessionMenu> findAllMenus(@Param("langCode") String langCode);
}
