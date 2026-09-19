package kkdugi.core.menu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

import kkdugi.core.menu.models.MenuBase;

@Mapper
public interface MenuBaseMapper {

    Optional<MenuBase> findById(@Param("id") String id);

    List<MenuBase> findAll();

    List<MenuBase> findSelfAndDescendants(@Param("path") String path);

    int insert(MenuBase menuBase);

    int update(MenuBase menuBase);

    int deleteByIds(@Param("ids") List<String> ids);
}
