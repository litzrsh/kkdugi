package kkdugi.core.util;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import kkdugi.core.security.authentication.SessionAuthentication;
import kkdugi.core.security.models.Authority;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.security.models.SessionUser;
import kkdugi.core.security.service.SessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SessionUtilsTest {

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        SessionUtils.setSessionService(null);
    }

    private Authority authority(String role) {
        Authority authority = new Authority();
        authority.setAuthority(role);
        return authority;
    }

    private void authenticateAs(SessionUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void getUser_noAuthentication_returnsAnonymous() {
        SessionUser user = SessionUtils.getUser();

        assertThat(user.getId()).isEqualTo(SessionUtils.ANONYMOUS_ID);
        assertThat(user.getUsername()).isEqualTo(SessionUtils.ANONYMOUS_USERNAME);
        assertThat(user.getAuthorities()).isEmpty();
    }

    @Test
    void getUser_authenticated_returnsSessionUserPrincipal() {
        SessionUser expected = new SessionUser();
        expected.setId("U_TEST_1");
        expected.setUsername("test_user");
        authenticateAs(expected);

        SessionUser found = SessionUtils.getUser();

        assertThat(found).isSameAs(expected);
    }

    @Test
    void hasAuthorityByRole_matchesOneOfGivenRoles_returnsTrue() {
        SessionUser user = new SessionUser();
        user.setAuthorities(List.of(authority("ROLE_ADMIN")));
        authenticateAs(user);

        assertThat(SessionUtils.hasAuthorityByRole("ROLE_USER", "ROLE_ADMIN")).isTrue();
    }

    @Test
    void hasAuthorityByRole_noMatch_returnsFalse() {
        SessionUser user = new SessionUser();
        user.setAuthorities(List.of(authority("ROLE_USER")));
        authenticateAs(user);

        assertThat(SessionUtils.hasAuthorityByRole("ROLE_ADMIN")).isFalse();
    }

    @Test
    void hasAuthorityByRole_emptyArgs_returnsFalse() {
        SessionUser user = new SessionUser();
        user.setAuthorities(List.of(authority("ROLE_ADMIN")));
        authenticateAs(user);

        assertThat(SessionUtils.hasAuthorityByRole()).isFalse();
    }

    @Test
    void hasAuthorityByRole_anonymousUser_returnsFalse() {
        assertThat(SessionUtils.hasAuthorityByRole("ROLE_ADMIN")).isFalse();
    }

    private SessionMenu menu(String id, int authority) {
        SessionMenu menu = new SessionMenu();
        menu.setId(id);
        menu.setAuthority(authority);
        return menu;
    }

    @Test
    void getMenu_returnsMatchingMenuFromSession() {
        SessionUser user = new SessionUser();
        user.setMenus(List.of(menu("M_1", 0x01), menu("M_2", 0x03)));
        authenticateAs(user);

        SessionMenu found = SessionUtils.getMenu("M_2");

        assertThat(found).isNotNull();
        assertThat(found.getAuthority()).isEqualTo(0x03);
    }

    @Test
    void getMenu_noMatch_returnsNull() {
        SessionUser user = new SessionUser();
        user.setMenus(List.of(menu("M_1", 0x01)));
        authenticateAs(user);

        assertThat(SessionUtils.getMenu("M_UNKNOWN")).isNull();
    }

    @Test
    void getMenu_anonymousUser_returnsNull() {
        assertThat(SessionUtils.getMenu("M_1")).isNull();
    }

    @Test
    void getAttribute_returnsSessionUserAttribute() {
        SessionUser user = new SessionUser();
        user.getAttributes().put("theme", "dark");
        authenticateAs(user);

        assertThat(SessionUtils.getAttribute("theme")).isEqualTo("dark");
        assertThat(SessionUtils.getAttribute("missing")).isNull();
    }

    @Test
    void getAttribute_anonymousUser_returnsNull() {
        assertThat(SessionUtils.getAttribute("theme")).isNull();
    }

    @Test
    void setAttribute_updatesDbBySessionIdAndCurrentUser() {
        SessionService service = mock(SessionService.class);
        SessionUtils.setSessionService(service);
        SecurityContextHolder.getContext().setAuthentication(new SessionAuthentication(new SessionUser(), "S_1"));

        SessionUtils.setAttribute("theme", "dark");

        verify(service).updateAttribute("S_1", "theme", "dark");
        assertThat(SessionUtils.getAttribute("theme")).isEqualTo("dark");
    }

    @Test
    void setAttribute_nullValueRemovesKeyFromCurrentUser() {
        SessionService service = mock(SessionService.class);
        SessionUtils.setSessionService(service);
        SessionUser user = new SessionUser();
        user.getAttributes().put("theme", "dark");
        SecurityContextHolder.getContext().setAuthentication(new SessionAuthentication(user, "S_1"));

        SessionUtils.setAttribute("theme", null);

        verify(service).updateAttribute("S_1", "theme", null);
        assertThat(SessionUtils.getAttribute("theme")).isNull();
    }

    @Test
    void setAttribute_withoutSessionAuthentication_throws() {
        SessionUtils.setSessionService(mock(SessionService.class));

        assertThatThrownBy(() -> SessionUtils.setAttribute("theme", "dark"))
                .isInstanceOf(IllegalStateException.class);

        authenticateAs(new SessionUser());
        assertThatThrownBy(() -> SessionUtils.setAttribute("theme", "dark"))
                .isInstanceOf(IllegalStateException.class);
    }
}
