package kkdugi.core.util;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

public abstract class MessageUtils {
    
    private static MessageSource messageSource;

    public static void setMessageSource(MessageSource source) throws Exception {
        if (source == null) {
            throw new IllegalArgumentException("Failed to initialize MessageSource : MessageSource is null");
        }
        if (messageSource != null) {
            throw new IllegalAccessException("Failed to initialize MessageSource : MessageSource already been initialized");
        }
        messageSource = source;
    }

    public static String getMessage(String code, Object... args) {
        if (messageSource == null) {
            return code;
        }
        return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
    }
}
