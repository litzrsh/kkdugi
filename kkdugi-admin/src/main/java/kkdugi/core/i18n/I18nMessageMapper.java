package kkdugi.core.i18n;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface I18nMessageMapper {

    List<I18nMessage> selectAll();

    I18nMessage findByCodeAndLang(@Param("msgCd") String msgCd, @Param("langCd") String langCd);

    List<I18nMessage> search(@Param("msgCd") String msgCd,
                              @Param("langCd") String langCd,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    long count(@Param("msgCd") String msgCd, @Param("langCd") String langCd);

    int insert(I18nMessage message);

    int update(I18nMessage message);

    int delete(@Param("msgCd") String msgCd, @Param("langCd") String langCd);
}
