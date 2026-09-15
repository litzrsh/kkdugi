package kkdugi.core.i18n;

import kkdugi.KkdugiAdminApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class KkdugiMessageSourceTest {

    @Autowired
    private KkdugiMessageSource kkdugiMessageSource;

    @Autowired
    private I18nMessageMapper mapper;

    @AfterEach
    void cleanUp() {
        mapper.delete("test.msg.temp", "ko_KR");
        mapper.delete("test.msg.args", "ko_KR");
        kkdugiMessageSource.refresh("test.msg.temp", "ko_KR");
        kkdugiMessageSource.refresh("test.msg.args", "ko_KR");
    }

    @Test
    void getMessage_returnsDbValueWhenPresent() {
        mapper.insert(new I18nMessage(
                "test.msg.temp", "ko_KR", "DB 메시지", LocalDateTime.now(), "SYSTEM", null, null));
        kkdugiMessageSource.refresh("test.msg.temp", "ko_KR");

        String result = kkdugiMessageSource.getMessage("test.msg.temp", null, Locale.KOREA);

        assertThat(result).isEqualTo("DB 메시지");
    }

    @Test
    void getMessage_fallsBackToPropertiesWhenNotInDb() {
        String result = kkdugiMessageSource.getMessage("system.err.default", null, Locale.KOREA);

        assertThat(result).isEqualTo("시스템 오류가 발생하였습니다");
    }

    @Test
    void getMessage_returnsCodeItselfWhenNotFoundAnywhere() {
        String result = kkdugiMessageSource.getMessage("unknown.code.missing", null, Locale.KOREA);

        assertThat(result).isEqualTo("unknown.code.missing");
    }

    @Test
    void getMessage_formatsArgumentsForDbValue() {
        mapper.insert(new I18nMessage(
                "test.msg.args", "ko_KR", "{0}님 환영합니다", LocalDateTime.now(), "SYSTEM", null, null));
        kkdugiMessageSource.refresh("test.msg.args", "ko_KR");

        String result = kkdugiMessageSource.getMessage(
                "test.msg.args", new Object[]{"철수"}, Locale.KOREA);

        assertThat(result).isEqualTo("철수님 환영합니다");
    }
}
