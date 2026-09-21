package kkdugi.app.batch.enums;

import kkdugi.core.enums.CodeEnums;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum EventTargetType implements CodeEnums {

    RUNNER("RUNNER", "batch.event.target.runner");

    private final String code;
    private final String labelCode;

    public static EventTargetType fromCode(String code) {
        return CodeEnums.fromCode(EventTargetType.class, code);
    }
}
