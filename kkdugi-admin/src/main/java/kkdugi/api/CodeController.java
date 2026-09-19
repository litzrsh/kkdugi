package kkdugi.api;

import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.ResponseEntity;
import kkdugi.core.exceptions.ExceptionMessage;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.code.models.Code;
import kkdugi.app.code.service.CodeService;

/** 사용자용 공통코드 조회. 관리자용은 {@code kkdugi.api.admin.AdminCodeController}. */
@RestController
@RequestMapping("/api/v1.0/code")
public class CodeController {

    private final CodeService service;

    public CodeController(CodeService service) {
        this.service = service;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ExceptionMessage> missingPath(MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest().body(new ExceptionMessage("code.err.malformed_request"));
    }

    @GetMapping
    public List<Code> children(@RequestParam String path, Locale locale) {
        return service.findCodes(path, locale.toString());
    }
}
