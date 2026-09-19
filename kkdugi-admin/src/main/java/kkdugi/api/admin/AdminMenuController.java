package kkdugi.api.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.menu.exceptions.AdminMenuConflictException;
import kkdugi.app.admin.menu.exceptions.AdminMenuValidationException;
import kkdugi.app.admin.menu.models.AdminMenu;
import kkdugi.app.admin.menu.models.AdminMenuPersistRequest;
import kkdugi.app.admin.menu.service.AdminMenuService;
import kkdugi.core.exceptions.ExceptionMessage;

@RestController
@RequestMapping("/api/v1.0/admin/menu")
public class AdminMenuController {

    private final AdminMenuService service;

    public AdminMenuController(AdminMenuService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminMenu> search() {
        return service.search();
    }

    @PostMapping("/persist")
    public void persist(@RequestBody AdminMenuPersistRequest request) {
        service.persist(request);
    }

    @ExceptionHandler(AdminMenuValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleValidation(AdminMenuValidationException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminMenuConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ExceptionMessage handleConflict(AdminMenuConflictException e) {
        return new ExceptionMessage(e.getCode());
    }
}
