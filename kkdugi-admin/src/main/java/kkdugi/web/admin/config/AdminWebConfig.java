package kkdugi.web.admin.config;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * {@code ?lang=} 파라미터로 페이지를 다시 요청하면 locale이 바뀌는 계약
 * (kkdugi-design README)을 위한 최소 구성. 쿠키 기반이라 이 프로젝트의
 * SessionCreationPolicy.STATELESS 방침(Spring Security의 HTTP 세션 미사용)과
 * 별개로, HTTP 세션 없이도 locale 선호도를 유지할 수 있다.
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private static final String LANG_PARAM = "lang";

    @Bean
    LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver();
        resolver.setDefaultLocaleFunction(request -> new Locale("ko", "KR"));
        return resolver;
    }

    @Bean
    LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LANG_PARAM);
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
