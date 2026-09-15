package kkdugi.core.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "system.err.default",
            "admin_user.label.name",
            "a.b.c",
            "system.err.default_1"
    })
    void matches_returnsTrueForValidCodes(String code) {
        assertThat(MessageCode.matches(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "System.Err.Default",
            "system.err",
            "system.err.default.extra",
            "system.err.default!",
            "system..default",
            " system.err.default",
            "system.err.default ",
            "system.err.default\n"
    })
    void matches_returnsFalseForInvalidCodes(String code) {
        assertThat(MessageCode.matches(code)).isFalse();
    }

    @Test
    void matches_returnsFalseForNull() {
        assertThat(MessageCode.matches(null)).isFalse();
    }

    @Test
    void validate_doesNotThrowForValidCode() {
        MessageCode.validate("system.err.default");
    }

    @Test
    void validate_throwsForInvalidCode() {
        assertThatThrownBy(() -> MessageCode.validate("System.Err.Default"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
