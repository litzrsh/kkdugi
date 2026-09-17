package kkdugi.core.code.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import kkdugi.core.code.models.CodeLang;

@Mapper
public interface CodeLangMapper {

    List<CodeLang> findByCodeId(@Param("codeId") String codeId);

    List<CodeLang> findByCodeIds(@Param("codeIds") List<String> codeIds);

    int insert(CodeLang codeLang);

    int update(CodeLang codeLang);

    int deleteByCodeIds(@Param("codeIds") List<String> codeIds);
}
