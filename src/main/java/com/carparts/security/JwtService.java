package com.carparts.security;

import com.carparts.domain.UserRole;
import com.carparts.repository.ReportingRepository.UserIdentity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and reads the bearer tokens.
 *
 * <p>The claims carry everything an authorisation decision needs — the account, the person, the
 * role, the department and whether they manage it — so a request answers "who is this and what
 * may they do" without a database lookup. That is worth stating because it is also the cost:
 * a token reflects the identity <em>as it was at login</em>. Promote somebody and their existing
 * token still says they are not a manager until it expires. With {@code expiry-minutes} at an
 * hour that is the accepted trade; the alternative is reading {@code v_user_identity} on every
 * request, which is a query per call to catch a change that happens a few times a year.
 */
@Service
public class JwtService {

    /** Claim names. Short, because every one of them is sent on every request. */
    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_EMPLOYEE_ID = "eid";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_DEPARTMENT_ID = "did";
    private static final String CLAIM_MANAGER = "mgr";

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expiry = Duration.ofMinutes(properties.expiryMinutes());
    }

    /** A signed token for somebody who has just proved who they are. */
    public String issue(UserIdentity identity) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(identity.username())
                .claim(CLAIM_USER_ID, identity.userId())
                .claim(CLAIM_EMPLOYEE_ID, identity.employeeId())
                .claim(CLAIM_ROLE, identity.role().name())
                .claim(CLAIM_DEPARTMENT_ID, identity.departmentId())
                .claim(CLAIM_MANAGER, identity.isManager())
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    /** How long a freshly issued token lasts, so the login response can say. */
    public long expirySeconds() {
        return expiry.toSeconds();
    }

    /**
     * Reads a token, or returns empty if it is anything other than valid.
     *
     * <p>Every failure — expired, tampered, signed with another key, malformed, not a JWT at
     * all — collapses to the same empty result, and the filter turns that into one 401. A caller
     * has no business learning <em>which</em> way their token was unacceptable: the distinction
     * is useful only to somebody probing the signature.
     */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(new AuthenticatedUser(
                    claims.get(CLAIM_USER_ID, Number.class).longValue(),
                    numberOrNull(claims.get(CLAIM_EMPLOYEE_ID, Number.class)),
                    UserRole.valueOf(claims.get(CLAIM_ROLE, String.class)),
                    numberOrNull(claims.get(CLAIM_DEPARTMENT_ID, Number.class)),
                    Boolean.TRUE.equals(claims.get(CLAIM_MANAGER, Boolean.class))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // IllegalArgumentException covers an unknown role name, NullPointerException a
            // token missing a claim this build requires — both mean "not a token we issued".
            return Optional.empty();
        }
    }

    private static Long numberOrNull(Number value) {
        return value == null ? null : value.longValue();
    }
}
