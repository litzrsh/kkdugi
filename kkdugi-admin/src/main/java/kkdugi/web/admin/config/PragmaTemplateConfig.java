package kkdugi.web.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * {@code PragmaController}가 {@code templates/pragma/*.vue} 파일을 렌더링할
 * 때 쓰는 전용 {@link TemplateEngine}. Spring Boot가 MVC 뷰 리졸빙용으로
 * 자동 구성하는 {@code SpringTemplateEngine}(접두사 {@code templates/},
 * 접미사 {@code .html})과는 별개의 독립된 인스턴스다 — 그 엔진에 리졸버를
 * 추가하는 대신 분리한 이유는, `layout/index` 같은 기존 뷰 리졸빙 경로에
 * `.vue` 접미사 리졸버가 조금이라도 관여할 가능성을 원천적으로 없애기
 * 위함이다. 이 빈을 주입받을 때는 반드시 이름({@code pragmaTemplateEngine})으로
 * 지정한다 — 타입만으로는 Boot가 자동 구성한 엔진과 모호해질 수 있다.
 *
 * <p>{@link SpringTemplateEngine}을 쓴다 — 순수 {@code org.thymeleaf.TemplateEngine}의
 * 기본 Standard Dialect는 {@code ${...}} 표현식을 OGNL로 평가하는데, 이
 * 프로젝트엔 {@code spring-boot-starter-thymeleaf}(Spring 통합, SpringEL 사용)만
 * 있고 OGNL 라이브러리가 없어 순수 엔진으로 만들면 처음 렌더링 시점에
 * {@code NoClassDefFoundError: ognl/PropertyAccessor}가 난다.
 * {@code SpringTemplateEngine}은 SpringStandardDialect(SpringEL)를 써서 이
 * 문제가 없다.</p>
 */
@Configuration
public class PragmaTemplateConfig {

    @Bean
    public TemplateEngine pragmaTemplateEngine(MessageSource messageSource) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/pragma/");
        resolver.setSuffix(".vue");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messageSource);
        return engine;
    }
}
