package kkdugi.api.admin.code;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.code.exceptions.CodeConflictException;
import kkdugi.app.admin.code.exceptions.CodeValidationException;
import kkdugi.app.admin.code.models.CodeContent;
import kkdugi.app.admin.code.models.CodePersistRequest;
import kkdugi.app.admin.code.models.CodeSearchParams;
import kkdugi.app.admin.code.service.CodeAdminService;
import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.models.Page;

@RestController
@RequestMapping("/api/v1.0/admin/code")
public class CodeAdminController {

    private final CodeAdminService service;

    public CodeAdminController(CodeAdminService service) {
        this.service = service;
    }

    @PostMapping
    public Page<CodeContent> search(@RequestBody CodeSearchParams params) {
        return service.search(params);
    }

    @PostMapping("/persist")
    public void persist(@RequestBody CodePersistRequest request) {
        service.persist(request);
    }

    @ExceptionHandler(CodeValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleValidation(CodeValidationException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(CodeConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ExceptionMessage handleConflict(CodeConflictException e) {
        return new ExceptionMessage(e.getCode());
    }
}
