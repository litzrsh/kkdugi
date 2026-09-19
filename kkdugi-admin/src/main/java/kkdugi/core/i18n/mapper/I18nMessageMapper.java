package kkdugi.core.i18n.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.core.i18n.models.I18nMessage;

import java.util.List;
import java.util.Optional;

/** Spring {@code MessageSource}(캐시 적재/갱신)가 쓰는 read 전용 쿼리. 관리자 쓰기는 {@code AdminMessageMapper}. */
@Mapper
public interface I18nMessageMapper {

    List<I18nMessage> selectAll();

    Optional<I18nMessage> findByCodeAndLang(@Param("msgCode") String msgCode, @Param("langCode") String langCode);
}
