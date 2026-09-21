package kkdugi.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.batch.config.BatchAgentSecurityConfig;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchAgentPrincipal;
import kkdugi.app.batch.models.BatchHeartbeatRequest;
import kkdugi.app.batch.models.BatchHeartbeatResponse;
import kkdugi.app.batch.models.BatchRegistrationRequest;
import kkdugi.app.batch.models.BatchRegistrationResponse;
import kkdugi.app.batch.models.BatchSessionRequest;
import kkdugi.app.batch.models.BatchSessionResponse;
import kkdugi.app.batch.service.BatchAgentService;

/**
 * Runner API(runner-api.md). 인증·자격증명 종류 검사는 {@code BatchAgentSecurityConfig}의 전용 보안 체인이,
 * 프로토콜 버전·no-store는 {@code BatchAgentInterceptor}가 처리한다. 여기서는 인증된 runner ID로 서비스를 호출한다.
 */
@RestController
@RequestMapping(BatchAgentSecurityConfig.AGENT_PATH)
public class BatchAgentController extends BatchApiSupport {

    private final BatchAgentService service;

    public BatchAgentController(BatchAgentService service) {
        this.service = service;
    }

    @PostMapping("/registrations")
    public ResponseEntity<BatchRegistrationResponse> register(@RequestBody BatchRegistrationRequest request) {
        BatchAgentPrincipal principal = principal();
        return ResponseEntity.status(201).body(service.register(principal.getRunnerId(), principal.getCredentialId(), request));
    }

    @PostMapping("/sessions")
    public BatchSessionResponse openSession(@RequestBody BatchSessionRequest request) {
        BatchAgentPrincipal principal = principal();
        return service.openSession(principal.getRunnerId(), principal.getCredentialId(), request);
    }

    @PostMapping("/heartbeat")
    public BatchHeartbeatResponse heartbeat(
            @RequestHeader(value = "X-Runner-Session", required = false) String session,
            @RequestBody BatchHeartbeatRequest request) {
        return service.heartbeat(principal().getRunnerId(), session, request);
    }

    private static BatchAgentPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof BatchAgentPrincipal principal)) {
            throw BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID);
        }
        return principal;
    }
}
