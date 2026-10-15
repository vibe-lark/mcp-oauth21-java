package com.example.mcpoauthdemo.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

@Component
public class CryptoSupport {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Pattern PKCE_VALUE = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey encryptionKey;

    public CryptoSupport() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, secureRandom);
            this.encryptionKey = generator.generateKey();
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot initialize token encryption", ex);
        }
    }

    public String randomUrlSafe(int bytes) {
        byte[] value = new byte[bytes];
        secureRandom.nextBytes(value);
        return URL_ENCODER.encodeToString(value);
    }

    public String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String s256(String verifier) {
        return sha256(verifier);
    }

    public boolean isValidPkceValue(String value) {
        return value != null && PKCE_VALUE.matcher(value).matches();
    }

    public boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return URL_ENCODER.encodeToString(packed);
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot encrypt credential", ex);
        }
    }

    public String decrypt(String encrypted) {
        try {
            byte[] packed = URL_DECODER.decode(encrypted);
            if (packed.length < 29) {
                throw new IllegalArgumentException("Invalid encrypted value");
            }
            byte[] iv = new byte[12];
            byte[] ciphertext = new byte[packed.length - iv.length];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            System.arraycopy(packed, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        }
        catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Cannot decrypt credential", ex);
        }
    }
}
