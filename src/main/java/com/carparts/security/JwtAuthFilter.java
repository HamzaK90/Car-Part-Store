package com.carparts.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a {@code Bearer} header into an authenticated request.
 *
 * <p>It never rejects anything. A missing or unreadable token simply leaves the context empty,
 * and the filter chain's own rules then decide whether that matters — which is what makes the
 * permitted endpoints (login, swagger) work through the same filter as everything else. Refusing
 * here would mean this filter had to know which paths are public, duplicating the chain.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            jwt.parse(header.substring(PREFIX.length()).trim())
                    .ifPresent(user -> authenticate(user, request));
        }
        chain.doFilter(request, response);
    }

    /**
     * Puts the caller in the security context.
     *
     * <p>The principal is the {@link AuthenticatedUser} itself rather than a username, so a
     * controller or a {@code @PreAuthorize} expression can read the employee, department and
     * manager flag without going back to the database.
     *
     * <p>The role becomes a {@code ROLE_}-prefixed authority because that is the prefix
     * {@code hasRole()} adds when it looks one up; storing it without would make every role check
     * silently fail to match.
     */
    private void authenticate(AuthenticatedUser user, HttpServletRequest request) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
