package kkdugi.core.code.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import kkdugi.core.code.models.CodeBase;

@Mapper
public interface CodeBaseMapper {

    CodeBase findById(@Param("id") String id);

    List<CodeBase> findChildren(@Param("parentId") String parentId,
                                 @Param("code") String code,
                                 @Param("path") String path,
                                 @Param("name") String name,
                                 @Param("use") String use,
                                 @Param("offset") int offset,
                                 @Param("pageSize") int pageSize);

    long countChildren(@Param("parentId") String parentId,
                        @Param("code") String code,
                        @Param("path") String path,
                        @Param("name") String name,
                        @Param("use") String use);

    List<CodeBase> findSelfAndDescendants(@Param("path") String path);

    int insert(CodeBase codeBase);

    int update(CodeBase codeBase);

    int deleteByIds(@Param("ids") List<String> ids);
}
