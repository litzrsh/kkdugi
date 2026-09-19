package kkdugi.app.admin.code.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.code.exceptions.AdminCodeConflictException;
import kkdugi.app.admin.code.exceptions.AdminCodeValidationException;
import kkdugi.app.admin.code.models.AdminCode;
import kkdugi.app.admin.code.models.AdminCodeLocale;
import kkdugi.app.admin.code.models.AdminCodePersistRequest;
import kkdugi.app.admin.code.models.AdminCodeParams;
import kkdugi.app.admin.code.mapper.AdminCodeMapper;
import kkdugi.app.admin.code.models.CodeBase;
import kkdugi.core.models.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminCodeServiceTest {

    @Autowired
    private AdminCodeService service;

    @Autowired
    private AdminCodeMapper adminCodeMapper;

    private String createdRootId;

    @AfterEach
    void cleanUp() {
        if (createdRootId != null) {
            String path = adminCodeMapper.findById(createdRootId).map(CodeBase::getPath).orElse("/__missing__");
            List<CodeBase> targets = adminCodeMapper.findSelfAndDescendants(path);
            List<String> ids = targets.stream().map(CodeBase::getId).toList();
            if (!ids.isEmpty()) {
                adminCodeMapper.deleteLangByCodeIds(ids);
                adminCodeMapper.deleteByIds(ids);
            }
            createdRootId = null;
        }
    }

    @Test
    void persist_insertsRootCode_thenSearchReturnsIt() {
        service.persist(new AdminCodePersistRequest(
                List.of(new AdminCode(null, null, "TEST_ROOT",
                        Map.of("ko_KR", new AdminCodeLocale("테스트 루트", "설명")),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));

        Page<AdminCode> page = service.search(new AdminCodeParams(null, null, "TEST_ROOT", null, null, 1, 200));
        assertThat(page.getContents()).hasSize(1);
        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getTotalItems()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(1);
        AdminCode found = page.getContents().get(0);
        assertThat(found.getCode()).isEqualTo("TEST_ROOT");
        assertThat(found.getLevel()).isEqualTo(0);
        assertThat(found.getPath()).isEqualTo("/TEST_ROOT");
        assertThat(found.getLocale().get("ko_KR").getName()).isEqualTo("테스트 루트");

        createdRootId = found.getId();
    }

    @Test
    void search_withUnresolvedPageParams_reportsResolvedDefaults() {
        service.persist(new AdminCodePersistRequest(
                List.of(new AdminCode(null, null, "TEST_PAGE_DEFAULT",
                        Map.of("ko_KR", new AdminCodeLocale("페이지 기본값", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));

        Page<AdminCode> page = service.search(
                new AdminCodeParams(null, null, "TEST_PAGE_DEFAULT", null, null, 0, 0));

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getTotalItems()).isEqualTo(1);
        assertThat(page.getContents()).hasSize(1);

        createdRootId = page.getContents().get(0).getId();
    }

    @Test
    void persist_insertsChild_underParent() {
        service.persist(new AdminCodePersistRequest(
                List.of(new AdminCode(null, null, "TEST_PARENT",
                        Map.of("ko_KR", new AdminCodeLocale("부모", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));
        String parentId = service.search(new AdminCodeParams(null, null, "TEST_PARENT", null, null, 1, 200))
                .getContents().get(0).getId();
        createdRootId = parentId;

        service.persist(new AdminCodePersistRequest(
                List.of(new AdminCode(null, parentId, "CHILD",
                        Map.of("ko_KR", new AdminCodeLocale("자식", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));

        Page<AdminCode> children = service.search(
                new AdminCodeParams(parentId, null, null, null, null, 1, 200));
        assertThat(children.getContents()).hasSize(1);
        assertThat(children.getContents().get(0).getPath()).isEqualTo("/TEST_PARENT/CHILD");
        assertThat(children.getContents().get(0).getLevel()).isEqualTo(1);
    }

    @Test
    void persist_deletingParent_cascadesToChildren() {
        service.persist(new AdminCodePersistRequest(
                List.of(new AdminCode(null, null, "TEST_CASCADE",
                        Map.of("ko_KR", new AdminCodeLocale("캐스케이드", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));
        String parentId = service.search(new AdminCodeParams(null, null, "TEST_CASCADE", null, null, 1, 200))
                .getContents().get(0).getId();

        service.persist(new AdminCodePersistRequest(
                List.of(new AdminCode(null, parentId, "CHILD",
                        Map.of("ko_KR", new AdminCodeLocale("자식", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));

        service.persist(new AdminCodePersistRequest(null, null,
                List.of(new AdminCode(parentId, null, null, null, null,
                        null, null, null, null, null, null, null, null))));

        assertThat(adminCodeMapper.findById(parentId)).isEmpty();
    }

    @Test
    void persist_rejectsMissingLocale() {
        assertThatThrownBy(() -> service.persist(new AdminCodePersistRequest(
                List.of(new AdminCode(null, null, "TEST_NO_LOCALE", null, "Y",
                        null, null, null, null, null, null, null, null)),
                null, null)))
                .isInstanceOf(AdminCodeValidationException.class);
    }

    @Test
    void persist_rejectsInvalidCodeFormat() {
        assertThatThrownBy(() -> service.persist(new AdminCodePersistRequest(
                List.of(new AdminCode(null, null, "_invalid_",
                        Map.of("ko_KR", new AdminCodeLocale("이름", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null)))
                .isInstanceOf(AdminCodeValidationException.class);
    }

    @Test
    void persist_conflictsOnUpdateOfMissingId() {
        assertThatThrownBy(() -> service.persist(new AdminCodePersistRequest(null,
                List.of(new AdminCode("C_MISSING", null, null,
                        Map.of("ko_KR", new AdminCodeLocale("값", null)),
                        null, null, null, null, null, null, null, null, null)),
                null)))
                .isInstanceOf(AdminCodeConflictException.class);
    }
}
