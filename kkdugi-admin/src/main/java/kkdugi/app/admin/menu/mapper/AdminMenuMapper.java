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
}
