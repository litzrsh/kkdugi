package kkdugi.core.util;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

public abstract class MessageUtils {
    
    private static MessageSource messageSource;

    public static void setMessageSource(MessageSource messageSource) throws Exception {
        if (messageSource == null) throw new IllegalArgumentException("MessageSource cannot be null");
        if (MessageUtils.messageSource != null) throw new IllegalAccessException("MessageSource already been initialized");
        MessageUtils.messageSource = messageSource;
    }

    public static String getMessage(String code, Object... args) {
        if (messageSource == null) return code;
        return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
    }
}
