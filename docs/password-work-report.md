# Password hashing migration helper

> Historical implementation and verification record from 2026-09-19. These results were not rerun as part of the 2026-10-09 publication sanitation.

`utils.PasswordUtils` adds a JDK-only password hashing format without new dependencies. It does not edit account records or the existing user service itself; the authorization integration owns registration, login, and successful-login upgrades.

## Public interface

```java
String encoded = PasswordUtils.hash(rawPassword);
boolean valid = PasswordUtils.matches(rawPassword, storedPassword);
boolean upgrade = valid && PasswordUtils.needsRehash(storedPassword);
```

New hashes use `PBKDF2WithHmacSHA256`, 600,000 iterations, a fresh 16-byte `SecureRandom` salt, and a 32-byte derived key. The encoded form is:

```text
pbkdf2-sha256$v1$600000$<standard padded Base64 salt>$<standard padded Base64 key>
```

The same password produces different stored strings. Login must query by account and then call `matches`; querying the database for a freshly computed hash will not work.

`matches` supports the new format and the exact previous `MD5(("yupi" + password).getBytes())` behavior. The old implementation used the JVM default charset, so legacy verification deliberately retains that charset. New PBKDF2 passwords support Unicode and preserve whitespace. Deployments with historical non-ASCII legacy passwords must retain their previous default charset until those passwords are upgraded or reset.

After successful verification, `needsRehash` returns true for legacy MD5 and supported PBKDF2 hashes below 600,000 iterations. It returns false for current or stronger supported parameters and for malformed or unsupported strings. The service should update to a fresh `hash(rawPassword)` only after successful verification, preferably using a conditional update against the old hash to avoid overwriting a concurrent password change.

## Input and resource limits

- Raw passwords must contain 1–1024 Java characters. The application's existing minimum-length and blank-password policy remains the service's responsibility. `hash` rejects invalid input with `IllegalArgumentException`; `matches` returns false.
- Stored values are limited to 256 characters. The parser requires the exact version, five fields, canonical padded Base64, a 16-byte salt, and a 32-byte key.
- Verification accepts 100,000–1,200,000 iterations. Out-of-range or malformed iteration counts are rejected before any expensive derivation, preventing untrusted encoded parameters from requesting unlimited CPU work.
- Password comparison uses `MessageDigest.isEqual` on fixed-length digest bytes. Temporary password character arrays and derived comparison bytes are cleared after use. No password or digest is logged.

Rate limiting and account-enumeration policy remain application concerns. The helper bounds each verification request but does not rate-limit login attempts.

## Validation

Tests use actual JDK cryptography without mocks or network access. Current PBKDF2, lower-work-factor PBKDF2, and legacy MD5 fixtures were independently generated with .NET cryptographic APIs. Coverage includes distinct random salts, expected format and sizes, correct and incorrect passwords, legacy upgrade detection, stronger-parameter preservation, Unicode/whitespace, the maximum supported password length, malformed fields, bad Base64, oversized values, and excessive iteration counts.

The initial executable test baseline ran 24 cases with eight expected assertion failures before implementation. The final targeted verification command is:

```text
mvn -q -Dtest=PasswordUtilsTest test
```

Using the installed JDK 17, this command completed successfully: 28 tests, zero failures, zero errors. The targeted tests took 1.731 seconds. `git diff --check` also passed for the owned files.
