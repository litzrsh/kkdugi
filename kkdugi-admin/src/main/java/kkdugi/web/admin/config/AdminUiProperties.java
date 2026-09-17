package kkdugi.web.admin.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import kkdugi.web.admin.models.LanguageOption;

/**
 * 관리자 셸이 표시할 등록 언어 목록. i18n 메시지 테이블(kkdugi_i18n_message)의
 * DISTINCT locale이 아니라 별도 설정으로 관리한다 — 그 테이블은 "번역이 얼마나
 * 채워져 있는지"이지 "시스템이 지원하는 언어가 무엇인지"가 아니다.
 */
@Configuration
@ConfigurationProperties(prefix = "kkdugi.i18n")
public class AdminUiProperties {

    private List<LanguageOption> languages = List.of();

    public List<LanguageOption> getLanguages() {
        return languages;
    }

    public void setLanguages(List<LanguageOption> languages) {
        this.languages = languages;
    }
}
