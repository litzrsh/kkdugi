package kkdugi.core.i18n.service;

import org.springframework.context.support.AbstractMessageSource;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import kkdugi.core.i18n.mapper.I18nMessageMapper;
import kkdugi.core.i18n.models.I18nMessage;

public class KkdugiMessageSource extends AbstractMessageSource {

    private final I18nMessageMapper mapper;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public KkdugiMessageSource(I18nMessageMapper mapper) {
        this.mapper = mapper;
    }

    public void loadAll() {
        cache.clear();
        List<I18nMessage> messages = mapper.selectAll();
        for (I18nMessage message : messages) {
            if (message.getMsgText() != null) {
                cache.put(cacheKey(message.getMsgCode(), message.getLangCode()), message.getMsgText());
            }
        }
    }

    public void refresh(String msgCode, String langCode) {
        String key = cacheKey(msgCode, langCode);
        mapper.findByCodeAndLang(msgCode, langCode)
                .map(I18nMessage::getMsgText)
                .ifPresentOrElse(text -> cache.put(key, text), () -> cache.remove(key));
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        String value = cache.get(cacheKey(code, locale.toString()));
        if (value == null) {
            return null;
        }
        return new MessageFormat(value, locale);
    }

    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        return cache.get(cacheKey(code, locale.toString()));
    }

    private static String cacheKey(String msgCode, String langCode) {
        return msgCode + ' ' + langCode;
    }
}
