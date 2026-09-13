package kkdugi.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Injects framework-level default properties (e.g. {@code spring.jackson.default-property-inclusion})
 * at the lowest precedence, so any value an application defines for the same key always wins.
 */
public class KkdugiDefaultPropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "kkdugiDefaultProperties";

    private static final Map<String, Object> DEFAULT_PROPERTIES = buildDefaults();

    private final Map<String, Object> defaultProperties;

    public KkdugiDefaultPropertiesEnvironmentPostProcessor() {
        this(DEFAULT_PROPERTIES);
    }

    KkdugiDefaultPropertiesEnvironmentPostProcessor(Map<String, Object> defaultProperties) {
        this.defaultProperties = defaultProperties;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources propertySources = environment.getPropertySources();
        if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        propertySources.addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaultProperties));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static Map<String, Object> buildDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.jackson.default-property-inclusion", "non_null");
        return defaults;
    }
}
