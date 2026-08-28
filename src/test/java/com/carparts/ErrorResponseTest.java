package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a caller is told when something goes wrong.
 *
 * <p>Error paths are the part of an API that gets exercised least and read most: a client
 * integrating against it meets them constantly while learning the shape of the thing. Every
 * response here is RFC 7807, and the two properties a client can actually branch on — {@code
 * status} and {@code type} — have to be right, because {@code detail} is prose and will change.
 *
 * <p>The 500 case cannot be reached through any real endpoint, which is the point of having one.
 * It is driven through the advice directly in {@code ApiExceptionHandlerTest}.
 */
@DisplayName("error responses")
class ErrorResponseTest extends AbstractWebTest {

    private static final String BASE =
            "https://github.com/HamzaK90/Car-Part-Store/problems/";

    private JsonNode problem(int expectedStatus, String expectedType,
                             org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r)
            throws Exception {
        JsonNode p = body(r);
        assertThat(p.path("status").asInt()).as("status for %s", expectedType)
                .isEqualTo(expectedStatus);
        assertThat(p.path("type").asText()).isEqualTo(BASE + expectedType);
        assertThat(p.path("detail").asText()).as("something to read").isNotBlank();
        return p;
    }

    @Test
    @DisplayName("the wrong verb on a real path is a 405 naming what is supported")
    void wrongVerb() throws Exception {
        String admin = login("layla");

        // /api/auth/login exists but only for POST. Without the explicit handler this became a
        // 500 from the catch-all, which reads as "the server is broken" rather than "read the
        // documentation".
        JsonNode p = problem(405, "method-not-allowed", get("/api/auth/login", null));
        assertThat(p.path("detail").asText()).contains("GET").contains("POST");

        assertThat(problem(405, "method-not-allowed", put("/api/customers/1", admin, Map.of()))
                .path("detail").asText()).as("PUT is not how this API updates").contains("PUT");
    }

    @Test
    @DisplayName("a path that matches no route is a 404, not a 500")
    void noSuchRoute() throws Exception {
        problem(404, "not-found", get("/api/does-not-exist", login("layla")));
        problem(404, "not-found", get("/api/customers/1/nothing-here", login("layla")));
    }

    @Test
    @DisplayName("a body that is not JSON is a 400 that gives nothing away")
    void malformedBody() throws Exception {
        String admin = login("layla");

        for (String junk : new String[]{"{not json", "", "[]", "{\"name\": }"}) {
            JsonNode p = body(post("/api/customers", admin, junk));
            assertThat(p.path("status").asInt()).as("body %s", junk).isEqualTo(400);
        }

        // A string where a number belongs is the same class of failure, and the message must not
        // echo the value back — it goes into logs and error trackers verbatim.
        JsonNode p = problem(400, "malformed-request", post("/api/parts", admin,
                "{\"sku\":\"X\",\"name\":\"X\",\"price\":\"' OR 1=1 --\",\"weightKg\":1,"
                        + "\"reorderLevel\":0,\"supplierId\":1}"));
        assertThat(p.path("detail").asText()).doesNotContain("OR 1=1");
    }

    @Test
    @DisplayName("a field the request does not have is ignored, not refused")
    void unknownFieldsAreIgnored() throws Exception {
        String admin = login("layla");

        // Worth pinning rather than leaving to Jackson's default, because it cuts both ways: a
        // client can send a field this version does not know yet and still be served, but a
        // misspelled optional field is accepted and silently does nothing. The first is why the
        // default is what it is; the second is the price, and it should be a known price.
        JsonNode created = body(post("/api/customers", admin, Map.of(
                "name", "Unknown Fields " + tag(), "phoneNumber", "078" + tag(),
                "loyaltyPoints", 500, "vip", true)));

        assertThat(created.path("id").asLong()).isPositive();
        assertThat(created.has("loyaltyPoints")).as("and it is not echoed back").isFalse();
    }

    @Test
    @DisplayName("an id that is not a number is a 400 naming the parameter")
    void badPathVariable() throws Exception {
        String admin = login("layla");
        JsonNode p = problem(400, "invalid-parameter", get("/api/customers/abc", admin));
        assertThat(p.path("detail").asText()).contains("id");

        problem(400, "invalid-parameter", get("/api/parts?year=nineteen", admin));
        problem(400, "invalid-parameter", get("/api/departments?type=SHOP", admin));
    }

    @Test
    @DisplayName("a validation failure lists every bad field at once, not the first")
    void validationListsEveryField() throws Exception {
        String admin = login("layla");
        JsonNode p = problem(400, "validation-failed", post("/api/parts", admin, Map.of(
                "sku", "", "name", "", "price", -1, "weightKg", 0, "reorderLevel", -3,
                "supplierId", 1)));

        JsonNode errors = p.path("errors");
        // One round trip should be enough to fix the form. Reporting them one at a time turns a
        // single mistake into five requests.
        assertThat(errors.properties().size()).isGreaterThanOrEqualTo(4);
        for (String field : List.of("sku", "name", "price", "weightKg")) {
            assertThat(errors.has(field)).as("field %s", field).isTrue();
            assertThat(errors.path(field).asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("a refusal by a database rule is a 409 that names the rule")
    void constraintViolationsAreIdentified() throws Exception {
        String admin = login("layla");
        String sku = "ER-" + tag();
        long supplier = body(post("/api/suppliers", admin,
                Map.of("name", "Error Vendor " + tag()))).path("id").asLong();
        Map<String, Object> part = Map.of("sku", sku, "name", "Error Part", "price", 5.00,
                "weightKg", 1.0, "reorderLevel", 0, "supplierId", supplier);
        body(post("/api/parts", admin, part));

        JsonNode p = problem(409, "constraint-violation", post("/api/parts", admin, part));
        assertThat(p.path("constraint").asText())
                .as("the machine-readable half; detail is prose").isEqualTo("uq_part_sku");
    }

    @Test
    @DisplayName("an unauthenticated call is a 401 and an unauthorised one a 403")
    void authenticationAndAuthorisationDiffer() throws Exception {
        problem(401, "unauthenticated", get("/api/customers", null));
        problem(401, "unauthenticated", get("/api/customers", "not.a.token"));

        // Different questions: "who are you" against "you, specifically, may not". Collapsing
        // them into one status makes a client retry a login that was never the problem.
        problem(403, "forbidden", post("/api/suppliers", login("omar"),
                Map.of("name", "Refused " + tag())));
    }

    @Test
    @DisplayName("a missing row is a 404 that says what was looked for")
    void notFoundNamesTheThing() throws Exception {
        String admin = login("layla");
        assertThat(problem(404, "not-found", get("/api/customers/999999", admin))
                .path("detail").asText()).containsIgnoringCase("customer");
        assertThat(problem(404, "not-found", get("/api/parts/999999", admin))
                .path("detail").asText()).containsIgnoringCase("part");
        assertThat(problem(404, "not-found", get("/api/departments/999999", admin))
                .path("detail").asText()).containsIgnoringCase("department");
    }
}
