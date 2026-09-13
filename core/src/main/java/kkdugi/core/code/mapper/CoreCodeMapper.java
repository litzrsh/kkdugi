package kkdugi.core.code.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.core.code.models.Code;

@Mapper
public interface CoreCodeMapper {

    List<Code> findCodes(@Param("path") String path);
}
