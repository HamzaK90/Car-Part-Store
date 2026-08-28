package com.carparts.security;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The signing secret and how long a token lasts.
 *
 * <p>Bound from {@code security.jwt.*}. Neither value has a default in code: the secret comes
 * from {@code JWT_SECRET} and an unset one fails start-up rather than falling back to something
 * guessable. That is the same stance {@code V6} takes with its password-hash placeholders — a
 * missing secret should stop the application, not quietly weaken it.
 *
 * @param secret the HMAC signing key, at least {@value #MIN_SECRET_BYTES} bytes
 * @param expiryMinutes how long an issued token stays valid
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(String secret, long expiryMinutes) {

    /**
     * The weakest key HMAC-SHA signing accepts here. jjwt picks the algorithm from the key it is
     * given — 32 bytes gets HS256, 48 gets HS384 — and refuses to sign with less than 32, so a
     * short secret is not a weaker token but no token at all. Checking it at start-up turns that
     * into a message naming the property, rather than a stack trace on the first login.
     */
    public static final int MIN_SECRET_BYTES = 32;

    /** What an unresolved {@code ${JWT_SECRET}} looks like once Spring gives up substituting it. */
    private static final String UNRESOLVED = "${JWT_SECRET}";

    public JwtProperties {
        if (secret == null || secret.isBlank() || UNRESOLVED.equals(secret.trim())) {
            throw new IllegalStateException(
                    "security.jwt.secret is not set — export JWT_SECRET. It has no default on "
                            + "purpose: a fallback secret is a published secret. Generate one "
                            + "with: openssl rand -base64 48");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret is " + bytes + " bytes; HMAC signing needs at least "
                            + MIN_SECRET_BYTES + ". Generate one with: "
                            + "openssl rand -base64 48");
        }
        if (expiryMinutes <= 0) {
            throw new IllegalStateException(
                    "security.jwt.expiry-minutes must be positive; a token that has already "
                            + "expired when issued would make every request a 401");
        }
    }
}
