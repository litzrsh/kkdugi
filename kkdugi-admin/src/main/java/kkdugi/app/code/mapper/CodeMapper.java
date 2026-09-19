package kkdugi.app.code.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import kkdugi.app.code.models.Code;

@Mapper
public interface CodeMapper {

    List<Code> findAll(@Param("path") String path,
                       @Param("langCode") String langCode);
}
