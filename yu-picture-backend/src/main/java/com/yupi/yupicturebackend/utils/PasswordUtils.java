package com.yupi.yupicturebackend.utils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** Versioned password hashes with read-only compatibility for the previous salted MD5 format. */
public final class PasswordUtils {
    private static final String PREFIX = "pbkdf2-sha256$v1$";
    private static final int ITERATIONS = 600_000;
    private static final int MIN_SUPPORTED_ITERATIONS = 100_000;
    private static final int MAX_SUPPORTED_ITERATIONS = 1_200_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int MAX_PASSWORD_LENGTH = 1024;
    private static final int MAX_ENCODED_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtils() {
    }

    /** Creates a fresh random salt; never use the result to query for a password match. */
    public static String hash(String raw) {
        if (!validPassword(raw)) {
            throw new IllegalArgumentException("Password must contain 1 to 1024 characters");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(raw, salt, ITERATIONS);
        try {
            return PREFIX + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(derived);
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    /** Malformed, unsupported and oversized inputs fail closed without running a KDF. */
    public static boolean matches(String raw, String encoded) {
        if (!validPassword(raw) || !validEncodedLength(encoded)) {
            return false;
        }
        if (isLegacyHash(encoded)) {
            return matchesLegacy(raw, encoded);
        }
        EncodedHash stored = parse(encoded);
        if (stored == null) {
            return false;
        }
        byte[] actual = derive(raw, stored.salt, stored.iterations);
        try {
            return MessageDigest.isEqual(stored.hash, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    /** Call only after matches succeeds. Stronger supported hashes are not downgraded. */
    public static boolean needsRehash(String encoded) {
        if (isLegacyHash(encoded)) {
            return true;
        }
        EncodedHash stored = parse(encoded);
        return stored != null && stored.iterations < ITERATIONS;
    }

    private static boolean validPassword(String raw) {
        return raw != null && !raw.isEmpty() && raw.length() <= MAX_PASSWORD_LENGTH;
    }

    private static boolean validEncodedLength(String encoded) {
        return encoded != null && !encoded.isEmpty() && encoded.length() <= MAX_ENCODED_LENGTH;
    }

    private static boolean isLegacyHash(String encoded) {
        return encoded != null && encoded.length() == 32 && encoded.matches("[0-9a-fA-F]{32}");
    }

    private static boolean matchesLegacy(String raw, String encoded) {
        // The previous application called getBytes() without a charset: retain that exact behavior.
        byte[] input = ("yupi" + raw).getBytes(Charset.defaultCharset());
        byte[] actual = null;
        try {
            actual = MessageDigest.getInstance("MD5").digest(input);
            return MessageDigest.isEqual(HexFormat.of().parseHex(encoded), actual);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password verification is unavailable", e);
        } finally {
            Arrays.fill(input, (byte) 0);
            if (actual != null) {
                Arrays.fill(actual, (byte) 0);
            }
        }
    }

    private static EncodedHash parse(String encoded) {
        if (!validEncodedLength(encoded) || !encoded.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 5 || !parts[2].matches("[1-9][0-9]{0,6}")) {
            return null;
        }
        try {
            int iterations = Integer.parseInt(parts[2]);
            if (iterations < MIN_SUPPORTED_ITERATIONS || iterations > MAX_SUPPORTED_ITERATIONS) {
                return null;
            }
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] hash = Base64.getDecoder().decode(parts[4]);
            if (salt.length != SALT_BYTES || hash.length != HASH_BYTES
                    || !Base64.getEncoder().encodeToString(salt).equals(parts[3])
                    || !Base64.getEncoder().encodeToString(hash).equals(parts[4])) {
                return null;
            }
            return new EncodedHash(iterations, salt, hash);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] derive(String raw, byte[] salt, int iterations) {
        char[] password = raw.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is unavailable", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(password, '\0');
        }
    }

    private static final class EncodedHash {
        private final int iterations;
        private final byte[] salt;
        private final byte[] hash;

        private EncodedHash(int iterations, byte[] salt, byte[] hash) {
            this.iterations = iterations;
            this.salt = salt;
            this.hash = hash;
        }
    }
}
