package kkdugi.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class AesGcmPropertyDecryptorTest {

    @Test
    void decryptsAPayloadEncryptedWithTheSamePassphrase() throws Exception {
        String passphrase = "correct-horse-battery-staple";
        String plaintext = "s3cr3t-db-password";
        String payload = encrypt(passphrase, plaintext);

        String decrypted = AesGcmPropertyDecryptor.decrypt(passphrase, payload);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void throwsWhenThePassphraseIsWrong() throws Exception {
        String payload = encrypt("correct-horse-battery-staple", "s3cr3t-db-password");

        assertThatThrownBy(() -> AesGcmPropertyDecryptor.decrypt("wrong-passphrase", payload))
                .isInstanceOf(PropertyDecryptionException.class);
    }

    @Test
    void throwsWhenThePayloadIsNotValidBase64() {
        assertThatThrownBy(() -> AesGcmPropertyDecryptor.decrypt("any-passphrase", "not-base64!!"))
                .isInstanceOf(PropertyDecryptionException.class);
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
