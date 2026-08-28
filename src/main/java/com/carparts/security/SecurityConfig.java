package com.carparts.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The real filter chain. Replaces the interim one that opened every endpoint.
 *
 * <p>Stateless and token-based: no session, no {@code JSESSIONID}, and CSRF disabled because it
 * defends browser form posts riding on an ambient session cookie. A client sending a bearer
 * token is not exposed to that, and there is no cookie here for a third-party page to ride.
 *
 * <p>The chain decides only whether a request is <em>authenticated</em>. Which roles may do what
 * is {@code @PreAuthorize} on the endpoints themselves, because a URL pattern is a poor place to
 * express it: {@code /api/departments/{id}} is admin-only for one verb and open to that
 * department's manager for another, and a matcher cannot see the difference without repeating
 * the routing. Keeping it on the method also puts the rule where somebody reading the endpoint
 * will find it.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtFilter;
    private final ObjectMapper json;

    public SecurityConfig(JwtAuthFilter jwtFilter, ObjectMapper json) {
        this.jwtFilter = jwtFilter;
        this.json = json;
    }

    /**
     * BCrypt at cost 12, matching what {@code V6} seeded and what {@code app_user} enforces.
     *
     * <p>The cost is deliberately high enough to be slow: roughly a quarter of a second per
     * verification, which is nothing on one login and ruinous for somebody working through a
     * password list. {@code ck_app_user_password_hashed} refuses anything not shaped like a
     * BCrypt digest, so this encoder and that constraint have to agree.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Logging in cannot itself require being logged in.
                        .requestMatchers("/api/auth/login").permitAll()
                        // The API's own description stays readable, as it was before.
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                            .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthenticated())
                        .accessDeniedHandler(forbidden()))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * No token, or one that could not be read.
     *
     * <p>Written here rather than left to Spring's default empty 401 so that a failure inside the
     * filter chain looks like every other failure in this API. {@code ApiExceptionHandler} never
     * sees these: they happen before the request reaches a controller, so without this the API
     * would answer with a bare status for exactly the errors a client is most likely to hit.
     */
    private AuthenticationEntryPoint unauthenticated() {
        return (request, response, ex) -> write(response, HttpStatus.UNAUTHORIZED,
                "Unauthenticated", "this endpoint needs a bearer token", "unauthenticated");
    }

    /** A valid token belonging to somebody who may not do this. */
    private AccessDeniedHandler forbidden() {
        return (request, response, ex) -> write(response, HttpStatus.FORBIDDEN,
                "Forbidden", Access.REFUSED, "forbidden");
    }

    private void write(HttpServletResponse response, HttpStatus status,
                       String title, String detail, String type) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(
                "https://github.com/HamzaK90/Car-Part-Store/problems/" + type));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
