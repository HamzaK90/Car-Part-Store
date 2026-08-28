package com.carparts.web;

import com.carparts.repository.ReportingRepository.UserIdentity;
import com.carparts.security.JwtService;
import com.carparts.service.AuthService;
import com.carparts.service.AuthenticationFailedException;
import com.carparts.web.dto.Requests.LoginRequest;
import com.carparts.web.dto.Responses.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logging in.
 *
 * <p>The only endpoint reachable without a token, for the obvious reason. There is no logout: a
 * bearer token is not a session and the server holds nothing to end. A client forgets the token;
 * to revoke access before it expires, disable the account — {@code app_user.enabled} exists for
 * that, and keeps the person's history rather than deleting them.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Proving who you are")
public class AuthController {

    private final AuthService auth;
    private final JwtService jwt;

    public AuthController(AuthService auth, JwtService jwt) {
        this.auth = auth;
        this.jwt = jwt;
    }

    /**
     * Exchanges a username and password for a bearer token.
     *
     * <p>A wrong password, an unknown username and a disabled account are all the same 401 with
     * the same wording. Telling them apart would make this endpoint a way of discovering which
     * accounts exist, and "that account is disabled" confirms a real one outright.
     */
    // The one endpoint with no security requirement, so Swagger does not imply
    // that logging in needs a token you can only get by logging in.
    @SecurityRequirements
    @PostMapping("/login")
    @Operation(summary = "Log in",
               description = "Returns a bearer token. Send it as: Authorization: Bearer <token>")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        UserIdentity who = auth.login(request.username(), request.password())
                .orElseThrow(AuthenticationFailedException::new);

        return LoginResponse.of(jwt.issue(who), jwt.expirySeconds(), who);
    }
}
