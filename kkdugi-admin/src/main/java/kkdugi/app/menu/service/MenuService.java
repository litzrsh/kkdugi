package kkdugi.app.menu.service;

import java.util.List;

import org.springframework.stereotype.Service;

import kkdugi.app.menu.models.Menu;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.util.CommonUtils;
import kkdugi.core.util.SessionUtils;
import kkdugi.core.util.TreeUtils;

/**
 * 로그인한 본인이 볼 수 있는 메뉴를 화면 내비게이션용 트리로 만든다. 세션에는
 * {@link SessionMenu}가 flat list로 저장돼 있다(화면 콘텐츠 로딩용
 * {@code program}/{@code authority} 필드 포함 — {@code kkdugi.web.admin.PragmaController}가
 * {@code SessionUtils.getMenu(menuId)}로 쓴다). 그 필드들은 내비게이션 응답에 필요/노출 대상이
 * 아니므로 {@link Menu}로 옮겨 담아 트리로 변환한다. 로그인하지 않은 요청은
 * {@link SessionUtils#getUser()}가 돌려주는 익명 사용자의 빈 메뉴 목록이라 빈 목록을 받는다.
 * DB를 읽지 않는다(세션 스냅샷 기반)라서 mapper가 없다. 관리자용 메뉴 CRUD는
 * {@code kkdugi.app.admin.menu.service.AdminMenuService}.
 */
@Service
public class MenuService {

    public List<Menu> tree() {
        List<Menu> items = SessionUtils.getUser().getMenus().stream()
                .map(MenuService::toMenu)
                .toList();
        return TreeUtils.convert(items);
    }

    private static Menu toMenu(SessionMenu menu) {
        return new Menu(menu.getId(), menu.getParentId(), menu.getTitle(), menu.getRemarks(),
                menu.getIcon(), menu.getSort(), CommonUtils.isNotEmpty(menu.getProgram()));
    }
}
