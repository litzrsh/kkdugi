package kkdugi.app.batch.exceptions;

/** 배치 오류 코드. 모든 값은 messages*.properties에 메시지가 있어야 한다(BatchMessagesTest가 검사). */
public final class BatchErrors {
    private BatchErrors() {}

    public static final String REQUEST_INVALID = "batch.request.invalid";
    public static final String CREDENTIAL_INVALID = "batch.credential.invalid";
    public static final String RUNNER_FORBIDDEN = "batch.runner.forbidden";
    public static final String RUNNER_NOT_FOUND = "batch.runner.not_found";
    public static final String RUNNER_DUPLICATE_CODE = "batch.runner.duplicate_code";
    public static final String STATE_CONFLICT = "batch.state.conflict";
    public static final String SESSION_STALE = "batch.session.stale";
    public static final String PROTOCOL_UNSUPPORTED = "batch.protocol.unsupported";
    public static final String PLATFORM_UNSUPPORTED = "batch.platform.unsupported";
    public static final String IDEMPOTENCY_CONFLICT = "batch.idempotency.conflict";
    public static final String VERSION_CONFLICT = "batch.version.conflict";
    public static final String VERSION_REQUIRED = "batch.version.required";
    public static final String REQUEST_EXPIRED = "batch.request.expired";
    public static final String PAYLOAD_TOO_LARGE = "batch.payload.too_large";
}
