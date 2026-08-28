package com.carparts.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The two things about the error handler that no endpoint can demonstrate.
 *
 * <p>The catch-all exists for failures nobody predicted, so by construction there is no request
 * that produces one — every path that could has a handler of its own. Driving the advice
 * directly is the only way to see what it does, and what it does matters: it decides how much of
 * an internal failure leaks to whoever tripped it.
 *
 * <p>The other is the constraint mapping. Every message in it is keyed on a name that lives in a
 * migration, and nothing links the two — a rename orphans the message silently.
 */
@DisplayName("the error handler itself")
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Nested
    @DisplayName("the catch-all")
    class CatchAll {

        @Test
        @DisplayName("an unforeseen failure is a 500 that reveals nothing")
        void unexpectedRevealsNothing() {
            // A real one carries a query, a connection string or part of a row. Whoever needs it
            // has the log; the caller gets a sentence and a status.
            Exception secret = new IllegalStateException(
                    "FATAL: password authentication failed for user \"carparts\" at "
                            + "jdbc:postgresql://prod-db.internal:5432/carparts");

            ProblemDetail problem = handler.onUnexpected(secret);

            assertThat(problem.getStatus()).isEqualTo(500);
            assertThat(problem.getDetail())
                    .isEqualTo("something went wrong handling this request");
            assertThat(problem.getDetail()).doesNotContain("password", "carparts", "5432");
            assertThat(problem.getType().toString()).endsWith("/internal-error");
        }

        @Test
        @DisplayName("a null message does not become the response")
        void nullMessageIsSafe() {
            ProblemDetail problem = handler.onUnexpected(new RuntimeException());
            assertThat(problem.getDetail()).isNotBlank().doesNotContain("null");
        }
    }

    @Nested
    @DisplayName("naming the constraint")
    class NamingTheConstraint {

        private DataIntegrityViolationException wrapping(Throwable cause) {
            return new DataIntegrityViolationException("could not execute statement", cause);
        }

        @Test
        @DisplayName("the name is dug out of the chain, however deep it is wrapped")
        void findsTheNameThroughWrapping() {
            // Spring wraps Hibernate wraps the driver. The name is never on the exception that
            // arrives at this layer, so the walk down getCause() is the whole mechanism.
            Throwable hibernate = new ConstraintViolationException(
                    "unique violation", new java.sql.SQLException("duplicate key"), "uq_part_sku");
            ProblemDetail problem = handler.onConstraintViolation(
                    wrapping(new RuntimeException("outer", hibernate)));

            assertThat(problem.getStatus()).isEqualTo(409);
            assertThat(problem.getProperties().get("constraint")).isEqualTo("uq_part_sku");
            assertThat(problem.getDetail()).isEqualTo("that SKU is already in the catalogue");
        }

        @Test
        @DisplayName("a name only present in the message text is still found")
        void findsTheNameInTheMessage() {
            // A trigger raises with the name in CONSTRAINT, but not every driver surfaces it
            // there. Reading the message is the fallback, and it has to work.
            ProblemDetail problem = handler.onConstraintViolation(wrapping(new RuntimeException(
                    "ERROR: duplicate key value violates unique constraint \"uq_customer_phone\"")));

            assertThat(problem.getProperties().get("constraint")).isEqualTo("uq_customer_phone");
            assertThat(problem.getDetail()).isEqualTo("that phone number is already registered");
        }

        @Test
        @DisplayName("an unmapped rule is still a 409, with an honest message and no name")
        void unmappedRuleFallsBack() {
            ProblemDetail problem = handler.onConstraintViolation(
                    wrapping(new RuntimeException("some rule nobody wrote a message for")));

            assertThat(problem.getStatus()).as("guessing a 400 would be worse").isEqualTo(409);
            assertThat(problem.getDetail())
                    .isEqualTo("the request conflicts with the current state of the data");
            // getProperties() is null until something sets one, so "no constraint property" is
            // the absence of the map as much as the absence of the key.
            assertThat(problem.getProperties() == null
                    || !problem.getProperties().containsKey("constraint"))
                    .as("no name rather than a wrong one").isTrue();
        }

        @Test
        @DisplayName("an exception with no cause and no message does not loop or throw")
        void emptyChain() {
            ProblemDetail problem = handler.onConstraintViolation(
                    new DataIntegrityViolationException(""));
            assertThat(problem.getStatus()).isEqualTo(409);
        }
    }

    @Nested
    @DisplayName("the wrong verb")
    class WrongVerb {

        @Test
        @DisplayName("the supported verbs are listed when the framework knows them")
        void listsSupportedMethods() {
            ProblemDetail problem = handler.onWrongMethod(
                    new org.springframework.web.HttpRequestMethodNotSupportedException(
                            "DELETE", List.of("GET", "PATCH")));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
            assertThat(problem.getDetail()).contains("DELETE").contains("GET").contains("PATCH");
        }

        @Test
        @DisplayName("and something readable is said when it does not")
        void survivesUnknownSupportedMethods() {
            // getSupportedMethods() is nullable, and the obvious String.join over it is a
            // NullPointerException inside the handler — a 500 raised while reporting a 405.
            ProblemDetail problem = handler.onWrongMethod(
                    new org.springframework.web.HttpRequestMethodNotSupportedException("TRACE"));

            assertThat(problem.getStatus()).isEqualTo(405);
            assertThat(problem.getDetail()).contains("TRACE").contains("another verb");
        }
    }

}
