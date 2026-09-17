package kkdugi.app.admin.i18n.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.i18n.exceptions.MessageConflictException;
import kkdugi.app.admin.i18n.exceptions.MessageValidationException;
import kkdugi.app.admin.i18n.models.MessageContent;
import kkdugi.app.admin.i18n.models.MessagePersistRequest;
import kkdugi.core.i18n.mapper.I18nMessageMapper;
import kkdugi.core.i18n.service.KkdugiMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class MessageAdminServiceTest {

    @Autowired
    private MessageAdminService service;

    @Autowired
    private I18nMessageMapper mapper;

    @Autowired
    private KkdugiMessageSource messageSource;

    @AfterEach
    void cleanUp() {
        mapper.deleteByCode("test.admin.batch");
        messageSource.refresh("test.admin.batch", "ko_KR");
        messageSource.refresh("test.admin.batch", "en_US");
    }

    @Test
    void persist_insertsThenUpdates_andRefreshesCache() {
        service.persist(new MessagePersistRequest(
                List.of(new MessageContent("test.admin.batch", Map.of("ko_KR", "첫 값"))),
                null,
                null));

        service.persist(new MessagePersistRequest(
                null,
                List.of(new MessageContent("test.admin.batch", Map.of(
                        "ko_KR", "수정된 값",
                        "en_US", "english value"))),
                null));

        assertThat(messageSource.getMessage("test.admin.batch", null, Locale.KOREA))
                .isEqualTo("수정된 값");
        assertThat(messageSource.getMessage("test.admin.batch", null, Locale.US))
                .isEqualTo("english value");
    }

    @Test
    void persist_deletesAllLanguagesForCode() {
        service.persist(new MessagePersistRequest(
                List.of(new MessageContent("test.admin.batch", Map.of(
                        "ko_KR", "값", "en_US", "value"))),
                null, null));

        service.persist(new MessagePersistRequest(null, null,
                List.of(new MessageContent("test.admin.batch", Map.of()))));

        assertThat(mapper.findByCode("test.admin.batch")).isEmpty();
        assertThat(messageSource.getMessage("test.admin.batch", null, Locale.KOREA))
                .isEqualTo("test.admin.batch");
    }

    @Test
    void persist_rejectsInvalidMessageCode_withoutTouchingDb() {
        assertThatThrownBy(() -> service.persist(new MessagePersistRequest(
                List.of(new MessageContent("Invalid.Code", Map.of("ko_KR", "값"))),
                null, null)))
                .isInstanceOf(MessageValidationException.class);

        assertThat(mapper.findByCode("Invalid.Code")).isEmpty();
    }

    @Test
    void persist_conflictsOnDuplicateInsert() {
        service.persist(new MessagePersistRequest(
                List.of(new MessageContent("test.admin.batch", Map.of("ko_KR", "값"))),
                null, null));

        assertThatThrownBy(() -> service.persist(new MessagePersistRequest(
                List.of(new MessageContent("test.admin.batch", Map.of("ko_KR", "다시"))),
                null, null)))
                .isInstanceOf(MessageConflictException.class);
    }

    @Test
    void persist_conflictsOnUpdateOfMissingCode() {
        assertThatThrownBy(() -> service.persist(new MessagePersistRequest(
                null,
                List.of(new MessageContent("test.admin.missing", Map.of("ko_KR", "값"))),
                null)))
                .isInstanceOf(MessageConflictException.class);
    }
}
