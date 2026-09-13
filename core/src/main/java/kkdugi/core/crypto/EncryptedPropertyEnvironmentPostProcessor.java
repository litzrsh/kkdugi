package kkdugi.core.crypto;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

/**
 * Resolves property values written as {@code enc(...)} by decrypting the wrapped payload
 * with {@link AesGcmPropertyDecryptor}. The passphrase is read from the
 * {@value #PASSPHRASE_ENV_VAR} environment variable. If the passphrase is missing or
 * decryption fails for any reason, the value falls back to whatever was inside the
 * {@code enc(...)} wrapper.
 */
public class EncryptedPropertyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "kkdugiDecryptedProperties";
    static final String PASSPHRASE_ENV_VAR = "KKDUGI_PROPERTY_ENCRYPTION_KEY";

    private static final Pattern ENCRYPTED_VALUE_PATTERN = Pattern.compile("^enc\\((.*)\\)$", Pattern.DOTALL);

    private final Supplier<String> passphraseSupplier;

    public EncryptedPropertyEnvironmentPostProcessor() {
        this(() -> System.getenv(PASSPHRASE_ENV_VAR));
    }

    EncryptedPropertyEnvironmentPostProcessor(Supplier<String> passphraseSupplier) {
        this.passphraseSupplier = passphraseSupplier;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources propertySources = environment.getPropertySources();
        Map<String, Object> resolved = resolveEncryptedProperties(environment, propertySources);
        if (resolved.isEmpty()) {
            return;
        }

        propertySources.remove(PROPERTY_SOURCE_NAME);
        propertySources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, resolved));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private Map<String, Object> resolveEncryptedProperties(ConfigurableEnvironment environment, MutablePropertySources propertySources) {
        String passphrase = passphraseSupplier.get();

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String name : collectPropertyNames(propertySources)) {
            String value = environment.getProperty(name);
            if (value == null) {
                continue;
            }

            Matcher matcher = ENCRYPTED_VALUE_PATTERN.matcher(value);
            if (matcher.matches()) {
                resolved.put(name, resolveValue(passphrase, matcher.group(1)));
            }
        }
        return resolved;
    }

    private String resolveValue(String passphrase, String payload) {
        if (!StringUtils.hasText(passphrase)) {
            return payload;
        }
        try {
            return AesGcmPropertyDecryptor.decrypt(passphrase, payload);
        } catch (PropertyDecryptionException ex) {
            return payload;
        }
    }

    private Set<String> collectPropertyNames(MutablePropertySources propertySources) {
        Set<String> names = new LinkedHashSet<>();
        for (PropertySource<?> propertySource : propertySources) {
            if (propertySource instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
