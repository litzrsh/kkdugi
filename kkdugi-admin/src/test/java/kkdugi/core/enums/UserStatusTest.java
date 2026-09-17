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

    @Test
    void getLabel_fallsBackToLabelCode_whenMessageNotResolved() {
        // 이 테스트는 Spring 컨텍스트 없이 도는 순수 단위 테스트라
        // MessageUtils에 실제 MessageSource가 연결돼 있어도(다른
        // @SpringBootTest가 먼저 떴을 경우) "user.status.*" 메시지 키는
        // DB/properties 어디에도 등록돼 있지 않으므로 코드 자체가
        // 반환된다 - 어느 쪽이든 결과는 labelCode와 같다.
        for (UserStatus status : UserStatus.values()) {
            assertThat(status.getLabel()).isEqualTo(status.getLabelCode());
        }
    }
}
