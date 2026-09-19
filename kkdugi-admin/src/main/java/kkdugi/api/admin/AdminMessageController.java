package kkdugi.api.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.i18n.exceptions.AdminMessageConflictException;
import kkdugi.app.admin.i18n.exceptions.AdminMessageValidationException;
import kkdugi.app.admin.i18n.models.AdminMessage;
import kkdugi.app.admin.i18n.models.AdminMessageParams;
import kkdugi.app.admin.i18n.models.AdminMessagePersistRequest;
import kkdugi.app.admin.i18n.service.AdminMessageService;
import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.models.Page;

@RestController
@RequestMapping("/api/v1.0/admin/i18n")
public class AdminMessageController {

    private final AdminMessageService service;

    public AdminMessageController(AdminMessageService service) {
        this.service = service;
    }

    @PostMapping
    public Page<AdminMessage> search(@RequestBody AdminMessageParams params) {
        return service.search(params);
    }

    @PostMapping("/persist")
    public void persist(@RequestBody AdminMessagePersistRequest request) {
        service.persist(request);
    }

    @ExceptionHandler(AdminMessageValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleValidation(AdminMessageValidationException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminMessageConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ExceptionMessage handleConflict(AdminMessageConflictException e) {
        return new ExceptionMessage(e.getCode());
    }
}
