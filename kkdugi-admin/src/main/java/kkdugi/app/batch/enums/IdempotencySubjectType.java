package kkdugi.app.batch.enums;

import kkdugi.core.enums.CodeEnums;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum IdempotencySubjectType implements CodeEnums {

    USER("USER", "batch.idempotency.subject.user"),
    RUNNER("RUNNER", "batch.idempotency.subject.runner");

    private final String code;
    private final String labelCode;

    public static IdempotencySubjectType fromCode(String code) {
        return CodeEnums.fromCode(IdempotencySubjectType.class, code);
    }
}
