package com.carparts.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The description shown at {@code /swagger-ui}.
 *
 * <p>Written here rather than in {@code application.yml} so the text can say something useful
 * about how the API behaves. Most of what a caller needs to know is not the shape of a request
 * but which failures mean "try something else" and which mean "try again later".
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI carPartsOpenApi() {
        // Every endpoint but /api/auth/login needs a bearer token. Declaring the scheme is
        // what puts the Authorize button in Swagger and makes try-it-out work at all; without
        // it the browsable description became useless the moment the API was closed.
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste the token from POST /api/auth/login.");

        return new OpenAPI()
                .components(new Components().addSecuritySchemes("bearerAuth", bearer))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .info(new Info()
                .title("Car Parts Store API")
                .version("1.0.0")
                .description("""
                        REST API for a car parts retail business.

                        **Errors** are RFC 7807 `ProblemDetail` documents. Beyond the status \
                        code they carry a `detail` written for a person, and constraint \
                        violations name the rule that refused the write.

                        - `400` the request is malformed, or asks for something the domain \
                        does not allow
                        - `404` something the request referred to does not exist — including \
                        an id that exists but is of the wrong kind, such as a warehouse id \
                        given as an order's branch
                        - `409` the request is understood but conflicts with the current \
                        state. `POST /api/orders` returns this when the warehouse is short, \
                        with a `shortages` array listing every part and how many are missing, \
                        so the order can be corrected in one pass

                        **Placing an order** locks the stock rows it needs and holds them until \
                        the transaction commits, so two simultaneous orders for the last unit \
                        end with exactly one order and no oversell. Each line records the \
                        part's price at the moment of sale, so repricing a part later never \
                        moves the total of an order that already exists.
                        """)
                .license(new License().name("MIT")
                        .url("https://github.com/HamzaK90/Car-Part-Store/blob/main/LICENSE")));
    }
}
