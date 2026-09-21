package kkdugi.support;

import java.util.List;
import jakarta.servlet.Filter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import kkdugi.core.security.SecurityChecker;
import kkdugi.core.security.authentication.SessionAuthentication;
import kkdugi.core.security.models.Authority;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.security.models.SessionUser;

/** Domain contract fixtures use a session principal; dedicated security tests exercise the filter chain. */
public final class TestAuthorization {
    private TestAuthorization() {}

    public static SessionAuthentication session(String program, int bits, String... roles) {
        SessionMenu menu = new SessionMenu();
        menu.setId("M_TEST_API");
        menu.setProgram(program);
        menu.setAuthority(bits);
        SessionUser user = new SessionUser();
        user.setId("U_TEST_API");
        user.setMenus(List.of(menu));
        user.setAuthorities(java.util.Arrays.stream(roles).map(value -> {
            Authority role = new Authority(); role.setAuthority(value); return role;
        }).toList());
        return new SessionAuthentication(user, "S_TEST_API");
    }

    public static MockMvc mvc(WebApplicationContext context, String program) {
        return mvc(context, program, 15, "SYS_ADMIN");
    }

    /** 지정한 RBAC 비트/역할의 세션으로 호출하는 MockMvc — 인가 어노테이션 적용을 검증할 때 쓴다. */
    public static MockMvc mvc(WebApplicationContext context, String program, int bits, String... roles) {
        return mvcWithFilters(context, program, bits, new Filter[0], roles);
    }

    /** {@link #mvc}와 같지만 세션 주입 필터 뒤에 서블릿 필터를 더 등록한다(본문 한도 필터처럼 필터를 거치는 동작을 검증할 때 쓴다). */
    public static MockMvc mvcWithFilters(WebApplicationContext context, String program, int bits, Filter[] after,
            String... roles) {
        Filter session = (request, response, chain) -> {
            var previous = SecurityContextHolder.getContext();
            var security = SecurityContextHolder.createEmptyContext();
            security.setAuthentication(session(program, bits, roles));
            SecurityContextHolder.setContext(security);
            try { chain.doFilter(request, response); }
            finally { SecurityContextHolder.setContext(previous); }
        };
        Filter[] filters = new Filter[after.length + 1];
        filters[0] = session;
        System.arraycopy(after, 0, filters, 1, after.length);
        return MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(MockMvcRequestBuilders.get("/").header(SecurityChecker.MENU_ID_HEADER, "M_TEST_API"))
                .addFilters(filters).build();
    }
}
