package com.carparts.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guard that stops the application rather than weakening it.
 *
 * <p>These branches are unreachable from a running application by design — the context does not
 * come up when any of them trips, so a test that boots one cannot observe them. The compact
 * constructor is the thing under test, and calling it directly is the only way to see it work.
 *
 * <p>What makes it worth testing is what it protects against: every failure here is silent if the
 * check is removed. A missing secret becomes a default one, a short secret becomes a signing
 * error on the first login rather than at start-up, and a zero expiry becomes a token that is
 * already expired when it is issued. None of those announce themselves.
 */
@DisplayName("the JWT secret guard")
class JwtPropertiesTest {

    private static final String GOOD =
            "a-secret-long-enough-to-sign-with-and-then-some-more";

    @Test
    @DisplayName("a usable secret is accepted")
    void aGoodSecretIsAccepted() {
        assertThatCode(() -> new JwtProperties(GOOD, 60)).doesNotThrowAnyException();

        assertThat(GOOD.getBytes(StandardCharsets.UTF_8).length)
                .as("the fixture has to actually clear the bar it is testing")
                .isGreaterThanOrEqualTo(JwtProperties.MIN_SECRET_BYTES);
    }

    @Test
    @DisplayName("no secret at all stops start-up and says which variable to set")
    void missingSecretStopsStartup() {
        for (String absent : new String[]{null, "", "   ", "\t\n"}) {
            assertThatThrownBy(() -> new JwtProperties(absent, 60))
                    .as("secret %s", absent == null ? "null" : "\"" + absent + "\"")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET")
                    .hasMessageContaining("no default");
        }
    }

    @Test
    @DisplayName("an unsubstituted placeholder is treated as missing, not as a secret")
    void literalPlaceholderIsNotASecret() {
        // What is actually in the property when JWT_SECRET is unset and Spring gives up: the
        // literal text. It is 13 characters, so the length check alone would catch it — but with
        // the wrong message, sending somebody off to lengthen a secret they never set.
        assertThatThrownBy(() -> new JwtProperties("${JWT_SECRET}", 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not set");

        assertThatThrownBy(() -> new JwtProperties("  ${JWT_SECRET}  ", 60))
                .as("trimmed before comparing, since a stray space would slip past")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not set");
    }

    @Test
    @DisplayName("a secret too short to sign with is refused at start-up, not at first login")
    void shortSecretIsRefused() {
        String tooShort = "x".repeat(JwtProperties.MIN_SECRET_BYTES - 1);

        // jjwt refuses to sign below 32 bytes, so this is not a weaker token — it is no token,
        // and every login is a 500. Better to never start.
        assertThatThrownBy(() -> new JwtProperties(tooShort, 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(JwtProperties.MIN_SECRET_BYTES))
                .hasMessageContaining("31 bytes");

        assertThatCode(() -> new JwtProperties("x".repeat(JwtProperties.MIN_SECRET_BYTES), 60))
                .as("and exactly the minimum is enough").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("length is counted in bytes, not characters")
    void lengthIsInBytes() {
        // 31 characters that are 62 bytes in UTF-8. Counting characters would refuse a secret
        // that is comfortably long enough, which is the harmless direction — but the same
        // confusion in reverse accepts a short one, and only one of these tests catches which.
        String multiByte = "é".repeat(31);
        assertThat(multiByte.length()).isLessThan(JwtProperties.MIN_SECRET_BYTES);
        assertThat(multiByte.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThanOrEqualTo(JwtProperties.MIN_SECRET_BYTES);

        assertThatCode(() -> new JwtProperties(multiByte, 60)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a token cannot be issued already expired")
    void expiryMustBePositive() {
        for (long minutes : new long[]{0, -1, Long.MIN_VALUE}) {
            assertThatThrownBy(() -> new JwtProperties(GOOD, minutes))
                    .as("expiry %d", minutes)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be positive");
        }
    }

    @Test
    @DisplayName("the secret is checked before the expiry")
    void secretIsCheckedFirst() {
        // Both wrong at once. The secret is the one worth reporting: an operator who has not set
        // JWT_SECRET has almost certainly not set the expiry either, and being sent to fix the
        // second one first wastes a restart.
        assertThatThrownBy(() -> new JwtProperties(null, 0))
                .hasMessageContaining("JWT_SECRET");
    }
}
