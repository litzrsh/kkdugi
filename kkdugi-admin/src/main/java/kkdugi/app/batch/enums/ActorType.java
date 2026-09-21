package kkdugi.app.batch.enums;

import kkdugi.core.enums.CodeEnums;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ActorType implements CodeEnums {

    USER("USER", "batch.actor.type.user"),
    RUNNER("RUNNER", "batch.actor.type.runner"),
    SYSTEM("SYSTEM", "batch.actor.type.system");

    private final String code;
    private final String labelCode;

    public static ActorType fromCode(String code) {
        return CodeEnums.fromCode(ActorType.class, code);
    }
}
