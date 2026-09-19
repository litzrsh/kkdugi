package kkdugi.core.i18n.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class KkdugiMessageSourceTest {

    @Autowired
    private KkdugiMessageSource kkdugiMessageSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_i18n_msg WHERE msg_cd IN (?, ?) AND lang_cd = ?",
                "test.msg.temp", "test.msg.args", "ko_KR");
        kkdugiMessageSource.refresh("test.msg.temp", "ko_KR");
        kkdugiMessageSource.refresh("test.msg.args", "ko_KR");
    }

    @Test
    void getMessage_returnsDbValueWhenPresent() {
        insertMessage("test.msg.temp", "DB 메시지");
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
        insertMessage("test.msg.args", "{0}님 환영합니다");
        kkdugiMessageSource.refresh("test.msg.args", "ko_KR");

        String result = kkdugiMessageSource.getMessage(
                "test.msg.args", new Object[]{"철수"}, Locale.KOREA);

        assertThat(result).isEqualTo("철수님 환영합니다");
    }

    private void insertMessage(String code, String text) {
        jdbcTemplate.update("INSERT INTO kkdugi_i18n_msg (msg_cd, lang_cd, msg_val, reg_id) VALUES (?, ?, ?, ?)",
                code, "ko_KR", text, "SYSTEM");
    }
}
