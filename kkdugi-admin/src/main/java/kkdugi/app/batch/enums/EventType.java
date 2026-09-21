package kkdugi.app.batch.enums;

import kkdugi.core.enums.CodeEnums;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum EventType implements CodeEnums {

    CREATED("CREATED", "batch.event.type.created"),
    UPDATED("UPDATED", "batch.event.type.updated"),
    DELETED("DELETED", "batch.event.type.deleted"),
    ENROLLMENT_ISSUED("ENROLLMENT_ISSUED", "batch.event.type.enrollment_issued"),
    REGISTERED("REGISTERED", "batch.event.type.registered"),
    SESSION_OPENED("SESSION_OPENED", "batch.event.type.session_opened"),
    REVOKED("REVOKED", "batch.event.type.revoked");

    private final String code;
    private final String labelCode;

    public static EventType fromCode(String code) {
        return CodeEnums.fromCode(EventType.class, code);
    }
}
