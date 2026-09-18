package kkdugi.api.admin.session;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.util.SessionUtils;
import kkdugi.core.util.TreeUtils;

/**
 * 로그인한 본인이 볼 수 있는 메뉴를 화면 내비게이션용 트리로 내려준다.
 * {@code api-define-admin.md} 3절의 메뉴 관리(전체 CRUD, SYS_ADMIN 전용) API와는
 * 별개다. 세션에는 {@link SessionMenu}가 flat list로 저장돼 있다(화면 콘텐츠
 * 로딩용 {@code program}/{@code authority} 필드 포함 —
 * {@code kkdugi.web.admin.PragmaController}가 {@code SessionUtils.getMenu(menuId)}로
 * 이 필드들을 써서 프로그램별 Vue 조각을 서빙한다) — 그 필드들은 화면
 * 내비게이션 응답에는 필요/노출 대상이 아니므로, {@link MenuTreeItem}으로
 * 옮겨 담아 트리 모양으로 변환해 내려준다. 로그인하지 않은 요청은
 * {@link SessionUtils#getUser()}가 돌려주는 익명 사용자의 빈 메뉴 목록이라
 * 빈 배열을 받는다.
 */
@RestController
@RequestMapping("/api/v1.0/admin/session")
public class SessionMenuController {

    @GetMapping("/menu")
    public List<MenuTreeItem> menu() {
        List<MenuTreeItem> items = SessionUtils.getUser().getMenus().stream()
                .map(SessionMenuController::toTreeItem)
                .toList();
        return TreeUtils.convert(items);
    }

    private static MenuTreeItem toTreeItem(SessionMenu menu) {
        return new MenuTreeItem(menu.getId(), menu.getParentId(), menu.getTitle(), menu.getRemarks(),
                menu.getIcon(), menu.getSort());
    }
}
