package kkdugi.core.util;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import kkdugi.core.security.models.Authority;
import kkdugi.core.security.models.SessionUser;

import static org.assertj.core.api.Assertions.assertThat;

class SessionUtilsTest {

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
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
}
