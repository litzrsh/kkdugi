package kkdugi.app.batch.config;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import kkdugi.app.batch.models.BatchAgentPrincipal;

/** 자격증명 종류가 권한({@code BATCH_ENROLLMENT}/{@code BATCH_ACCESS})이 되어 endpoint별 종류 검사를 한다. */
public class BatchAgentAuthentication extends AbstractAuthenticationToken {

    private final BatchAgentPrincipal principal;

    public BatchAgentAuthentication(BatchAgentPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("BATCH_" + principal.getType().name())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
