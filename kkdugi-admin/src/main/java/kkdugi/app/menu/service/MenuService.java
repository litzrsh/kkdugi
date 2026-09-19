package kkdugi.app.menu.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import kkdugi.app.menu.mapper.MenuMapper;
import kkdugi.app.menu.models.Menu;
import kkdugi.app.menu.models.MenuLabel;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.util.CommonUtils;
import kkdugi.core.util.SessionUtils;
import kkdugi.core.util.TreeUtils;

/** 세션의 메뉴 접근 범위/구조는 유지하고, 표시 문구만 요청 언어로 조회한다. */
@Service
public class MenuService {

    private final MenuMapper mapper;

    public MenuService(MenuMapper mapper) {
        this.mapper = mapper;
    }

    public List<Menu> tree(String langCode) {
        List<SessionMenu> sessionMenus = SessionUtils.getUser().getMenus();
        if (sessionMenus.isEmpty()) {
            return List.of();
        }
        List<String> ids = sessionMenus.stream().map(SessionMenu::getId).toList();
        Map<String, MenuLabel> labels = mapper.findLabels(ids, langCode).stream()
                .collect(Collectors.toMap(MenuLabel::getMenuId, Function.identity()));
        List<Menu> items = sessionMenus.stream()
                .map(menu -> toMenu(menu, labels.get(menu.getId())))
                .toList();
        return TreeUtils.convert(items);
    }

    private static Menu toMenu(SessionMenu menu, MenuLabel label) {
        // 번역이 없는 메뉴도 트리에서 제거하지 않는다. 세션 자체는 변경하지 않는다.
        return new Menu(menu.getId(), menu.getParentId(),
                label == null ? menu.getTitle() : label.getTitle(),
                label == null ? menu.getRemarks() : label.getRemarks(),
                menu.getIcon(), menu.getSort(), CommonUtils.isNotEmpty(menu.getProgram()));
    }
}
