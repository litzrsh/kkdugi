package kkdugi.core.i18n.service;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import kkdugi.core.i18n.mapper.CoreI18nMessageMapper;
import kkdugi.core.i18n.models.I18nMessage;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CoreI18nMessageService {

    private final CoreI18nMessageMapper coreI18nMessageMapper;

    @Transactional(readOnly = true)
    public List<I18nMessage> findAll() {
        return coreI18nMessageMapper.findAll();
    }
}
