package kkdugi.core.security;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriUtils;

import jakarta.servlet.http.HttpServletRequest;
import kkdugi.core.enums.Rbac;
import kkdugi.core.security.annotation.HasRole;
import kkdugi.core.security.annotation.RequireAuthority;
import kkdugi.core.security.authentication.SessionAuthentication;
import kkdugi.core.security.models.AuthorityBatch;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.security.models.SessionUser;

/** Request-scoped authorization, applied before transactions and business methods. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class SecurityChecker {

    public static final String MENU_ID_HEADER = "X-Menu-Id";
    public static final String SHELL_MENU_ID = "__shell__";
    private static final Pattern MENU_ID = Pattern.compile("[\\x20-\\x7e]{1,60}");

    @Around("execution(public * *(..)) && ("
            + "@within(kkdugi.core.security.annotation.HasRole) || "
            + "@annotation(kkdugi.core.security.annotation.HasRole) || "
            + "@within(kkdugi.core.security.annotation.RequireAuthority) || "
            + "@annotation(kkdugi.core.security.annotation.RequireAuthority))")
    public Object authorize(ProceedingJoinPoint invocation) throws Throwable {
        Class<?> targetClass = AopUtils.getTargetClass(invocation.getTarget());
        Method method = AopUtils.getMostSpecificMethod(
                ((MethodSignature) invocation.getSignature()).getMethod(), targetClass);
        SessionUser user = authenticatedUser();
        HttpServletRequest request = currentRequest();
        String menuId = menuId(request);

        // Class and method policies are cumulative: a method cannot weaken its class policy.
        checkRole(user, AnnotatedElementUtils.findMergedAnnotation(targetClass, HasRole.class));
        checkRole(user, AnnotatedElementUtils.findMergedAnnotation(method, HasRole.class));
        List<RequireAuthority> policies = new ArrayList<>();
        addPolicy(policies, AnnotatedElementUtils.findMergedAnnotation(targetClass, RequireAuthority.class));
        addPolicy(policies, AnnotatedElementUtils.findMergedAnnotation(method, RequireAuthority.class));

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (SHELL_MENU_ID.equals(menuId)) {
            if (!"GET".equals(request.getMethod()) || !"/api/v1.0/menu".equals(path)
                    || policies.isEmpty() || policies.stream().anyMatch(p -> !p.allowShell()
                            || !p.program().isEmpty() || p.batch())) {
                throw denied();
            }
            return invocation.proceed();
        }

        if (user.getMenus() == null) {
            throw denied();
        }
        SessionMenu menu = user.getMenus().stream().filter(m -> menuId.equals(m.getId()))
                .findFirst().orElseThrow(SecurityChecker::denied);
        if (!StringUtils.hasText(menu.getProgram()) || (menu.getAuthority() & 0x0f) == 0) {
            throw denied();
        }
        if (path.startsWith("/pragma/")
                && !menuId.equals(UriUtils.decode(path.substring("/pragma/".length()), StandardCharsets.UTF_8))) {
            throw denied();
        }
        for (RequireAuthority policy : policies) {
            if (!policy.program().isEmpty() && !policy.program().equals(menu.getProgram())) {
                throw denied();
            }
            int required = 0;
            for (Rbac permission : policy.value()) {
                required |= permission.getValue();
            }
            if (policy.batch()) {
                required |= batchAuthority(invocation.getArgs());
            }
            if ((menu.getAuthority() & required) != required) {
                throw denied();
            }
        }
        return invocation.proceed();
    }

    private static SessionUser authenticatedUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof SessionAuthentication session) || !session.isAuthenticated()
                || !StringUtils.hasText(session.getSessionId())
                || !(session.getPrincipal() instanceof SessionUser user)) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated session required");
        }
        return user;
    }

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw denied();
        }
        return attributes.getRequest();
    }

    private static String menuId(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(MENU_ID_HEADER));
        if (values.size() != 1) {
            throw denied();
        }
        String id = values.get(0);
        if (!MENU_ID.matcher(id).matches() || !id.equals(id.trim())) {
            throw denied();
        }
        return id;
    }

    private static void checkRole(SessionUser user, HasRole policy) {
        if (policy == null) {
            return;
        }
        for (String role : policy.value()) {
            if (StringUtils.hasText(role) && user.getAuthorities() != null && user.getAuthorities().stream()
                    .anyMatch(authority -> role.equals(authority.getAuthority()))) {
                return;
            }
        }
        throw denied();
    }

    private static void addPolicy(List<RequireAuthority> policies, RequireAuthority policy) {
        if (policy != null) {
            policies.add(policy);
        }
    }

    private static int batchAuthority(Object[] arguments) {
        AuthorityBatch batch = null;
        for (Object argument : arguments) {
            if (argument instanceof AuthorityBatch candidate) {
                if (batch != null) {
                    throw denied();
                }
                batch = candidate;
            }
        }
        // An absent/unsupported batch must not silently skip authorization.
        if (batch == null) {
            throw denied();
        }
        int required = 0;
        if (notEmpty(batch.getInsert()) || notEmpty(batch.getUpdate())) {
            required |= Rbac.WRTE.getValue();
        }
        if (notEmpty(batch.getDelete())) {
            required |= Rbac.DELT.getValue();
        }
        return required;
    }

    private static boolean notEmpty(List<?> items) {
        return items != null && !items.isEmpty();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
