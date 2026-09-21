package kkdugi.app.batch.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import kkdugi.core.enums.CodeEnums;

class BatchEnumsTest {

    @Test
    void codeEqualsNameForEveryBatchEnum() {
        for (Class<? extends CodeEnums> type : java.util.List.of(RunnerStatus.class, CredentialType.class,
                ActorType.class, EventTargetType.class, EventType.class, IdempotencySubjectType.class)) {
            for (CodeEnums value : type.getEnumConstants()) {
                assertThat(value.getCode()).isEqualTo(((Enum<?>) value).name());
            }
        }
    }

    @Test
    void runnerStatusFromCode() {
        assertThat(RunnerStatus.fromCode("ACTIVE")).isEqualTo(RunnerStatus.ACTIVE);
        assertThatThrownBy(() -> RunnerStatus.fromCode("BOGUS")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enumeratesContractValues() {
        assertThat(RunnerStatus.values()).extracting(Enum::name)
                .containsExactly("REGISTERING", "ACTIVE", "PAUSED", "REVOKED");
        assertThat(CredentialType.values()).extracting(Enum::name).containsExactly("ENROLLMENT", "ACCESS");
    }
}
