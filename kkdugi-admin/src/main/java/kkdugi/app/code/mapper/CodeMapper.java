package kkdugi.app.code.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import kkdugi.app.code.models.Code;

@Mapper
public interface CodeMapper {

    List<Code> findChildren(@Param("parentId") String parentId,
                            @Param("path") String path,
                            @Param("langCode") String langCode,
                            @Param("offset") int offset,
                            @Param("pageSize") int pageSize);
}
