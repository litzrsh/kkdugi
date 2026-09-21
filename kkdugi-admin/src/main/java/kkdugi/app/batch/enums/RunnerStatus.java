package kkdugi.app.batch.enums;

import kkdugi.core.enums.CodeEnums;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Runner 상태. 저장 코드는 상수 이름과 같다(kkdugi_batch_runner_stat_chk와 맞춘다). */
@RequiredArgsConstructor
@Getter
public enum RunnerStatus implements CodeEnums {

    REGISTERING("REGISTERING", "batch.runner.status.registering"),
    ACTIVE("ACTIVE", "batch.runner.status.active"),
    PAUSED("PAUSED", "batch.runner.status.paused"),
    REVOKED("REVOKED", "batch.runner.status.revoked");

    private final String code;
    private final String labelCode;

    public static RunnerStatus fromCode(String code) {
        return CodeEnums.fromCode(RunnerStatus.class, code);
    }
}
