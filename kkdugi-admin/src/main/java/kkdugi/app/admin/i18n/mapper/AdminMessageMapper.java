package kkdugi.app.admin.i18n.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import kkdugi.app.admin.i18n.models.MessageCodeRow;
import kkdugi.core.i18n.models.I18nMessage;

@Mapper
public interface AdminMessageMapper {

    List<MessageCodeRow> searchDistinctCodes(@Param("msgCode") String msgCode,
                                             @Param("msgText") String msgText,
                                             @Param("offset") int offset,
                                             @Param("pageSize") int pageSize);

    List<I18nMessage> findByCode(@Param("msgCode") String msgCode);

    List<I18nMessage> findByCodes(@Param("msgCodes") List<String> msgCodes);

    int insert(I18nMessage message);

    int update(I18nMessage message);

    int deleteByCode(@Param("msgCode") String msgCode);
}
