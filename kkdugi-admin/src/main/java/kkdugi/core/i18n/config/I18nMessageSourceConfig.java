package kkdugi.core.i18n.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import kkdugi.core.i18n.mapper.I18nMessageMapper;
import kkdugi.core.i18n.service.KkdugiMessageSource;
import kkdugi.core.util.MessageUtils;

@Configuration
public class I18nMessageSourceConfig {

    @Bean(name = "messageSource")
    public KkdugiMessageSource kkdugiMessageSource(I18nMessageMapper mapper) throws Exception {
        KkdugiMessageSource messageSource = new KkdugiMessageSource(mapper);
        messageSource.setParentMessageSource(propertiesMessageSource());
        messageSource.setUseCodeAsDefaultMessage(true);
        MessageUtils.setMessageSource(messageSource);
        return messageSource;
    }

    @Bean
    public CommandLineRunner loadI18nMessageCache(KkdugiMessageSource kkdugiMessageSource) {
        // Flyway 마이그레이션은 ApplicationContext 초기화(context.refresh())
        // 중에 실행되고, CommandLineRunner는 그 refresh()가 완전히 끝난
        // 뒤에만 실행된다. MyBatis는 JPA와 달리 Flyway 이후 순서를 자동으로
        // 보장해주지 않으므로, @Bean 팩토리 메서드 안에서 즉시 loadAll()을
        // 호출하면 테이블이 아직 없을 때 조회할 위험이 있다. CommandLineRunner로
        // 옮겨 이 위험을 없앤다.
        return args -> kkdugiMessageSource.loadAll();
    }

    private ReloadableResourceBundleMessageSource propertiesMessageSource() {
        ReloadableResourceBundleMessageSource messageSource =
                new ReloadableResourceBundleMessageSource();
        // admin-ui: kkdugi-design이 제공하는 화면 UI 문구 번들(admin.ui.*) —
        // DB 메시지 관리 대상이 아니라 properties에만 있다.
        messageSource.setBasenames("classpath:messages/messages", "classpath:messages/admin-ui");
        messageSource.setUseCodeAsDefaultMessage(true);
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }
}
