package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Who an order records as its handler, and why the request cannot say.
 *
 * <p>{@code PlaceOrderRequest} has no employee field at all. That absence is the mechanism: a
 * salesperson cannot record an order as handled by a colleague because there is nowhere in the
 * request to write it, rather than because a rule somewhere remembers to refuse. These tests
 * exist to keep the field from being added back by someone who thinks it is missing.
 *
 * <p>Not transactional. Placing an order commits, and {@code ct_order_employee_at_branch} is
 * what refuses a handler from another department — a rolling-back test that never reaches
 * commit would prove considerably less.
 */
@DisplayName("the order handler comes from the session")
class SessionHandlerTest extends AbstractWebTest {

    @Autowired
    private JdbcClient jdbc;

    /**
     * A login for warehouse staff, which V6 does not seed.
     *
     * <p>Every seeded account belongs to branch staff or to nobody, so the rule that warehouse
     * staff cannot take branch orders has no account to test it with. Created once and reused;
     * employee 6 works at Zarqa Warehouse.
     */
    private String warehouseStaffToken() throws Exception {
        Long existing = jdbc.sql("SELECT user_id FROM app_user WHERE username = 'warehouse-test'")
                .query(Long.class).optional().orElse(null);
        if (existing == null) {
            jdbc.sql("""
                    INSERT INTO app_user (username, password_hash, role, enabled, employee_id)
                    SELECT 'warehouse-test', password_hash, 'EMPLOYEE', TRUE, 6
                    FROM app_user WHERE user_id = 1
                    """).update();
        }
        return login("warehouse-test");
    }

    private Map<String, Object> orderAt(long branch, long part) {
        return Map.of("customerId", 1, "branchId", branch, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 1)));
    }

    /** A part of its own, stocked, so these tests never compete for the same shelf. */
    private long stockedPart() throws Exception {
        String admin = login("layla");
        JsonNode supplier = body(post("/api/suppliers", admin,
                Map.of("name", "Handler Vendor " + System.nanoTime())));
        JsonNode part = body(post("/api/parts", admin, Map.of(
                "sku", "HD-" + System.nanoTime(), "name", "Handler Part",
                "price", 10.00, "weightKg", 1.0, "reorderLevel", 0,
                "supplierId", supplier.path("id").asLong())));
        long id = part.path("id").asLong();
        body(post("/api/warehouses/3/stock", login("omar"), Map.of("partId", id, "quantity", 50)));
        return id;
    }

    @Test
    @DisplayName("the order records the caller, without the body naming anybody")
    void handlerIsTheCaller() throws Exception {
        long part = stockedPart();

        JsonNode omar = body(post("/api/orders", login("omar"), orderAt(1, part)));
        assertThat(omar.path("employeeId").asLong()).isEqualTo(2);
        assertThat(omar.path("employeeName").asText()).isEqualTo("Omar Nasser");

        // A different caller records differently, so it follows the session rather than a
        // constant that happens to match.
        JsonNode rana = body(post("/api/orders", login("rana"), orderAt(2, part)));
        assertThat(rana.path("employeeId").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("an employee id in the body is ignored, not honoured")
    void bodyCannotNameAHandler() throws Exception {
        long part = stockedPart();

        // Every shape somebody might try, in one request.
        Map<String, Object> injected = new java.util.HashMap<>(orderAt(1, part));
        injected.put("employeeId", 3);
        injected.put("employee", 3);
        injected.put("handlerId", 3);
        injected.put("handlingEmployeeId", 3);

        JsonNode placed = body(post("/api/orders", login("omar"), injected));
        assertThat(placed.path("employeeId").asLong())
                .as("omar is employee 2; rana is 3, and he tried to be her")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a salesperson cannot take an order for another branch")
    void cannotSellAtAnotherBranch() throws Exception {
        long part = stockedPart();

        JsonNode problem = body(post("/api/orders", login("omar"), orderAt(2, part)));
        assertThat(problem.path("status").asInt()).isEqualTo(400);
        assertThat(problem.path("detail").asText())
                .contains("Omar Nasser")
                .contains("Airport Road Branch");
    }

    @Test
    @DisplayName("warehouse staff cannot take branch orders at all — criterion 5")
    void warehouseStaffCannotSell() throws Exception {
        long part = stockedPart();
        String warehouse = warehouseStaffToken();

        for (long branch : new long[]{1, 2}) {
            JsonNode problem = body(post("/api/orders", warehouse, orderAt(branch, part)));
            assertThat(problem.path("status").asInt())
                    .as("branch %d", branch).isEqualTo(400);
            assertThat(problem.path("detail").asText()).contains("Yousef Odeh");
        }
    }

    @Test
    @DisplayName("being ADMIN does not bypass the branch rule")
    void adminIsNotExempt() throws Exception {
        long part = stockedPart();

        // layla is ADMIN and employee 1, at branch 1. The rule is about which employee is
        // behind the account, not what the account may do generally.
        JsonNode problem = body(post("/api/orders", login("layla"), orderAt(2, part)));
        assertThat(problem.path("status").asInt()).isEqualTo(400);
        assertThat(problem.path("detail").asText()).contains("Layla Haddad");
    }

    @Test
    @DisplayName("an account with nobody on the payroll records no handler")
    void accountWithoutEmployee() throws Exception {
        long part = stockedPart();

        // app_user.employee_id is nullable for an administrative or service account, and
        // customer_order.employee_id is nullable to match — V6 seeds an order "taken without a
        // named salesperson" precisely because the schema means to allow it.
        JsonNode placed = body(post("/api/orders", login("admin"), orderAt(1, part)));
        assertThat(placed.path("employeeId").isNull()).isTrue();
        assertThat(placed.path("employeeName").isNull()).isTrue();
    }

    @Test
    @DisplayName("the recorded handler survives a read and is filterable")
    void handlerSurvivesARead() throws Exception {
        long part = stockedPart();
        String omar = login("omar");
        long id = body(post("/api/orders", omar, orderAt(1, part))).path("id").asLong();

        assertThat(body(get("/api/orders/" + id, omar)).path("employeeId").asLong()).isEqualTo(2);

        boolean found = false;
        for (int page = 0; page < 200 && !found; page++) {
            JsonNode d = body(get("/api/orders?employeeId=2&size=100&page=" + page, omar));
            if (d.path("content").isEmpty()) {
                break;
            }
            for (JsonNode row : d.path("content")) {
                found |= row.path("orderId").asLong() == id;
            }
        }
        assertThat(found).as("findable by the handler filter").isTrue();
    }
}
