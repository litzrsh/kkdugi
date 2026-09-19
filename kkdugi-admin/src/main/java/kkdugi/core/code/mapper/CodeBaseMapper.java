package kkdugi.core.code.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

import kkdugi.core.code.models.CodeBase;

@Mapper
public interface CodeBaseMapper {

    Optional<CodeBase> findById(@Param("id") String id);

    List<CodeBase> findChildren(@Param("parentId") String parentId,
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
}
