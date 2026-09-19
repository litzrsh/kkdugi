package kkdugi.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.menu.models.Menu;
import kkdugi.app.menu.service.MenuService;
import kkdugi.core.security.annotation.RequireAuthority;

/** 사용자용 메뉴 조회(내 메뉴 트리). 관리자용 메뉴 CRUD는 {@code kkdugi.api.admin.AdminMenuController}. */
@RestController
@RequestMapping("/api/v1.0/menu")
public class MenuController {

    private final MenuService service;

    public MenuController(MenuService service) {
        this.service = service;
    }

    @RequireAuthority(value = kkdugi.core.enums.Rbac.READ, allowShell = true)
    @GetMapping
    public List<Menu> menu() {
        return service.tree();
    }
}
