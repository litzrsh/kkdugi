package kkdugi.api.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.code.exceptions.AdminCodeConflictException;
import kkdugi.app.admin.code.exceptions.AdminCodeValidationException;
import kkdugi.app.admin.code.models.AdminCode;
import kkdugi.app.admin.code.models.AdminCodeParams;
import kkdugi.app.admin.code.models.AdminCodePersistRequest;
import kkdugi.app.admin.code.service.AdminCodeService;
import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.models.Page;

@RestController
@RequestMapping("/api/v1.0/admin/code")
public class AdminCodeController {

    private final AdminCodeService service;

    public AdminCodeController(AdminCodeService service) {
        this.service = service;
    }

    @PostMapping
    public Page<AdminCode> search(@RequestBody AdminCodeParams params) {
        return service.search(params);
    }

    @PostMapping("/persist")
    public void persist(@RequestBody AdminCodePersistRequest request) {
        service.persist(request);
    }

    @ExceptionHandler(AdminCodeValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleValidation(AdminCodeValidationException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminCodeConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ExceptionMessage handleConflict(AdminCodeConflictException e) {
        return new ExceptionMessage(e.getCode());
    }
}
