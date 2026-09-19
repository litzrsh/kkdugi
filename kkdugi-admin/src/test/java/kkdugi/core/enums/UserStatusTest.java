package kkdugi.core.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserStatusTest {

    @Test
    void fromCode_roundTripsForEveryConstant() {
        for (UserStatus status : UserStatus.values()) {
            assertThat(UserStatus.fromCode(status.getCode())).isEqualTo(status);
        }
    }

    @Test
    void fromCode_throwsForUnknownCode() {
        assertThatThrownBy(() -> UserStatus.fromCode("99"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromCode_throwsForNullCode() {
        assertThatThrownBy(() -> UserStatus.fromCode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
