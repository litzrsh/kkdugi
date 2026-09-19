package kkdugi.api.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.user.exceptions.AdminUserConflictException;
import kkdugi.app.admin.user.exceptions.AdminUserNotFoundException;
import kkdugi.app.admin.user.exceptions.AdminUserValidationException;
import kkdugi.app.admin.user.models.AdminUser;
import kkdugi.app.admin.user.models.AdminUserAuthorityQuery;
import kkdugi.app.admin.user.models.AdminUserAuthoritiesRequest;
import kkdugi.app.admin.user.models.AdminUserAuthority;
import kkdugi.app.admin.user.models.AdminUserChangeStatusRequest;
import kkdugi.app.admin.user.models.AdminUserIds;
import kkdugi.app.admin.user.models.AdminUserParams;
import kkdugi.app.admin.user.models.AdminUserPersistRequest;
import kkdugi.app.admin.user.service.AdminUserService;
import kkdugi.core.Constants;
import kkdugi.core.enums.Rbac;
import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.models.Page;
import kkdugi.core.security.annotation.HasRole;
import kkdugi.core.security.annotation.RequireAuthority;

/**
 * 사용자 관리 API. 권한 관리 API와 같은 기준으로 SYS_ADMIN 역할과 {@code admin/user} 메뉴의 RBAC를
 * 요구한다 — 사용자에게 권한을 부여하고 비밀번호를 초기화할 수 있어 메뉴 RBAC만으로는 권한 상승
 * 경로가 된다. 조회는 READ, 등록/저장/초기화/상태 변경은 WRTE, 삭제는 DELT. 권한 저장은 프로젝트의
 * 배치 규약을 따라 insert/update가 있으면 WRTE, 비어 있지 않은 delete가 있으면 DELT를 요구한다.
 */
@RestController
@RequestMapping("/api/v1.0/admin/user")
public class AdminUserController {

    private static final String PROGRAM = "admin/user";

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping
    public Page<AdminUser> search(@RequestBody AdminUserParams params) {
        return service.search(params);
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @GetMapping("/{id}")
    public AdminUser get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/regist")
    public AdminUser regist(@RequestBody AdminUserPersistRequest request) {
        return service.regist(request);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}")
    public AdminUser save(@PathVariable("id") String id, @RequestBody AdminUserPersistRequest request) {
        return service.save(id, request);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/reset-password")
    public void resetPassword(@RequestBody AdminUserIds request) {
        service.resetPassword(request);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/change-status")
    public void changeStatus(@RequestBody AdminUserChangeStatusRequest request) {
        service.changeStatus(request);
    }

    @RequireAuthority(value = Rbac.DELT, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/delete")
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @GetMapping("/{id}/authorities")
    public List<AdminUserAuthority> authorities(@PathVariable("id") String id) {
        return service.authorities(id);
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/authority-candidates")
    public List<AdminUserAuthority> authorityCandidates(@PathVariable("id") String id,
            @RequestBody(required = false) AdminUserAuthorityQuery query) {
        return service.authorityCandidates(id, query == null ? null : query.getQuery());
    }

    @RequireAuthority(program = PROGRAM, batch = true)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/authorities")
    public List<AdminUserAuthority> saveAuthorities(@PathVariable("id") String id,
            @RequestBody AdminUserAuthoritiesRequest request) {
        return service.saveAuthorities(id, request);
    }

    @ExceptionHandler(AdminUserValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleValidation(AdminUserValidationException e) {
        return new ExceptionMessage(e.getCode());
    }

    /**
     * 읽을 수 없는 본문(깨진 JSON, 잘못된 날짜 형식 등)은 400이다 — 처리하지 않으면 공통 예외 처리기의
     * 500으로 떨어진다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleUnreadable(HttpMessageNotReadableException e) {
        return new ExceptionMessage(AdminUserService.ERR_MALFORMED_REQUEST);
    }

    @ExceptionHandler(AdminUserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ExceptionMessage handleNotFound(AdminUserNotFoundException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminUserConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ExceptionMessage handleConflict(AdminUserConflictException e) {
        return new ExceptionMessage(e.getCode());
    }
}
