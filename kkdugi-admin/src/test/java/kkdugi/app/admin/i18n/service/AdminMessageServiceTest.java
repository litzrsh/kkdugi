package kkdugi.app.admin.i18n.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.i18n.exceptions.AdminMessageConflictException;
import kkdugi.app.admin.i18n.exceptions.AdminMessageValidationException;
import kkdugi.app.admin.i18n.mapper.AdminMessageMapper;
import kkdugi.app.admin.i18n.models.AdminMessage;
import kkdugi.app.admin.i18n.models.AdminMessageParams;
import kkdugi.app.admin.i18n.models.AdminMessagePersistRequest;
import kkdugi.core.i18n.service.KkdugiMessageSource;
import kkdugi.core.models.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminMessageServiceTest {

    @Autowired
    private AdminMessageService service;

    @Autowired
    private AdminMessageMapper mapper;

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
        service.persist(new AdminMessagePersistRequest(
                List.of(new AdminMessage("test.admin.batch", Map.of("ko_KR", "첫 값"))),
                null,
                null));

        service.persist(new AdminMessagePersistRequest(
                null,
                List.of(new AdminMessage("test.admin.batch", Map.of(
                        "ko_KR", "수정된 값",
                        "en_US", "english value"))),
                null));

        assertThat(messageSource.getMessage("test.admin.batch", null, Locale.KOREA))
                .isEqualTo("수정된 값");
        assertThat(messageSource.getMessage("test.admin.batch", null, Locale.US))
                .isEqualTo("english value");
    }

    @Test
    void search_returnsResolvedPagingAndTotalItemsFromQuery() {
        service.persist(new AdminMessagePersistRequest(
                List.of(new AdminMessage("test.admin.batch", Map.of("ko_KR", "값"))),
                null, null));

        Page<AdminMessage> page = service.search(new AdminMessageParams("test.admin.batch", null, 0, 0));

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getTotalItems()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(page.getContents()).hasSize(1);
        assertThat(page.getContents().get(0).getCode()).isEqualTo("test.admin.batch");
    }

    @Test
    void persist_deletesAllLanguagesForCode() {
        service.persist(new AdminMessagePersistRequest(
                List.of(new AdminMessage("test.admin.batch", Map.of(
                        "ko_KR", "값", "en_US", "value"))),
                null, null));

        service.persist(new AdminMessagePersistRequest(null, null,
                List.of(new AdminMessage("test.admin.batch", Map.of()))));

        assertThat(mapper.findByCode("test.admin.batch")).isEmpty();
        assertThat(messageSource.getMessage("test.admin.batch", null, Locale.KOREA))
                .isEqualTo("test.admin.batch");
    }

    @Test
    void persist_rejectsInvalidMessageCode_withoutTouchingDb() {
        assertThatThrownBy(() -> service.persist(new AdminMessagePersistRequest(
                List.of(new AdminMessage("Invalid.Code", Map.of("ko_KR", "값"))),
                null, null)))
                .isInstanceOf(AdminMessageValidationException.class);

        assertThat(mapper.findByCode("Invalid.Code")).isEmpty();
    }

    @Test
    void persist_conflictsOnDuplicateInsert() {
        service.persist(new AdminMessagePersistRequest(
                List.of(new AdminMessage("test.admin.batch", Map.of("ko_KR", "값"))),
                null, null));

        assertThatThrownBy(() -> service.persist(new AdminMessagePersistRequest(
                List.of(new AdminMessage("test.admin.batch", Map.of("ko_KR", "다시"))),
                null, null)))
                .isInstanceOf(AdminMessageConflictException.class);
    }

    @Test
    void persist_conflictsOnUpdateOfMissingCode() {
        assertThatThrownBy(() -> service.persist(new AdminMessagePersistRequest(
                null,
                List.of(new AdminMessage("test.admin.missing", Map.of("ko_KR", "값"))),
                null)))
                .isInstanceOf(AdminMessageConflictException.class);
    }
}
