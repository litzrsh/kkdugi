package kkdugi.api.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.authority.exceptions.AdminAuthorityConflictException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityNotFoundException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityValidationException;
import kkdugi.app.admin.authority.models.AdminAuthority;
import kkdugi.app.admin.authority.models.AdminAuthorityCandidate;
import kkdugi.app.admin.authority.models.AdminAuthorityCandidateQuery;
import kkdugi.app.admin.authority.models.AdminAuthorityMenuNode;
import kkdugi.app.admin.authority.models.AdminAuthorityParams;
import kkdugi.app.admin.authority.models.AdminAuthorityPersistRequest;
import kkdugi.app.admin.authority.service.AdminAuthorityService;
import kkdugi.core.Constants;
import kkdugi.core.enums.Rbac;
import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.models.Page;
import kkdugi.core.security.annotation.HasRole;
import kkdugi.core.security.annotation.RequireAuthority;

/**
 * 권한 관리 API. 메뉴 관리 API와 같은 기준으로 SYS_ADMIN 역할과 {@code admin/authority}
 * 메뉴의 RBAC를 요구한다 — 이 API는 사용자에게 SYS_ADMIN을 포함한 권한을 부여할 수 있어서
 * 메뉴 RBAC만으로 열어두면 권한 상승 경로가 된다. 조회는 READ, 등록/저장은 WRTE, 삭제는 DELT.
 */
@RestController
@RequestMapping("/api/v1.0/admin/authority")
public class AdminAuthorityController {

    private static final String PROGRAM = "admin/authority";

    private final AdminAuthorityService service;

    public AdminAuthorityController(AdminAuthorityService service) {
        this.service = service;
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping
    public Page<AdminAuthority> search(@RequestBody AdminAuthorityParams params) {
        return service.search(params);
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @GetMapping("/{id}")
    public AdminAuthority get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/regist")
    public AdminAuthority regist(@RequestBody AdminAuthorityPersistRequest request) {
        return service.regist(request);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}")
    public AdminAuthority save(@PathVariable("id") String id, @RequestBody AdminAuthorityPersistRequest request) {
        return service.save(id, request);
    }

    @RequireAuthority(value = Rbac.DELT, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/delete")
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/user")
    public List<AdminAuthorityCandidate> candidates(@PathVariable("id") String id,
            @RequestBody(required = false) AdminAuthorityCandidateQuery request) {
        return service.searchCandidates(id, request == null ? null : request.getQuery());
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/menu")
    public List<AdminAuthorityMenuNode> menus(@PathVariable("id") String id) {
        return service.menus(id);
    }

    @ExceptionHandler(AdminAuthorityValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleValidation(AdminAuthorityValidationException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminAuthorityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ExceptionMessage handleNotFound(AdminAuthorityNotFoundException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminAuthorityConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ExceptionMessage handleConflict(AdminAuthorityConflictException e) {
        return new ExceptionMessage(e.getCode());
    }
}
