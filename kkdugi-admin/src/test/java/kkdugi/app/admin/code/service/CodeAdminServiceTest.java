package kkdugi.app.admin.code.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.code.exceptions.CodeConflictException;
import kkdugi.app.admin.code.exceptions.CodeValidationException;
import kkdugi.app.admin.code.models.CodeContent;
import kkdugi.app.admin.code.models.CodeLocale;
import kkdugi.app.admin.code.models.CodePersistRequest;
import kkdugi.app.admin.code.models.CodeSearchParams;
import kkdugi.core.code.mapper.CodeBaseMapper;
import kkdugi.core.code.mapper.CodeLangMapper;
import kkdugi.core.code.models.CodeBase;
import kkdugi.core.models.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class CodeAdminServiceTest {

    @Autowired
    private CodeAdminService service;

    @Autowired
    private CodeBaseMapper codeBaseMapper;

    @Autowired
    private CodeLangMapper codeLangMapper;

    private String createdRootId;

    @AfterEach
    void cleanUp() {
        if (createdRootId != null) {
            String path = codeBaseMapper.findById(createdRootId).map(CodeBase::getPath).orElse("/__missing__");
            List<CodeBase> targets = codeBaseMapper.findSelfAndDescendants(path);
            List<String> ids = targets.stream().map(CodeBase::getId).toList();
            if (!ids.isEmpty()) {
                codeLangMapper.deleteByCodeIds(ids);
                codeBaseMapper.deleteByIds(ids);
            }
            createdRootId = null;
        }
    }

    @Test
    void persist_insertsRootCode_thenSearchReturnsIt() {
        service.persist(new CodePersistRequest(
                List.of(new CodeContent(null, null, "TEST_ROOT",
                        Map.of("ko_KR", new CodeLocale("테스트 루트", "설명")),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));

        Page<CodeContent> page = service.search(new CodeSearchParams(null, null, "TEST_ROOT", null, null, 1, 200));
        assertThat(page.getContents()).hasSize(1);
        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getTotalItems()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(1);
        CodeContent found = page.getContents().get(0);
        assertThat(found.getCode()).isEqualTo("TEST_ROOT");
        assertThat(found.getLevel()).isEqualTo(0);
        assertThat(found.getPath()).isEqualTo("/TEST_ROOT");
        assertThat(found.getLocale().get("ko_KR").getName()).isEqualTo("테스트 루트");

        createdRootId = found.getId();
    }

    @Test
    void search_withUnresolvedPageParams_reportsResolvedDefaults() {
        service.persist(new CodePersistRequest(
                List.of(new CodeContent(null, null, "TEST_PAGE_DEFAULT",
                        Map.of("ko_KR", new CodeLocale("페이지 기본값", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));

        Page<CodeContent> page = service.search(
                new CodeSearchParams(null, null, "TEST_PAGE_DEFAULT", null, null, 0, 0));

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getTotalItems()).isEqualTo(1);
        assertThat(page.getContents()).hasSize(1);

        createdRootId = page.getContents().get(0).getId();
    }

    @Test
    void persist_insertsChild_underParent() {
        service.persist(new CodePersistRequest(
                List.of(new CodeContent(null, null, "TEST_PARENT",
                        Map.of("ko_KR", new CodeLocale("부모", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));
        String parentId = service.search(new CodeSearchParams(null, null, "TEST_PARENT", null, null, 1, 200))
                .getContents().get(0).getId();
        createdRootId = parentId;

        service.persist(new CodePersistRequest(
                List.of(new CodeContent(null, parentId, "CHILD",
                        Map.of("ko_KR", new CodeLocale("자식", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));

        Page<CodeContent> children = service.search(
                new CodeSearchParams(parentId, null, null, null, null, 1, 200));
        assertThat(children.getContents()).hasSize(1);
        assertThat(children.getContents().get(0).getPath()).isEqualTo("/TEST_PARENT/CHILD");
        assertThat(children.getContents().get(0).getLevel()).isEqualTo(1);
    }

    @Test
    void persist_deletingParent_cascadesToChildren() {
        service.persist(new CodePersistRequest(
                List.of(new CodeContent(null, null, "TEST_CASCADE",
                        Map.of("ko_KR", new CodeLocale("캐스케이드", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));
        String parentId = service.search(new CodeSearchParams(null, null, "TEST_CASCADE", null, null, 1, 200))
                .getContents().get(0).getId();

        service.persist(new CodePersistRequest(
                List.of(new CodeContent(null, parentId, "CHILD",
                        Map.of("ko_KR", new CodeLocale("자식", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null));

        service.persist(new CodePersistRequest(null, null,
                List.of(new CodeContent(parentId, null, null, null, null,
                        null, null, null, null, null, null, null, null))));

        assertThat(codeBaseMapper.findById(parentId)).isEmpty();
    }

    @Test
    void persist_rejectsMissingLocale() {
        assertThatThrownBy(() -> service.persist(new CodePersistRequest(
                List.of(new CodeContent(null, null, "TEST_NO_LOCALE", null, "Y",
                        null, null, null, null, null, null, null, null)),
                null, null)))
                .isInstanceOf(CodeValidationException.class);
    }

    @Test
    void persist_rejectsInvalidCodeFormat() {
        assertThatThrownBy(() -> service.persist(new CodePersistRequest(
                List.of(new CodeContent(null, null, "_invalid_",
                        Map.of("ko_KR", new CodeLocale("이름", null)),
                        "Y", null, null, null, null, null, null, null, null)),
                null, null)))
                .isInstanceOf(CodeValidationException.class);
    }

    @Test
    void persist_conflictsOnUpdateOfMissingId() {
        assertThatThrownBy(() -> service.persist(new CodePersistRequest(null,
                List.of(new CodeContent("C_MISSING", null, null,
                        Map.of("ko_KR", new CodeLocale("값", null)),
                        null, null, null, null, null, null, null, null, null)),
                null)))
                .isInstanceOf(CodeConflictException.class);
    }
}
