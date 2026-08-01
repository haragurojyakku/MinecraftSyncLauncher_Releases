package dev.haraguro.modserverplaymanager.launcher.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password-based encryption for exported connection settings files (see LauncherWindow's
 * exportConnectionFileEncryptedButton / LauncherApp's runExportConnectionFileEncrypted) — AES-256-GCM
 * with a key derived from the passphrase via PBKDF2, so the file itself is unreadable without the
 * password the exporter shares with whoever they hand it to (out of band — a chat message, verbally,
 * etc.), not embedded in the file. A fresh random salt+IV is generated per export.
 */
public final class ConnectionFileCrypto {

    private static final Gson GSON = new Gson();
    private static final String KEY_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int IV_LENGTH_BYTES = 12;

    private ConnectionFileCrypto() {
    }

    /** Wire format written to/read from the exported file — see the class javadoc. */
    private record Envelope(boolean encrypted, String salt, String iv, String ciphertext) {
    }

    /** Encrypts {@code plaintext} (the connect-only ServerProfile JSON) into the on-disk envelope JSON. */
    public static String encrypt(String plaintext, char[] password) throws GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        random.nextBytes(salt);
        byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(iv);

        SecretKeySpec key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        Envelope envelope = new Envelope(true, base64(salt), base64(iv), base64(ciphertext));
        return GSON.toJson(envelope);
    }

    /**
     * Decrypts an envelope produced by {@link #encrypt}. Throws GeneralSecurityException (wrong
     * password or corrupted file both fail the GCM authentication tag check the same way — there's
     * no way to tell them apart, so callers should show one generic "couldn't decrypt" message).
     */
    public static String decrypt(String fileContent, char[] password) throws GeneralSecurityException {
        Envelope envelope = GSON.fromJson(fileContent, Envelope.class);
        if (envelope == null || envelope.salt() == null || envelope.iv() == null || envelope.ciphertext() == null) {
            throw new GeneralSecurityException("Not a valid encrypted connection settings file");
        }
        SecretKeySpec key = deriveKey(password, Base64.getDecoder().decode(envelope.salt()));
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.getDecoder().decode(envelope.iv())));
        byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertext()));
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    /** Cheap peek at a file's content to decide whether {@link #decrypt} (vs. plain JSON parsing) applies. */
    public static boolean looksEncrypted(String fileContent) {
        try {
            Envelope envelope = GSON.fromJson(fileContent, Envelope.class);
            return envelope != null && envelope.encrypted() && envelope.ciphertext() != null;
        } catch (JsonParseException e) {
            return false;
        }
    }

    private static SecretKeySpec deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        byte[] keyBytes = SecretKeyFactory.getInstance(KEY_ALGORITHM).generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
