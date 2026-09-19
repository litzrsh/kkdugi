package kkdugi.core.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class CodeEnumsTest {

    @Test
    void toCodes_preservesDeclarationOrderAndReturnsIndependentRows() {
        var codes = CodeEnums.toCodes(UserStatus.class);
        assertThat(codes).extracting(code -> code.getCode()).containsExactly("10", "20", "30", "40", "50");
        assertThat(codes).extracting(code -> code.getSort()).containsExactly(1, 2, 3, 4, 5);
        assertThat(codes.get(0).getId()).isEqualTo("UserStatus.10");
        assertThat(codes.get(0).getPath()).isEqualTo("UserStatus");
        assertThat(codes.get(0).getParentId()).isNull();
        codes.get(0).setName("changed");
        assertThat(CodeEnums.toCodes("UserStatus").get(0).getName()).isEqualTo(UserStatus.PEND.getLabel());
    }

    @Test
    void toCodes_rejectsMissingForeignAndNonEnumTypes() {
        for (String name : new String[]{"", "Missing", "CodeEnums", "java.lang.Thread", "../UserStatus", "userstatus"}) {
            assertThatThrownBy(() -> CodeEnums.toCodes(name)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CodeEnums.toCodes((String) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodeEnums.toCodes(CodeEnums.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyCoreEnumLabelIsPresentInEveryMessageBundle() throws Exception {
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:kkdugi/core/enums/*.class");
        for (String suffix : new String[]{"", "_ko_KR", "_en_US"}) {
            Properties messages = new Properties();
            try (var input = getClass().getResourceAsStream("/messages/messages" + suffix + ".properties")) {
                assertThat(input).isNotNull();
                messages.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
            int checked = 0;
            for (var resource : resources) {
                String name = resource.getFilename().replace(".class", "");
                Class<?> type = Class.forName("kkdugi.core.enums." + name, false, CodeEnums.class.getClassLoader());
                if (!type.isEnum() || !CodeEnums.class.isAssignableFrom(type)) {
                    continue;
                }
                for (CodeEnums value : type.asSubclass(CodeEnums.class).getEnumConstants()) {
                    assertThat(messages.getProperty(value.getLabelCode())).as(suffix + ": " + value.getLabelCode()).isNotBlank();
                    checked++;
                }
            }
            assertThat(checked).isGreaterThanOrEqualTo(14);
        }
    }
}
