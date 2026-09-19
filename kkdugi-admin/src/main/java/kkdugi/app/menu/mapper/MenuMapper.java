package kkdugi.app.menu.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import kkdugi.app.menu.models.MenuLabel;

@Mapper
public interface MenuMapper {
    /** menuIds는 세션에서 얻은 비어 있지 않은 목록이어야 한다. */
    List<MenuLabel> findLabels(@Param("menuIds") List<String> menuIds, @Param("langCode") String langCode);
}
