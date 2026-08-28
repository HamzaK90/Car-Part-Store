package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may do what — acceptance criterion 1, and the rules step 7 added.
 *
 * <p>Seeded accounts: {@code layla} is ADMIN and employee 1, who manages department 1;
 * {@code omar} is EMPLOYEE at department 1 and manages nothing; {@code rana} is EMPLOYEE and
 * manages department 2; {@code admin} is ADMIN with nobody on the payroll behind it; and
 * {@code svc-reporting} is disabled.
 */
@DisplayName("authentication and authorisation")
class ApiSecurityTest extends AbstractWebTest {

    private JsonNode claims(String token) throws Exception {
        String payload = token.split("\\.")[1];
        return json.readTree(Base64.getUrlDecoder().decode(payload));
    }

    // ---------------------------------------------------------------- logging in

    @Test
    @DisplayName("login returns a JWT carrying who you are — criterion 1")
    void loginReturnsToken() throws Exception {
        JsonNode body = body(post("/api/auth/login", null,
                Map.of("username", "layla", "password", PASSWORD)));

        assertThat(body.path("token").asText()).contains(".");
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("role").asText()).isEqualTo("ADMIN");
        assertThat(body.path("isManager").asBoolean()).isTrue();

        JsonNode claims = claims(body.path("token").asText());
        assertThat(claims.path("uid").asLong()).isEqualTo(1);
        assertThat(claims.path("eid").asLong()).isEqualTo(1);
        assertThat(claims.path("role").asText()).isEqualTo("ADMIN");
        assertThat(claims.path("did").asLong()).isEqualTo(1);
        assertThat(claims.path("mgr").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("every login failure is the same failure, so accounts cannot be enumerated")
    void failuresAreIndistinguishable() throws Exception {
        String wrongPassword = perform(post("/api/auth/login", null,
                Map.of("username", "layla", "password", "wrong")))
                .getResponse().getContentAsString();
        String unknownUser = perform(post("/api/auth/login", null,
                Map.of("username", "nobody-at-all", "password", "wrong")))
                .getResponse().getContentAsString();
        String disabled = perform(post("/api/auth/login", null,
                Map.of("username", "svc-reporting", "password", PASSWORD)))
                .getResponse().getContentAsString();

        // svc-reporting is seeded with a valid password and enabled = false, so its refusal
        // proves `enabled` was consulted rather than the password simply being wrong.
        assertThat(wrongPassword).isEqualTo(unknownUser).isEqualTo(disabled);
        assertThat(wrongPassword).doesNotContain("nobody-at-all").doesNotContain("disabled");
    }

    @Test
    @DisplayName("usernames are case-sensitive, matching uq_app_user_username")
    void caseSensitive() throws Exception {
        assertThat(status(post("/api/auth/login", null,
                Map.of("username", "LAYLA", "password", PASSWORD)))).isEqualTo(401);
    }

    // ---------------------------------------------------------------- tokens

    @Test
    @DisplayName("no token is a 401 — criterion 1")
    void noToken() throws Exception {
        assertThat(status(get("/api/parts", null))).isEqualTo(401);
        JsonNode problem = body(get("/api/parts", null));
        assertThat(problem.path("title").asText()).isEqualTo("Unauthenticated");
        assertThat(problem.path("type").asText()).startsWith("https://");
    }

    @Test
    @DisplayName("a token that is not ours is refused, however it is broken")
    void badTokens() throws Exception {
        String good = login("layla");
        String tampered = good.substring(0, good.length() - 3) + "aaa";
        String unsigned = good.substring(0, good.lastIndexOf('.') + 1);

        for (String bad : new String[]{"not-a-token", tampered, unsigned, "", "   "}) {
            assertThat(status(get("/api/parts", bad)))
                    .as("token %s should be refused", bad)
                    .isEqualTo(401);
        }
    }

    // ---------------------------------------------------------------- roles

    @Test
    @DisplayName("reads are open to any authenticated member of staff")
    void readsOpenToStaff() throws Exception {
        String staff = login("omar");
        for (String path : new String[]{"/api/parts", "/api/orders", "/api/customers",
                "/api/suppliers", "/api/departments", "/api/warehouses/3/stock",
                "/api/reports/low-stock", "/api/reports/revenue-by-customer",
                "/api/reports/department-headcount",
                "/api/reports/departments-without-manager"}) {
            assertThat(status(get(path, staff))).as("GET %s", path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("administrative writes need ADMIN — criterion 1")
    void administrativeWritesNeedAdmin() throws Exception {
        String staff = login("omar");

        assertThat(status(post("/api/customers", staff,
                Map.of("name", "X", "phoneNumber", "07" + tag())))).isEqualTo(403);
        assertThat(status(patch("/api/parts/1", staff, Map.of("price", 5)))).isEqualTo(403);
        assertThat(status(delete("/api/parts/1", staff))).isEqualTo(403);
        assertThat(status(post("/api/suppliers", staff, Map.of("name", "X")))).isEqualTo(403);
        assertThat(status(delete("/api/departments/1", staff))).isEqualTo(403);
    }

    @Test
    @DisplayName("the payroll is not general reading, even for GET")
    void payrollIsAdminOnly() throws Exception {
        String staff = login("omar");
        assertThat(status(get("/api/employees", staff))).isEqualTo(403);
        assertThat(status(get("/api/employees/1", staff))).isEqualTo(403);
        assertThat(status(get("/api/employees", login("layla")))).isEqualTo(200);
    }

    @Test
    @DisplayName("operational writes are the staff's daily work, not admin-only")
    void operationalWritesOpenToStaff() throws Exception {
        String staff = login("omar");

        assertThat(status(post("/api/warehouses/3/stock", staff,
                Map.of("partId", 1, "quantity", 1)))).isEqualTo(200);

        JsonNode order = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", java.util.List.of(Map.of("partId", 1, "quantity", 1)))));
        long id = order.path("id").asLong();
        assertThat(id).isPositive();
        assertThat(status(post("/api/orders/" + id + "/cancel", staff, null))).isEqualTo(200);
    }

    // ---------------------------------------------------------------- the manager rule

    @Test
    @DisplayName("a manager may set the manager of their own department, and only theirs")
    void managerRule() throws Exception {
        String rana = login("rana");     // manages department 2
        String omar = login("omar");     // works at department 1, manages nothing
        String admin = login("layla");

        // Both halves of the comparison matter, so both are exercised.
        assertThat(status(patch("/api/departments/2", rana, Map.of("managerId", 3))))
                .as("her own department").isEqualTo(200);
        assertThat(status(patch("/api/departments/1", rana, Map.of("managerId", 2))))
                .as("a department she does not manage").isEqualTo(403);
        assertThat(status(patch("/api/departments/1", omar, Map.of("managerId", 2))))
                .as("the department he merely works in").isEqualTo(403);
        assertThat(status(patch("/api/departments/2", admin, Map.of("managerId", 3))))
                .as("ADMIN needs no manager relationship").isEqualTo(200);
    }

    @Test
    @DisplayName("an ADMIN with nobody on the payroll behind it still passes the role check")
    void adminWithoutEmployee() throws Exception {
        String bare = login("admin");
        // jjwt omits a null claim rather than writing null, so the node is missing rather than
        // null — Jackson treats those as different things and only one of them is `isNull`.
        JsonNode employee = claims(bare).path("eid");
        assertThat(employee.isMissingNode() || employee.isNull())
                .as("an account with nobody on the payroll carries no employee claim").isTrue();
        assertThat(status(get("/api/employees", bare))).isEqualTo(200);
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("a denial is a 403 problem document, not a 500")
    void denialIsAProblemDocument() throws Exception {
        JsonNode problem = body(delete("/api/parts/1", login("omar")));
        assertThat(problem.path("status").asInt()).isEqualTo(403);
        assertThat(problem.path("title").asText()).isEqualTo("Forbidden");
        assertThat(problem.path("type").asText()).endsWith("/forbidden");
        // Naming the rule or the layer would only help somebody mapping the defences.
        assertThat(problem.toString()).doesNotContain("ADMIN").doesNotContain("PreAuthorize");
    }

    @Test
    @DisplayName("authorisation runs before the work, so it cannot be used to probe")
    void authorisationRunsFirst() throws Exception {
        // A row that does not exist still answers 403, not 404.
        assertThat(status(delete("/api/customers/999999", login("omar")))).isEqualTo(403);
    }

    @Test
    @DisplayName("401 and 403 stay distinguishable")
    void unauthenticatedIsNotForbidden() throws Exception {
        assertThat(status(delete("/api/parts/1", null))).isEqualTo(401);
        assertThat(status(delete("/api/parts/1", login("omar")))).isEqualTo(403);
    }

    @Test
    @DisplayName("the API description says how to authenticate, and exempts login")
    void openApiDescribesTheScheme() throws Exception {
        JsonNode doc = body(get("/v3/api-docs", null));
        assertThat(doc.path("components").path("securitySchemes").toString())
                .contains("bearer");
        assertThat(doc.path("paths").path("/api/auth/login").path("post").path("security"))
                .as("logging in must not appear to need a token").isEmpty();
    }
}
