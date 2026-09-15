package kkdugi.app.admin.i18n;

import kkdugi.KkdugiAdminApplication;
import kkdugi.core.i18n.I18nMessageMapper;
import kkdugi.core.i18n.KkdugiMessageSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Locale;

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
        mapper.delete("test.admin.batch", "ko_KR");
        mapper.delete("test.admin.batch", "en_US");
        messageSource.refresh("test.admin.batch", "ko_KR");
        messageSource.refresh("test.admin.batch", "en_US");
    }

    @Test
    void saveAll_insertsUpdatesInSeparateBatches_andRefreshesCache() {
        service.saveAll(List.of(
                new MessageRowCommand(CrudType.INSERT, "test.admin.batch", "ko_KR", "첫 값")
        ));

        MessageSaveResult result = service.saveAll(List.of(
                new MessageRowCommand(CrudType.UPDATE, "test.admin.batch", "ko_KR", "수정된 값"),
                new MessageRowCommand(CrudType.INSERT, "test.admin.batch", "en_US", "english value")
        ));

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(messageSource.getMessage("test.admin.batch", null, Locale.KOREA))
                .isEqualTo("수정된 값");
        assertThat(messageSource.getMessage("test.admin.batch", null, Locale.US))
                .isEqualTo("english value");
    }

    @Test
    void saveAll_rollsBackWholeBatch_whenOneRowFails() {
        assertThatThrownBy(() -> service.saveAll(List.of(
                new MessageRowCommand(CrudType.INSERT, "test.admin.batch", "ko_KR", "정상 행"),
                new MessageRowCommand(CrudType.UPDATE, "test.admin.batch", "en_US", "존재하지 않는 대상")
        ))).isInstanceOf(MessageConflictException.class);

        assertThat(mapper.findByCodeAndLang("test.admin.batch", "ko_KR")).isNull();
        assertThat(messageSource.getMessage("test.admin.batch", null, Locale.KOREA))
                .isEqualTo("test.admin.batch");
    }

    @Test
    void saveAll_rejectsInvalidMessageCode_withoutTouchingDb() {
        assertThatThrownBy(() -> service.saveAll(List.of(
                new MessageRowCommand(CrudType.INSERT, "Invalid.Code", "ko_KR", "값")
        ))).isInstanceOf(MessageValidationException.class);

        assertThat(mapper.findByCodeAndLang("Invalid.Code", "ko_KR")).isNull();
    }

    @Test
    void saveAll_rejectsInvalidMessageCode_onDeleteRow_withoutTouchingDb() {
        assertThatThrownBy(() -> service.saveAll(List.of(
                new MessageRowCommand(CrudType.DELETE, "Invalid.Code", "ko_KR", null)
        ))).isInstanceOf(MessageValidationException.class);

        assertThat(mapper.findByCodeAndLang("Invalid.Code", "ko_KR")).isNull();
    }
}
