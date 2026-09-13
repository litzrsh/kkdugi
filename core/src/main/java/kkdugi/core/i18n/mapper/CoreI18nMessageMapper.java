package kkdugi.core.i18n.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import kkdugi.core.i18n.models.I18nMessage;

@Mapper
public interface CoreI18nMessageMapper {

    List<I18nMessage> findAll();
}
