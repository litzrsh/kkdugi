package kkdugi.core.i18n;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.support.AbstractMessageSource;

import kkdugi.core.i18n.models.I18nMessage;
import kkdugi.core.i18n.service.CoreI18nMessageService;

public class JdbcRoutableMessageSource extends AbstractMessageSource {

    private final CoreI18nMessageService coreI18nMessageService;

    private volatile Map<String, Map<String, String>> messagesByLangAndCode = Map.of();

    public JdbcRoutableMessageSource(CoreI18nMessageService coreI18nMessageService) {
        this.coreI18nMessageService = coreI18nMessageService;
        reload();
    }

    public final void reload() {
        this.messagesByLangAndCode = convert(coreI18nMessageService.findAll());
    }

    Map<String, Map<String, String>> convert(List<I18nMessage> messages) {
        Map<String, Map<String, String>> result = new HashMap<>();
        for (I18nMessage message : messages) {
            String resolved = (message.getMessage() == null || message.getMessage().isBlank())
                    ? message.getCode()
                    : message.getMessage();
            result.computeIfAbsent(message.getLang(), lang -> new HashMap<>())
                    .put(message.getCode(), resolved);
        }
        return result;
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        String text = lookup(code, locale);
        return (text != null) ? new MessageFormat(text, locale) : null;
    }

    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        return lookup(code, locale);
    }

    private String lookup(String code, Locale locale) {
        Map<String, String> messagesByCode = messagesByLangAndCode.get(locale.getLanguage());
        return (messagesByCode != null) ? messagesByCode.get(code) : null;
    }
}
