package kkdugi.api.admin.i18n;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.i18n.exceptions.MessageConflictException;
import kkdugi.app.admin.i18n.exceptions.MessageValidationException;
import kkdugi.app.admin.i18n.models.MessageContent;
import kkdugi.app.admin.i18n.models.MessagePersistRequest;
import kkdugi.app.admin.i18n.models.MessageSearchParams;
import kkdugi.app.admin.i18n.service.MessageAdminService;
import kkdugi.core.models.Page;

@RestController
@RequestMapping("/api/v1.0/admin/i18n")
public class MessageAdminController {

    private final MessageAdminService service;

    public MessageAdminController(MessageAdminService service) {
        this.service = service;
    }

    @PostMapping
    public Page<MessageContent> search(@RequestBody MessageSearchParams params) {
        return service.search(params);
    }

    @PostMapping("/persist")
    public void persist(@RequestBody MessagePersistRequest request) {
        service.persist(request);
    }

    @ExceptionHandler(MessageValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public MessageErrorResponse handleValidation(MessageValidationException e) {
        return new MessageErrorResponse(e.getErrors());
    }

    @ExceptionHandler(MessageConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public MessageErrorResponse handleConflict(MessageConflictException e) {
        return new MessageErrorResponse(e.getErrors());
    }
}
