package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import kkdugi.app.batch.enums.CredentialType;

/** the authenticated runner; the runner id is decided from the token (credential) and can never be changed through the path or body. */
@Getter
@AllArgsConstructor
public class BatchAgentPrincipal {
    private final String runnerId;
    private final String credentialId;
    private final CredentialType type;
}
