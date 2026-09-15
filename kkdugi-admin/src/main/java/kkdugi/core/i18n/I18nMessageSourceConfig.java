package kkdugi.core.i18n;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

@Configuration
public class I18nMessageSourceConfig {

    @Bean(name = "messageSource")
    public KkdugiMessageSource kkdugiMessageSource(I18nMessageMapper mapper) {
        KkdugiMessageSource messageSource = new KkdugiMessageSource(mapper);
        messageSource.setParentMessageSource(propertiesMessageSource());
        messageSource.setUseCodeAsDefaultMessage(true);
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
        messageSource.setBasename("classpath:messages");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }
}
