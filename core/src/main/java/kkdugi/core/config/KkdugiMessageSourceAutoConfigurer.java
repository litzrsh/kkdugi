package kkdugi.core.config;

import java.nio.charset.StandardCharsets;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import kkdugi.core.i18n.JdbcRoutableMessageSource;
import kkdugi.core.i18n.service.CoreI18nMessageService;
import kkdugi.core.util.MessageUtils;

@Configuration
public class KkdugiMessageSourceAutoConfigurer {

    private static final String DEFAULT_MESSAGE_PATH = "classpath:messages/messages";

    @Bean
    JdbcRoutableMessageSource jdbcRoutableMessageSource(CoreI18nMessageService coreI18nMessageService) {
        return new JdbcRoutableMessageSource(coreI18nMessageService);
    }

    @Bean
    @Primary
    MessageSource messageSource(JdbcRoutableMessageSource jdbcRoutableMessageSource) throws Exception {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename(DEFAULT_MESSAGE_PATH);
        messageSource.setCacheMillis(1000);
        messageSource.setDefaultCharset(StandardCharsets.UTF_8);
        messageSource.setUseCodeAsDefaultMessage(true);
        messageSource.setParentMessageSource(jdbcRoutableMessageSource);
        MessageUtils.setMessageSource(messageSource);
        return messageSource;
    }
}
