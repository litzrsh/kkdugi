package kkdugi.app.admin.menu.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.menu.exceptions.MenuConflictException;
import kkdugi.app.admin.menu.exceptions.MenuValidationException;
import kkdugi.app.admin.menu.models.MenuContent;
import kkdugi.app.admin.menu.models.MenuLocale;
import kkdugi.app.admin.menu.models.MenuPersistRequest;
import kkdugi.core.menu.mapper.MenuBaseMapper;
import kkdugi.core.menu.mapper.MenuLangMapper;
import kkdugi.core.menu.models.MenuBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class MenuAdminServiceTest {

    @Autowired
    private MenuAdminService service;

    @Autowired
    private MenuBaseMapper menuBaseMapper;

    @Autowired
    private MenuLangMapper menuLangMapper;

    private String createdRootId;

    @AfterEach
    void cleanUp() {
        if (createdRootId != null) {
            String path = menuBaseMapper.findById(createdRootId).map(MenuBase::getPath).orElse("/__missing__");
            List<MenuBase> targets = menuBaseMapper.findSelfAndDescendants(path);
            List<String> ids = targets.stream().map(MenuBase::getId).toList();
            if (!ids.isEmpty()) {
                menuLangMapper.deleteByMenuIds(ids);
                menuBaseMapper.deleteByIds(ids);
            }
            createdRootId = null;
        }
    }

    private static MenuContent newMenu(String id, String parentId, String label) {
        return new MenuContent(id, parentId, Map.of("ko_KR", new MenuLocale(label, null)),
                null, "adcode", "Y", "Y", null, null, 1);
    }

    private MenuContent findById(List<MenuContent> tree, String id) {
        for (MenuContent node : tree) {
            if (node.getId().equals(id)) {
                return node;
            }
            if (node.getChildren() != null) {
                MenuContent found = findById(node.getChildren(), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void persist_insertsRootMenu_thenSearchReturnsItInTree() {
        service.persist(new MenuPersistRequest(
                List.of(newMenu(null, null, "테스트 루트 메뉴")), null, null));

        List<MenuContent> tree = service.search();
        MenuContent found = tree.stream()
                .filter(m -> "테스트 루트 메뉴".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow();

        assertThat(found.getParentId()).isNull();
        assertThat(found.getProgram()).isEqualTo("adcode");

        createdRootId = found.getId();
    }

    @Test
    void persist_insertsChild_underParent() {
        service.persist(new MenuPersistRequest(List.of(newMenu(null, null, "부모 메뉴")), null, null));
        List<MenuContent> afterParent = service.search();
        String parentId = afterParent.stream()
                .filter(m -> "부모 메뉴".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow().getId();
        createdRootId = parentId;

        service.persist(new MenuPersistRequest(List.of(newMenu(null, parentId, "자식 메뉴")), null, null));

        List<MenuContent> tree = service.search();
        MenuContent parent = findById(tree, parentId);
        assertThat(parent.getChildren()).hasSize(1);
        assertThat(parent.getChildren().get(0).getLocale().get("ko_KR").getLabel()).isEqualTo("자식 메뉴");
    }

    @Test
    void persist_deletingParent_cascadesToChildren() {
        service.persist(new MenuPersistRequest(List.of(newMenu(null, null, "캐스케이드 부모")), null, null));
        String parentId = service.search().stream()
                .filter(m -> "캐스케이드 부모".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow().getId();

        service.persist(new MenuPersistRequest(List.of(newMenu(null, parentId, "캐스케이드 자식")), null, null));

        service.persist(new MenuPersistRequest(null, null,
                List.of(new MenuContent(parentId, null, null, null, null, null, null, null, null, null))));

        assertThat(menuBaseMapper.findById(parentId)).isEmpty();
    }

    @Test
    void persist_rejectsMissingLocale() {
        assertThatThrownBy(() -> service.persist(new MenuPersistRequest(
                List.of(new MenuContent(null, null, null, null, "adcode", "Y", "Y", null, null, 1)),
                null, null)))
                .isInstanceOf(MenuValidationException.class);
    }

    @Test
    void persist_conflictsOnUpdateOfMissingId() {
        assertThatThrownBy(() -> service.persist(new MenuPersistRequest(null,
                List.of(newMenu("M_MISSING", null, "값")),
                null)))
                .isInstanceOf(MenuConflictException.class);
    }

    @Test
    void persist_conflictsOnParentChange() {
        service.persist(new MenuPersistRequest(List.of(newMenu(null, null, "상위변경 대상")), null, null));
        String id = service.search().stream()
                .filter(m -> "상위변경 대상".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow().getId();
        createdRootId = id;

        service.persist(new MenuPersistRequest(List.of(newMenu(null, null, "새로운 상위 후보")), null, null));
        String otherParentId = service.search().stream()
                .filter(m -> "새로운 상위 후보".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow().getId();

        try {
            assertThatThrownBy(() -> service.persist(new MenuPersistRequest(null,
                    List.of(newMenu(id, otherParentId, "상위변경 대상")),
                    null)))
                    .isInstanceOf(MenuConflictException.class);
        } finally {
            service.persist(new MenuPersistRequest(null, null,
                    List.of(new MenuContent(otherParentId, null, null, null, null, null, null, null, null, null))));
        }
    }
}
