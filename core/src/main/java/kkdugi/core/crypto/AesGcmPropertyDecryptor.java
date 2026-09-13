package kkdugi.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmPropertyDecryptor {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KEY_DIGEST_ALGORITHM = "SHA-256";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private AesGcmPropertyDecryptor() {}

    public static String decrypt(String passphrase, String payload) {
        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length <= GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Payload too short to contain an IV and ciphertext");
            }

            byte[] iv = Arrays.copyOfRange(decoded, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(decoded, GCM_IV_LENGTH_BYTES, decoded.length);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new PropertyDecryptionException("Failed to decrypt property value", e);
        }
    }

    private static SecretKeySpec deriveKey(String passphrase) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(KEY_DIGEST_ALGORITHM);
        return new SecretKeySpec(digest.digest(passphrase.getBytes(StandardCharsets.UTF_8)), KEY_ALGORITHM);
    }
}
