package kkdugi.core.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;

import kkdugi.core.i18n.models.I18nMessage;
import kkdugi.core.i18n.service.CoreI18nMessageService;

class JdbcRoutableMessageSourceTest {

    private JdbcRoutableMessageSource messageSource(I18nMessage... stored) {
        CoreI18nMessageService coreI18nMessageService = mock(CoreI18nMessageService.class);
        when(coreI18nMessageService.findAll()).thenReturn(List.of(stored));
        return new JdbcRoutableMessageSource(coreI18nMessageService);
    }

    @Test
    void convert_keepsNonBlankStoredMessage() {
        Map<String, Map<String, String>> converted = messageSource()
                .convert(List.of(new I18nMessage("ko", "greeting", "안녕하세요")));

        assertThat(converted.get("ko").get("greeting")).isEqualTo("안녕하세요");
    }

    @Test
    void convert_replacesEmptyMessageWithCode() {
        Map<String, Map<String, String>> converted = messageSource()
                .convert(List.of(new I18nMessage("ko", "empty.code", "")));

        assertThat(converted.get("ko").get("empty.code")).isEqualTo("empty.code");
    }

    @Test
    void convert_replacesWhitespaceOnlyMessageWithCode() {
        Map<String, Map<String, String>> converted = messageSource()
                .convert(List.of(new I18nMessage("ko", "blank.code", "   ")));

        assertThat(converted.get("ko").get("blank.code")).isEqualTo("blank.code");
    }

    @Test
    void convert_replacesNullMessageWithCode() {
        Map<String, Map<String, String>> converted = messageSource()
                .convert(List.of(new I18nMessage("ko", "null.code", null)));

        assertThat(converted.get("ko").get("null.code")).isEqualTo("null.code");
    }

    @Test
    void convert_groupsMessagesByLangIndependently() {
        Map<String, Map<String, String>> converted = messageSource().convert(List.of(
                new I18nMessage("ko", "greeting", "안녕하세요"),
                new I18nMessage("en", "greeting", "Hello")));

        assertThat(converted.get("ko").get("greeting")).isEqualTo("안녕하세요");
        assertThat(converted.get("en").get("greeting")).isEqualTo("Hello");
    }

    @Test
    void convert_sameCodeBlankInOneLangButPresentInAnother_onlyFallsBackForBlankLang() {
        Map<String, Map<String, String>> converted = messageSource().convert(List.of(
                new I18nMessage("ko", "greeting", ""),
                new I18nMessage("en", "greeting", "Hello")));

        assertThat(converted.get("ko").get("greeting")).isEqualTo("greeting");
        assertThat(converted.get("en").get("greeting")).isEqualTo("Hello");
    }

    @Test
    void getMessage_returnsStoredMessage_whenNotBlank() {
        JdbcRoutableMessageSource source = messageSource(
                new I18nMessage("ko", "greeting", "안녕하세요"));

        assertThat(source.getMessage("greeting", null, Locale.KOREAN)).isEqualTo("안녕하세요");
    }

    @Test
    void getMessage_returnsCode_whenStoredMessageIsBlank() {
        JdbcRoutableMessageSource source = messageSource(
                new I18nMessage("ko", "empty.code", "  "));

        assertThat(source.getMessage("empty.code", null, Locale.KOREAN)).isEqualTo("empty.code");
    }

    @Test
    void getMessage_throwsNoSuchMessageException_whenCodeNotStoredAtAll() {
        JdbcRoutableMessageSource source = messageSource(
                new I18nMessage("ko", "greeting", "안녕하세요"));

        assertThatThrownBy(() -> source.getMessage("unknown.code", null, Locale.KOREAN))
                .isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void getMessage_matchesLocaleByLanguage_ignoringCountryVariant() {
        JdbcRoutableMessageSource source = messageSource(
                new I18nMessage("ko", "greeting", "안녕하세요"));

        assertThat(source.getMessage("greeting", null, Locale.forLanguageTag("ko-KR")))
                .isEqualTo("안녕하세요");
    }

    @Test
    void reload_refreshesCacheFromLatestStoredMessages() {
        CoreI18nMessageService coreI18nMessageService = mock(CoreI18nMessageService.class);
        when(coreI18nMessageService.findAll())
                .thenReturn(List.of(new I18nMessage("ko", "greeting", "안녕하세요")))
                .thenReturn(List.of(new I18nMessage("ko", "greeting", "새 메시지")));

        JdbcRoutableMessageSource source = new JdbcRoutableMessageSource(coreI18nMessageService);
        assertThat(source.getMessage("greeting", null, Locale.KOREAN)).isEqualTo("안녕하세요");

        source.reload();

        assertThat(source.getMessage("greeting", null, Locale.KOREAN)).isEqualTo("새 메시지");
    }
}
