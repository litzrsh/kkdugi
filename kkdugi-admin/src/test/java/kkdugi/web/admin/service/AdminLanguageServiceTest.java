package kkdugi.web.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import kkdugi.app.code.models.Code;
import kkdugi.app.code.service.CodeService;

class AdminLanguageServiceTest {
    private Code row(String extra, String name) {
        Code code = new Code(); code.setCode("COMMON_CODE_VALUE"); code.setExtra1(extra); code.setName(name); return code;
    }

    @Test
    void usesAvailableChildrenWithExtra1AsLocaleAndPreservesOrder() {
        CodeService codes = mock(CodeService.class);
        when(codes.findChildren("/SYS/LANG", "ko_KR")).thenReturn(List.of(row(" ko_KR ","한국어"),row("ja_JP","日本語")));
        var result = new AdminLanguageService(codes).languages(Locale.KOREA);
        assertThat(result).extracting(option -> option.getCode()).containsExactly("ko_KR","ja_JP");
        assertThat(result).extracting(option -> option.getLabel()).containsExactly("한국어","日本語");
        verify(codes).findChildren("/SYS/LANG", "ko_KR");
    }

    @Test
    void omitsMissingInvalidAndDuplicateLocalesWithoutFallingBackToFixedLanguages() {
        var result=AdminLanguageService.toOptions(List.of(row(null,"Missing"),row("","Empty"),row("__proto__","Invalid"),row("en_US","English"),row("en_US","Duplicate"),row("zh_Hant_TW",null)));
        assertThat(result).extracting(option -> option.getCode()).containsExactly("en_US","zh_Hant_TW");
        assertThat(result.get(0).getLabel()).isEqualTo("English");
        assertThat(result.get(1).getLabel()).isEqualTo("zh_Hant_TW");
        assertThat(AdminLanguageService.toOptions(List.of())).isEmpty();
    }
}
