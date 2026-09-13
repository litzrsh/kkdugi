package kkdugi.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class KkdugiDefaultPropertiesEnvironmentPostProcessorTest {

    private final KkdugiDefaultPropertiesEnvironmentPostProcessor postProcessor =
            new KkdugiDefaultPropertiesEnvironmentPostProcessor();

    @Test
    void injectsTheDefaultWhenTheApplicationDoesNotSetIt() {
        ConfigurableEnvironment environment = new StandardEnvironment();

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.jackson.default-property-inclusion")).isEqualTo("non_null");
    }

    @Test
    void anApplicationDefinedValueTakesPrecedenceOverTheDefault() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application",
                Map.of("spring.jackson.default-property-inclusion", "always")));

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.jackson.default-property-inclusion")).isEqualTo("always");
    }

    @Test
    void isSafeToRunMoreThanOnce() {
        ConfigurableEnvironment environment = new StandardEnvironment();

        postProcessor.postProcessEnvironment(environment, null);
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.jackson.default-property-inclusion")).isEqualTo("non_null");
    }
}
