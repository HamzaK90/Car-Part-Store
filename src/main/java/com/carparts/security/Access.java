package com.carparts.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The authorisation rules that a role cannot express.
 *
 * <p>Referenced from {@code @PreAuthorize} as {@code @access}. Most endpoints need nothing more
 * than {@code hasRole('ADMIN')} and say so inline; this exists for the one rule that is about
 * <em>which</em> row is being touched rather than who the caller is globally.
 */
@Component("access")
public class Access {

    /**
     * What a refused caller is told, wherever the refusal is decided.
     *
     * <p>Lives here because two layers produce it — the filter chain for a rule in
     * {@code authorizeHttpRequests}, and {@code ApiExceptionHandler} for a {@code @PreAuthorize}
     * denial thrown during handler invocation. They have to say the same thing or the wording
     * itself reveals which layer refused, and that is an implementation detail worth only to
     * somebody mapping the API's defences. One constant is what stops the two drifting.
     */
    public static final String REFUSED = "your account may not do that";

    /**
     * Whether the caller manages this particular department.
     *
     * <p>Managing is per-department, which is why there is no {@code MANAGER} role to check:
     * {@code department.manager_id} records who manages what, and a second copy on the account
     * would drift the moment somebody is promoted. {@code v_user_identity} derives the flag at
     * login and it travels in the token, so this is a comparison rather than a query.
     *
     * <p>Both halves matter. The flag alone would let any manager edit any department; the
     * department alone would let every employee edit the one they work in.
     */
    public boolean managesDepartment(Long departmentId) {
        return departmentId != null
                && currentUser().map(u -> u.manages(departmentId)).orElse(false);
    }

    /**
     * The caller, when the request carries a token this application issued.
     *
     * <p>Empty for an anonymous request. Today the chain requires authentication before any
     * annotated method is reached, so this cannot return empty in practice — but a SpEL
     * expression that assumes a principal throws rather than denying, which would turn a
     * permitted path added later into a 500 instead of a 403. Returning empty degrades to
     * "not a manager", which is the safe answer.
     */
    private Optional<AuthenticatedUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }
}
