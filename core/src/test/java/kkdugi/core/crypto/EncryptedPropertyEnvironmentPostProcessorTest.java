package kkdugi.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class EncryptedPropertyEnvironmentPostProcessorTest {

    private static final String PASSPHRASE = "correct-horse-battery-staple";

    @Test
    void decryptsAnEncWrappedValueUsingThePassphrase() throws Exception {
        String plaintext = "s3cr3t-db-password";
        ConfigurableEnvironment environment = environmentWithProperty("some.secret",
                "enc(" + encrypt(PASSPHRASE, plaintext) + ")");
        EncryptedPropertyEnvironmentPostProcessor postProcessor = postProcessorWithPassphrase(PASSPHRASE);

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("some.secret")).isEqualTo(plaintext);
    }

    @Test
    void fallsBackToTheStrippedValueWhenDecryptionFails() throws Exception {
        String payload = encrypt("a-different-passphrase", "s3cr3t-db-password");
        ConfigurableEnvironment environment = environmentWithProperty("some.secret", "enc(" + payload + ")");
        EncryptedPropertyEnvironmentPostProcessor postProcessor = postProcessorWithPassphrase(PASSPHRASE);

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("some.secret")).isEqualTo(payload);
    }

    @Test
    void fallsBackToTheStrippedValueWhenNoPassphraseIsConfigured() {
        ConfigurableEnvironment environment = environmentWithProperty("some.secret", "enc(plain-inner-value)");
        EncryptedPropertyEnvironmentPostProcessor postProcessor = postProcessorWithPassphrase(null);

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("some.secret")).isEqualTo("plain-inner-value");
    }

    @Test
    void leavesPlainValuesUntouched() {
        ConfigurableEnvironment environment = environmentWithProperty("some.other.property", "plain-value");
        EncryptedPropertyEnvironmentPostProcessor postProcessor = postProcessorWithPassphrase(PASSPHRASE);

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("some.other.property")).isEqualTo("plain-value");
        assertThat(environment.getPropertySources().contains(
                EncryptedPropertyEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    private static ConfigurableEnvironment environmentWithProperty(String name, String value) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(name, value)));
        return environment;
    }

    private static EncryptedPropertyEnvironmentPostProcessor postProcessorWithPassphrase(String passphrase) {
        return new EncryptedPropertyEnvironmentPostProcessor(() -> passphrase);
    }

    private static String encrypt(String passphrase, String plaintext) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        SecretKeySpec key = new SecretKeySpec(digest.digest(passphrase.getBytes(StandardCharsets.UTF_8)), "AES");

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }
}
