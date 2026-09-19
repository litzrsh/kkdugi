package kkdugi.app.admin.menu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

import kkdugi.app.admin.menu.models.MenuBase;
import kkdugi.app.admin.menu.models.MenuLang;

@Mapper
public interface AdminMenuMapper {

    Optional<MenuBase> findById(@Param("id") String id);

    List<MenuBase> findAll();

    List<MenuBase> findSelfAndDescendants(@Param("path") String path);

    int insert(MenuBase menuBase);

    int update(MenuBase menuBase);

    int deleteByIds(@Param("ids") List<String> ids);

    List<MenuLang> findLangsByMenuId(@Param("menuId") String menuId);

    List<MenuLang> findLangsByMenuIds(@Param("menuIds") List<String> menuIds);

    int insertLang(MenuLang menuLang);

    int updateLang(MenuLang menuLang);

    int deleteLangByMenuIds(@Param("menuIds") List<String> menuIds);

    /**
     * 메뉴 삭제 전에 해당 메뉴들에 걸린 권한-메뉴 부여(kkdugi_auth_menu)를 지운다. 권한(kkdugi_auth_base) 자체는 건드리지 않는다.
     * {@code menuIds}는 비어 있으면 안 된다(빈 목록은 {@code IN ()} 구문 오류).
     */
    int deleteAuthMenusByMenuIds(@Param("menuIds") List<String> menuIds);
}
