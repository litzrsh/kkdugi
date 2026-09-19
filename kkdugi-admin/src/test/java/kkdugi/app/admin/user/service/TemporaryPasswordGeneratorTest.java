package kkdugi.app.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TemporaryPasswordGeneratorTest {

    private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();

    @Test
    void generate_hasFixedLength_andEveryCharacterClass() {
        for (int i = 0; i < 200; i++) {
            String password = generator.generate();
            assertThat(password).hasSize(12)
                    .matches("[A-Za-z0-9]+")
                    .matches(".*[A-Z].*")
                    .matches(".*[a-z].*")
                    .matches(".*[0-9].*");
        }
    }

    @Test
    void generate_excludesAmbiguousCharacters() {
        for (int i = 0; i < 200; i++) {
            assertThat(generator.generate()).doesNotContainPattern("[0O1lI]");
        }
    }

    @Test
    void generate_returnsDifferentValuesEachTime() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(50);
    }
}
