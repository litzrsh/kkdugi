package kkdugi.app.admin.menu.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.menu.exceptions.AdminMenuConflictException;
import kkdugi.app.admin.menu.exceptions.AdminMenuValidationException;
import kkdugi.app.admin.menu.mapper.AdminMenuMapper;
import kkdugi.app.admin.menu.models.AdminMenu;
import kkdugi.app.admin.menu.models.AdminMenuLocale;
import kkdugi.app.admin.menu.models.AdminMenuPersistRequest;
import kkdugi.app.admin.menu.models.MenuBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminMenuServiceTest {

    @Autowired
    private AdminMenuService service;

    @Autowired
    private AdminMenuMapper adminMenuMapper;

    private String createdRootId;

    @AfterEach
    void cleanUp() {
        if (createdRootId != null) {
            String path = adminMenuMapper.findById(createdRootId).map(MenuBase::getPath).orElse("/__missing__");
            List<MenuBase> targets = adminMenuMapper.findSelfAndDescendants(path);
            List<String> ids = targets.stream().map(MenuBase::getId).toList();
            if (!ids.isEmpty()) {
                adminMenuMapper.deleteLangByMenuIds(ids);
                adminMenuMapper.deleteByIds(ids);
            }
            createdRootId = null;
        }
    }

    private static AdminMenu newMenu(String id, String parentId, String label) {
        return new AdminMenu(id, parentId, Map.of("ko_KR", new AdminMenuLocale(label, null)),
                null, "adcode", "Y", "Y", null, null, 1);
    }

    private AdminMenu findById(List<AdminMenu> tree, String id) {
        for (AdminMenu node : tree) {
            if (node.getId().equals(id)) {
                return node;
            }
            if (node.getChildren() != null) {
                AdminMenu found = findById(node.getChildren(), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void persist_insertsRootMenu_thenSearchReturnsItInTree() {
        service.persist(new AdminMenuPersistRequest(
                List.of(newMenu(null, null, "테스트 루트 메뉴")), null, null));

        List<AdminMenu> tree = service.search();
        AdminMenu found = tree.stream()
                .filter(m -> "테스트 루트 메뉴".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow();

        assertThat(found.getParentId()).isNull();
        assertThat(found.getProgram()).isEqualTo("adcode");

        createdRootId = found.getId();
    }

    @Test
    void persist_insertsChild_underParent() {
        service.persist(new AdminMenuPersistRequest(List.of(newMenu(null, null, "부모 메뉴")), null, null));
        List<AdminMenu> afterParent = service.search();
        String parentId = afterParent.stream()
                .filter(m -> "부모 메뉴".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow().getId();
        createdRootId = parentId;

        service.persist(new AdminMenuPersistRequest(List.of(newMenu(null, parentId, "자식 메뉴")), null, null));

        List<AdminMenu> tree = service.search();
        AdminMenu parent = findById(tree, parentId);
        assertThat(parent.getChildren()).hasSize(1);
        assertThat(parent.getChildren().get(0).getLocale().get("ko_KR").getLabel()).isEqualTo("자식 메뉴");
    }

    @Test
    void persist_deletingParent_cascadesToChildren() {
        service.persist(new AdminMenuPersistRequest(List.of(newMenu(null, null, "캐스케이드 부모")), null, null));
        String parentId = service.search().stream()
                .filter(m -> "캐스케이드 부모".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow().getId();

        service.persist(new AdminMenuPersistRequest(List.of(newMenu(null, parentId, "캐스케이드 자식")), null, null));

        service.persist(new AdminMenuPersistRequest(null, null,
                List.of(new AdminMenu(parentId, null, null, null, null, null, null, null, null, null))));

        assertThat(adminMenuMapper.findById(parentId)).isEmpty();
    }

    @Test
    void persist_rejectsMissingLocale() {
        assertThatThrownBy(() -> service.persist(new AdminMenuPersistRequest(
                List.of(new AdminMenu(null, null, null, null, "adcode", "Y", "Y", null, null, 1)),
                null, null)))
                .isInstanceOf(AdminMenuValidationException.class);
    }

    @Test
    void persist_conflictsOnUpdateOfMissingId() {
        assertThatThrownBy(() -> service.persist(new AdminMenuPersistRequest(null,
                List.of(newMenu("M_MISSING", null, "값")),
                null)))
                .isInstanceOf(AdminMenuConflictException.class);
    }

    @Test
    void persist_conflictsOnParentChange() {
        service.persist(new AdminMenuPersistRequest(List.of(newMenu(null, null, "상위변경 대상")), null, null));
        String id = service.search().stream()
                .filter(m -> "상위변경 대상".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow().getId();
        createdRootId = id;

        service.persist(new AdminMenuPersistRequest(List.of(newMenu(null, null, "새로운 상위 후보")), null, null));
        String otherParentId = service.search().stream()
                .filter(m -> "새로운 상위 후보".equals(m.getLocale().get("ko_KR").getLabel()))
                .findFirst().orElseThrow().getId();

        try {
            assertThatThrownBy(() -> service.persist(new AdminMenuPersistRequest(null,
                    List.of(newMenu(id, otherParentId, "상위변경 대상")),
                    null)))
                    .isInstanceOf(AdminMenuConflictException.class);
        } finally {
            service.persist(new AdminMenuPersistRequest(null, null,
                    List.of(new AdminMenu(otherParentId, null, null, null, null, null, null, null, null, null))));
        }
    }
}
