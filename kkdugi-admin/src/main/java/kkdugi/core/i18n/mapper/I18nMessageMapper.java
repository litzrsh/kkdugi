package kkdugi.core.i18n.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.core.i18n.models.I18nMessage;

import java.util.List;

@Mapper
public interface I18nMessageMapper {

    List<I18nMessage> selectAll();

    I18nMessage findByCodeAndLang(@Param("msgCode") String msgCode, @Param("langCode") String langCode);

    List<I18nMessage> findByCode(@Param("msgCode") String msgCode);

    List<I18nMessage> findByCodes(@Param("msgCodes") List<String> msgCodes);

    List<String> searchDistinctCodes(@Param("msgCode") String msgCode,
                                      @Param("msgText") String msgText,
                                      @Param("offset") int offset,
                                      @Param("pageSize") int pageSize);

    long countDistinctCodes(@Param("msgCode") String msgCode, @Param("msgText") String msgText);

    int insert(I18nMessage message);

    int update(I18nMessage message);

    int delete(@Param("msgCode") String msgCode, @Param("langCode") String langCode);

    int deleteByCode(@Param("msgCode") String msgCode);
}
