package kkdugi.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import kkdugi.core.enums.Rbac;
import kkdugi.core.security.annotation.HasRole;
import kkdugi.core.security.annotation.RequireAuthority;
import kkdugi.core.security.authentication.SessionAuthentication;
import kkdugi.core.security.models.Authority;
import kkdugi.core.security.models.AuthorityBatch;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.security.models.SessionUser;

class SecurityCheckerTest {
    private MockHttpServletRequest request;
    private SessionUser user;
    private SecuredService target;
    private SecuredService service;

    @BeforeEach
    void setUp() {
        user = new SessionUser();
        user.setId("U_TEST");
        SessionMenu menu = new SessionMenu();
        menu.setId("M_TEST");
        menu.setProgram("admin/code");
        menu.setAuthority(15);
        user.setMenus(List.of(menu));
        authenticate();
        request = new MockHttpServletRequest("POST", "/kk/api/v1.0/admin/code");
        request.setContextPath("/kk");
        request.addHeader(SecurityChecker.MENU_ID_HEADER, "M_TEST");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        target = new SecuredService();
        service = proxy(target);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new SessionAuthentication(user, "S_TEST"));
    }

    private static <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new SecurityChecker());
        return factory.getProxy();
    }

    private void role(String value) {
        Authority role = new Authority();
        role.setAuthority(value);
        user.setAuthorities(List.of(role));
    }

    private void header(String value) {
        request.removeHeader(SecurityChecker.MENU_ID_HEADER);
        if (value != null) request.addHeader(SecurityChecker.MENU_ID_HEADER, value);
    }

    @Test
    void validMenuAndAllRequiredBits_allowExecution() {
        service.read();
        service.execute();
        assertThat(target.calls).isEqualTo(2);
    }

    @Test
    void anonymousOrNonSessionAuthentication_neverExecutes() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(service::read).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        assertThatThrownBy(service::read).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(target.calls).isZero();
    }

    @Test
    void missingMalformedAndDuplicateHeader_denyBeforeExecution() {
        for (String id : new String[]{null, "", " M_TEST", "M_TEST ", "M\r\nx", "한글", "M".repeat(61)}) {
            header(id);
            assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
        }
        header("M_TEST");
        request.addHeader(SecurityChecker.MENU_ID_HEADER, "M_TEST");
        assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
        assertThat(target.calls).isZero();
    }

    @Test
    void unknownMenuOrWrongProgramOrGroupOrZeroBits_denyEvenSysAdmin() {
        role("SYS_ADMIN");
        header("M_OTHER");
        assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
        header("M_TEST");
        for (String program : new String[]{"admin/menu", "", null}) {
            user.getMenus().get(0).setProgram(program);
            assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
        }
        user.getMenus().get(0).setProgram("admin/code");
        user.getMenus().get(0).setAuthority(0);
        assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
        assertThat(target.calls).isZero();
    }

    @Test
    void allBitsAreRequired_andReadDoesNotPermitExecute() {
        user.getMenus().get(0).setAuthority(1);
        service.read();
        assertThatThrownBy(service::execute).isInstanceOf(AccessDeniedException.class);
        user.getMenus().get(0).setAuthority(8);
        assertThatThrownBy(service::execute).isInstanceOf(AccessDeniedException.class);
        assertThat(target.calls).isEqualTo(1);
    }

    @Test
    void batchRequiresOnlyItsOperations_andRejectsMixedBatchAtomically() {
        user.getMenus().get(0).setAuthority(2);
        service.save(new Changes(List.of("new"), null, null));
        service.save(new Changes(null, List.of("changed"), null));
        assertThatThrownBy(() -> service.save(new Changes(List.of("new"), null, List.of("deleted"))))
                .isInstanceOf(AccessDeniedException.class);
        user.getMenus().get(0).setAuthority(4);
        service.save(new Changes(null, null, List.of("deleted")));
        assertThatThrownBy(() -> service.save(new Changes(List.of("new"), null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(target.calls).isEqualTo(3);
    }

    @Test
    void unsupportedOrMissingBatchCannotSkipChecks() {
        assertThatThrownBy(() -> service.save(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(service::badBatch).isInstanceOf(AccessDeniedException.class);
        service.save(new Changes(null, null, null));
        assertThat(target.calls).isEqualTo(1);
    }

    @Test
    void roleListIsOrAndUsesExactCodes_andCombinesWithRbac() {
        role("ROLE_EDITOR");
        assertThatThrownBy(service::edit).isInstanceOf(AccessDeniedException.class);
        role("EDITOR");
        service.edit();
        role("OWNER");
        service.edit();
        user.getMenus().get(0).setAuthority(1);
        assertThatThrownBy(service::edit).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(service::emptyRole).isInstanceOf(AccessDeniedException.class);
        assertThat(target.calls).isEqualTo(2);
    }

    @Test
    void classAndMethodPoliciesAreCumulative() {
        ClassPolicyService restricted = proxy(new ClassPolicyService());
        role("EDITOR");
        assertThatThrownBy(restricted::edit).isInstanceOf(AccessDeniedException.class);
        role("OWNER");
        user.getMenus().get(0).setAuthority(2);
        assertThatThrownBy(restricted::edit).isInstanceOf(AccessDeniedException.class);
        user.getMenus().get(0).setAuthority(3);
        assertThat(restricted.edit()).isEqualTo("ok");
    }

    @Test
    void shellRequiresExplicitOptInExactGetRouteAndAuthentication() {
        header(SecurityChecker.SHELL_MENU_ID);
        request.setRequestURI("/kk/api/v1.0/menu");
        request.setMethod("GET");
        user.setMenus(List.of());
        service.menus();
        assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
        request.setMethod("POST");
        assertThatThrownBy(service::menus).isInstanceOf(AccessDeniedException.class);
        request.setMethod("GET");
        request.setRequestURI("/kk/api/v1.0/admin/menu");
        assertThatThrownBy(service::menus).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(service::menus).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(target.calls).isEqualTo(1);
    }

    @Test
    void pragmaHeaderMustMatchPath() {
        request.setRequestURI("/kk/pragma/M_OTHER");
        assertThatThrownBy(service::screen).isInstanceOf(AccessDeniedException.class);
        request.setRequestURI("/kk/pragma/M_TEST");
        service.screen();
        assertThat(target.calls).isEqualTo(1);
    }

    @Test
    void serviceWithoutRequestFailsClosed() {
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
    }

    public static class SecuredService {
        int calls;
        @RequireAuthority(value = Rbac.READ, program = "admin/code")
        public void read() { calls++; }
        @RequireAuthority({Rbac.READ, Rbac.EXEC})
        public void execute() { calls++; }
        @RequireAuthority(program = "admin/code", batch = true)
        public void save(Changes changes) { calls++; }
        @RequireAuthority(batch = true)
        public void badBatch() { calls++; }
        @HasRole({"OWNER", "EDITOR"})
        @RequireAuthority(Rbac.WRTE)
        public void edit() { calls++; }
        @HasRole({})
        public void emptyRole() { calls++; }
        @RequireAuthority(value = Rbac.READ, allowShell = true)
        public void menus() { calls++; }
        @RequireAuthority
        public void screen() { calls++; }
    }

    @HasRole("OWNER")
    @RequireAuthority(Rbac.READ)
    public static class ClassPolicyService {
        @RequireAuthority(Rbac.WRTE)
        @HasRole({"OWNER", "EDITOR"})
        public String edit() { return "ok"; }
    }

    public static class Changes implements AuthorityBatch {
        private final List<?> insert, update, delete;
        Changes(List<?> insert, List<?> update, List<?> delete) {
            this.insert = insert; this.update = update; this.delete = delete;
        }
        public List<?> getInsert() { return insert; }
        public List<?> getUpdate() { return update; }
        public List<?> getDelete() { return delete; }
    }
}
