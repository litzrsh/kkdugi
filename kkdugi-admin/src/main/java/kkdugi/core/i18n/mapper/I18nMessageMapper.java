package kkdugi.core.i18n.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.core.i18n.models.I18nMessage;
import kkdugi.core.i18n.models.MessageCodeRow;

import java.util.List;
import java.util.Optional;

@Mapper
public interface I18nMessageMapper {

    List<I18nMessage> selectAll();

    Optional<I18nMessage> findByCodeAndLang(@Param("msgCode") String msgCode, @Param("langCode") String langCode);

    List<I18nMessage> findByCode(@Param("msgCode") String msgCode);

    List<I18nMessage> findByCodes(@Param("msgCodes") List<String> msgCodes);

    List<MessageCodeRow> searchDistinctCodes(@Param("msgCode") String msgCode,
                                              @Param("msgText") String msgText,
                                              @Param("offset") int offset,
                                              @Param("pageSize") int pageSize);

    int insert(I18nMessage message);

    int update(I18nMessage message);

    int delete(@Param("msgCode") String msgCode, @Param("langCode") String langCode);

    int deleteByCode(@Param("msgCode") String msgCode);
}
