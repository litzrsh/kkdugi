package kkdugi.app.batch.enums;

import kkdugi.core.enums.CodeEnums;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum CredentialType implements CodeEnums {

    ENROLLMENT("ENROLLMENT", "batch.credential.type.enrollment"),
    ACCESS("ACCESS", "batch.credential.type.access");

    private final String code;
    private final String labelCode;

    public static CredentialType fromCode(String code) {
        return CodeEnums.fromCode(CredentialType.class, code);
    }
}
