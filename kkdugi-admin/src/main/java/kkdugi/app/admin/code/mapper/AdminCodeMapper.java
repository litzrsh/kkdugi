package kkdugi.app.admin.code.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

import kkdugi.app.admin.code.models.CodeBase;
import kkdugi.app.admin.code.models.CodeLang;

@Mapper
public interface AdminCodeMapper {

    Optional<CodeBase> findById(@Param("id") String id);

    List<CodeBase> search(@Param("parentId") String parentId,
                          @Param("code") String code,
                          @Param("path") String path,
                          @Param("name") String name,
                          @Param("use") String use,
                          @Param("offset") int offset,
                          @Param("pageSize") int pageSize);

    List<CodeBase> findSelfAndDescendants(@Param("path") String path);

    int insert(CodeBase codeBase);

    int update(CodeBase codeBase);

    int deleteByIds(@Param("ids") List<String> ids);

    List<CodeLang> findLangsByCodeId(@Param("codeId") String codeId);

    List<CodeLang> findLangsByCodeIds(@Param("codeIds") List<String> codeIds);

    int insertLang(CodeLang codeLang);

    int updateLang(CodeLang codeLang);

    int deleteLangByCodeIds(@Param("codeIds") List<String> codeIds);
}
