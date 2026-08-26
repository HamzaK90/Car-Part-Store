package com.carparts.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ⚠ TEMPORARY — every endpoint is open. Replaced by the real chain in step 7.
 *
 * <p>Why this exists at all: {@code spring-boot-starter-security} is on the classpath, and with
 * no {@code SecurityFilterChain} defined Spring applies its defaults — HTTP Basic over every
 * path with a password printed to the console at start-up, plus CSRF protection.
 *
 * <p>That combination makes the API unusable rather than merely locked. {@code CsrfFilter} runs
 * <em>before</em> {@code BasicAuthenticationFilter}, so a POST without a CSRF token is refused
 * while the request is still anonymous, and the failure surfaces as 401 rather than the 403 the
 * situation actually describes. Reads work, writes cannot, and the reason is invisible from the
 * response.
 *
 * <p>Rather than leave the REST layer unreachable, this permits everything and turns off the two
 * pieces a token API does not want:
 *
 * <ul>
 *   <li><b>CSRF disabled</b> — it defends browser form posts that ride on an ambient session
 *       cookie. A client sending a bearer token is not vulnerable to it, and step 7 makes this
 *       API exactly that.
 *   <li><b>Stateless sessions</b> — no {@code JSESSIONID}, matching where step 7 is heading.
 * </ul>
 *
 * <p><b>Delete this file in step 7.</b> The real chain adds {@code JwtAuthFilter}, BCrypt at
 * cost 12, and {@code @PreAuthorize} so writes and {@code /api/employees} require ADMIN.
 */
@Configuration
public class InterimSecurityConfig {

    @Bean
    public SecurityFilterChain interimChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Swagger and the OpenAPI document, so the API can be read.
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                            .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}
