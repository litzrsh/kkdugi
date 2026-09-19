package kkdugi.core.menu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import kkdugi.core.menu.models.MenuLang;

@Mapper
public interface MenuLangMapper {

    List<MenuLang> findByMenuId(@Param("menuId") String menuId);

    List<MenuLang> findByMenuIds(@Param("menuIds") List<String> menuIds);

    int insert(MenuLang menuLang);

    int update(MenuLang menuLang);

    int deleteByMenuIds(@Param("menuIds") List<String> menuIds);
}
