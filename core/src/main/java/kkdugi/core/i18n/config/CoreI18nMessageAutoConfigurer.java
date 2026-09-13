package kkdugi.core.i18n.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kkdugi.core.i18n.JdbcRoutableMessageSource;
import kkdugi.core.i18n.mapper.CoreI18nMessageMapper;
import kkdugi.core.i18n.service.CoreI18nMessageService;

@Configuration
public class CoreI18nMessageAutoConfigurer {

    @Bean
    CoreI18nMessageService coreI18nMessageService(CoreI18nMessageMapper coreI18nMessageMapper) {
        return new CoreI18nMessageService(coreI18nMessageMapper);
    }

    @Bean
    JdbcRoutableMessageSource jdbcRoutableMessageSource(CoreI18nMessageService coreI18nMessageService) {
        return new JdbcRoutableMessageSource(coreI18nMessageService);
    }
}
