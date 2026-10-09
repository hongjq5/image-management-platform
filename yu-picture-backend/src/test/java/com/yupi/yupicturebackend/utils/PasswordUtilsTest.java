package com.yupi.yupicturebackend.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordUtilsTest {
    // Independently generated with .NET Rfc2898DeriveBytes.Pbkdf2 and MD5.HashData.
    private static final String CURRENT = "pbkdf2-sha256$v1$600000$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=";
    private static final String OLD_WORK_FACTOR = "pbkdf2-sha256$v1$100000$MDEyMzQ1Njc4OWFiY2RlZg==$KRGXGQ7A4ThQ5pPfzXHF4MEowJXv0qjzNUN+Mlmqkno=";
    private static final String LEGACY = "cfad555007af218634cd5a74fdfc1fe8";

    @Test
    void hashesTheSamePasswordWithIndependentRandomSalts() {
        String first = PasswordUtils.hash("Password123!");
        String second = PasswordUtils.hash("Password123!");

        assertNotEquals(first, second);
        assertFalse(first.contains("Password123!"));
        String[] parts = first.split("\\$");
        assertEquals(5, parts.length);
        assertEquals("pbkdf2-sha256", parts[0]);
        assertEquals("v1", parts[1]);
        assertEquals("600000", parts[2]);
        assertEquals(16, Base64.getDecoder().decode(parts[3]).length);
        assertEquals(32, Base64.getDecoder().decode(parts[4]).length);
        assertNotEquals(parts[3], second.split("\\$")[3]);
        assertTrue(PasswordUtils.matches("Password123!", first));
        assertTrue(PasswordUtils.matches("Password123!", second));
        assertFalse(PasswordUtils.needsRehash(first));
    }

    @Test
    void verifiesAnIndependentCurrentHashAndRejectsTheWrongPassword() {
        assertTrue(PasswordUtils.matches("Password123!", CURRENT));
        assertFalse(PasswordUtils.matches("Password123?", CURRENT));
        assertFalse(PasswordUtils.needsRehash(CURRENT));
    }

    @Test
    void recognizesLegacySaltedMd5ForUpgradeOnlyAfterSuccessfulVerification() {
        assertTrue(PasswordUtils.matches("Password123!", LEGACY));
        assertFalse(PasswordUtils.matches("Password123?", LEGACY));
        assertTrue(PasswordUtils.needsRehash(LEGACY));
    }

    @Test
    void verifiesAndUpgradesAnOlderPbkdf2WorkFactor() {
        assertTrue(PasswordUtils.matches("Password123!", OLD_WORK_FACTOR));
        assertTrue(PasswordUtils.needsRehash(OLD_WORK_FACTOR));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "plain text password",
            "not-a-32-character-hexadecimal-md5",
            "pbkdf2-sha256$v2$600000$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha1$v1$600000$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$0$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$-1$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$+600000$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$1200001$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$2147483647$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$2147483648$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$600000$@@@$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$600000$MDEyMzQ1Njc4OWFiY2RlZg==$@@@",
            "pbkdf2-sha256$v1$600000$c2FsdA==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$600000$MDEyMzQ1Njc4OWFiY2RlZg==$c2hvcnQ=",
            "pbkdf2-sha256$v1$600000$MDEyMzQ1Njc4OWFiY2RlZg$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=",
            "pbkdf2-sha256$v1$600000$MDEyMzQ1Njc4OWFiY2RlZg==$d5DtB7HT8Kx0/BMg8S6z2wVS8ivrPBx+ozuGoXSVwj8=$extra"
    })
    void rejectsMalformedHashesWithoutThrowing(String encoded) {
        assertFalse(PasswordUtils.matches("Password123!", encoded));
        assertFalse(PasswordUtils.needsRehash(encoded));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingPasswords(String raw) {
        assertFalse(PasswordUtils.matches(raw, CURRENT));
        assertFalse(PasswordUtils.matches(raw, LEGACY));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtils.hash(raw));
    }

    @Test
    void boundsPasswordAndStoredHashLengthsBeforeDoingExpensiveWork() {
        String oversizedPassword = "a".repeat(1025);
        assertFalse(PasswordUtils.matches(oversizedPassword, CURRENT));
        assertFalse(PasswordUtils.matches(oversizedPassword, LEGACY));
        assertFalse(PasswordUtils.matches("Password123!", "a".repeat(257)));
        assertFalse(PasswordUtils.needsRehash("a".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtils.hash(oversizedPassword));
    }

    @Test
    void preservesUnicodeAndWhitespaceInNewPasswords() {
        String hash = PasswordUtils.hash(" 猫咪Password123! ");
        assertTrue(PasswordUtils.matches(" 猫咪Password123! ", hash));
        assertFalse(PasswordUtils.matches("猫咪Password123!", hash));
    }

    @Test
    void acceptsTheMaximumPasswordLengthWithoutTruncation() {
        String raw = "a".repeat(1024);
        String hash = PasswordUtils.hash(raw);
        assertTrue(PasswordUtils.matches(raw, hash));
        assertFalse(PasswordUtils.matches(raw.substring(1), hash));
    }

    @Test
    void doesNotRequestADowngradeForStrongerSupportedParameters() {
        assertFalse(PasswordUtils.needsRehash(CURRENT.replace("$600000$", "$1200000$")));
    }
}
