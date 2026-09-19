package kkdugi.api.admin.menu;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.menu.exceptions.MenuConflictException;
import kkdugi.app.admin.menu.exceptions.MenuValidationException;
import kkdugi.app.admin.menu.models.MenuContent;
import kkdugi.app.admin.menu.models.MenuPersistRequest;
import kkdugi.app.admin.menu.service.MenuAdminService;
import kkdugi.core.exceptions.ExceptionMessage;

@RestController
@RequestMapping("/api/v1.0/admin/menu")
public class MenuAdminController {

    private final MenuAdminService service;

    public MenuAdminController(MenuAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<MenuContent> search() {
        return service.search();
    }

    @PostMapping("/persist")
    public void persist(@RequestBody MenuPersistRequest request) {
        service.persist(request);
    }

    @ExceptionHandler(MenuValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleValidation(MenuValidationException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(MenuConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ExceptionMessage handleConflict(MenuConflictException e) {
        return new ExceptionMessage(e.getCode());
    }
}
