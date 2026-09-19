package kkdugi.api;

import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.code.models.Code;
import kkdugi.app.code.models.CodeParams;
import kkdugi.app.code.service.CodeService;
import kkdugi.core.models.Page;

/** 사용자용 공통코드 조회. 관리자용은 {@code kkdugi.api.admin.AdminCodeController}. */
@RestController
@RequestMapping("/api/v1.0/code")
public class CodeController {

    private final CodeService service;

    public CodeController(CodeService service) {
        this.service = service;
    }

    @GetMapping
    public Page<Code> children(CodeParams params, Locale locale) {
        return service.findChildren(params, locale.toString());
    }
}
