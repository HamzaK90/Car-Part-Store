package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The business, driven over HTTP: opening premises, hiring, stocking, selling, invoicing.
 *
 * <p>These are the acceptance criteria and the paths a real day exercises. Everything runs
 * through the full stack — filter chain, controller, service, database — because that is where
 * this project's defects have actually lived. Rendering an order outside its transaction, an
 * N+1 behind a listing, a paged query losing rows: none of them are visible to a unit test.
 */
@DisplayName("the business, end to end")
class ApiJourneyTest extends AbstractWebTest {

    private String admin;
    private String staff;

    private void logIn() throws Exception {
        admin = login("layla");
        staff = login("omar");
    }

    private long id(JsonNode node) {
        return node.has("id") ? node.path("id").asLong() : node.path("orderId").asLong();
    }

    // ---------------------------------------------------------------- opening up

    @Test
    @DisplayName("a department opens with no manager, and hiring offers the first candidate")
    void openingAndHiring() throws Exception {
        logIn();
        JsonNode branch = body(post("/api/departments", admin, Map.of(
                "name", "Journey Branch " + tag(), "type", "BRANCH",
                "city", "Aqaba", "street", "High St")));
        assertThat(branch.path("managerId").isNull())
                .as("a department exists before anyone is hired into it").isTrue();

        JsonNode hired = body(post("/api/employees", admin, Map.of(
                "fullName", "Journey Seller " + tag(), "salary", 800,
                "workShift", "MORNING", "departmentId", id(branch))));

        JsonNode vacancy = hired.path("managerVacancy");
        assertThat(vacancy.isMissingNode() || vacancy.isNull()).isFalse();
        assertThat(vacancy.path("eligibleEmployees").asInt())
                .as("the new hire is the one candidate").isEqualTo(1);

        long seller = hired.path("employee").path("id").asLong();
        JsonNode promoted = body(patch("/api/departments/" + id(branch), admin,
                Map.of("managerId", seller)));
        assertThat(promoted.path("managerId").asLong()).isEqualTo(seller);
    }

    @Test
    @DisplayName("free area belongs to a warehouse and only a warehouse")
    void freeAreaIsAWarehouseThing() throws Exception {
        logIn();
        assertThat(status(post("/api/departments", admin, Map.of(
                "name", "Bad " + tag(), "type", "BRANCH", "city", "A", "street", "B",
                "freeAreaSqm", 10)))).isEqualTo(400);
        assertThat(status(post("/api/departments", admin, Map.of(
                "name", "Bad2 " + tag(), "type", "WAREHOUSE", "city", "A", "street", "B"))))
                .isEqualTo(400);
    }

    // ---------------------------------------------------------------- selling

    @Test
    @DisplayName("placing an order decrements stock — criterion 2")
    void placingAnOrderDecrementsStock() throws Exception {
        logIn();
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 30)));

        JsonNode order = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 4)))));

        assertThat(order.path("total").asDouble()).isEqualTo(180.00);
        assertThat(stockOf(part, 3)).as("30 minus the four sold").isEqualTo(26);
    }

    @Test
    @DisplayName("ordering more than exists is a 409, and nothing moves — criterion 3")
    void oversellChangesNothing() throws Exception {
        logIn();
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 5)));

        JsonNode problem = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 99)))));

        assertThat(problem.path("status").asInt()).isEqualTo(409);
        JsonNode shortage = problem.path("shortages").get(0);
        assertThat(shortage.path("requested").asInt()).isEqualTo(99);
        assertThat(shortage.path("available").asInt()).isEqualTo(5);
        assertThat(shortage.path("shortBy").asInt()).isEqualTo(94);
        assertThat(stockOf(part, 3)).as("stock is untouched").isEqualTo(5);
    }

    @Test
    @DisplayName("every shortage is reported, not just the first")
    void everyShortageReported() throws Exception {
        logIn();
        long a = newPart();
        long b = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", a, "quantity", 1)));
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", b, "quantity", 1)));

        JsonNode problem = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", a, "quantity", 9),
                                 Map.of("partId", b, "quantity", 9)))));

        assertThat(problem.path("shortages")).as("one round trip, not three").hasSize(2);
    }

    @Test
    @DisplayName("the same part twice on one order is summed before the stock check")
    void duplicateLinesAreSummed() throws Exception {
        logIn();
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 4)));

        // Three and three is six, not two separate threes against a shelf holding four.
        JsonNode problem = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 3),
                                 Map.of("partId", part, "quantity", 3)))));

        assertThat(problem.path("status").asInt()).isEqualTo(409);
        assertThat(problem.path("shortages").get(0).path("requested").asInt()).isEqualTo(6);
        assertThat(stockOf(part, 3)).isEqualTo(4);
    }

    @Test
    @DisplayName("an invoice total is unaffected by a later reprice — criterion 4")
    void repricingDoesNotMoveASettledOrder() throws Exception {
        logIn();
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 10)));
        JsonNode order = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 2)))));
        long orderId = id(order);

        body(patch("/api/parts/" + part, admin, Map.of("price", 999.00)));

        JsonNode after = body(get("/api/orders/" + orderId, staff));
        assertThat(after.path("total").asDouble()).isEqualTo(90.00);
        assertThat(after.path("lines").get(0).path("unitPrice").asDouble()).isEqualTo(45.00);
    }

    @Test
    @DisplayName("a warehouse id given as the branch is a 404, not a bad sale")
    void warehouseIsNotABranch() throws Exception {
        logIn();
        assertThat(status(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 3, "warehouseId", 3,
                "lines", List.of(Map.of("partId", 1, "quantity", 1)))))).isEqualTo(404);
    }

    // ---------------------------------------------------------------- fulfilment

    @Test
    @DisplayName("fulfilment renders the whole order — the lazy-loading regression")
    void fulfilRendersCompletely() throws Exception {
        logIn();
        long orderId = id(placeSmallOrder());

        JsonNode fulfilled = body(post("/api/orders/" + orderId + "/fulfil", staff, null));

        // This is the shape of a bug that shipped: the write committed and the response threw
        // while rendering, so the caller saw a 500 for an order that really was fulfilled.
        assertThat(fulfilled.path("status").asText()).isEqualTo("FULFILLED");
        assertThat(fulfilled.path("customerName").asText()).isNotBlank();
        assertThat(fulfilled.path("branchName").asText()).isNotBlank();
        assertThat(fulfilled.path("lines")).isNotEmpty();
    }

    @Test
    @DisplayName("only a PLACED order may be fulfilled or cancelled")
    void statusTransitionsAreGuarded() throws Exception {
        logIn();
        long orderId = id(placeSmallOrder());
        assertThat(status(post("/api/orders/" + orderId + "/fulfil", staff, null))).isEqualTo(200);
        assertThat(status(post("/api/orders/" + orderId + "/cancel", staff, null))).isEqualTo(409);
    }

    @Test
    @DisplayName("cancelling returns the parts to the shelf")
    void cancellingRestoresStock() throws Exception {
        logIn();
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 10)));
        JsonNode order = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 3)))));
        assertThat(stockOf(part, 3)).isEqualTo(7);

        body(post("/api/orders/" + id(order) + "/cancel", staff, null));

        // Changing the status alone would leak inventory silently: taken off the shelf, never
        // given back, and nothing reporting the gap.
        assertThat(stockOf(part, 3)).isEqualTo(10);
    }

    // ---------------------------------------------------------------- stock

    @Test
    @DisplayName("a transfer moves both ends atomically, and says so")
    void transferMovesBothEnds() throws Exception {
        logIn();
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 20)));

        JsonNode moved = body(post("/api/warehouses/3/stock/transfer", staff,
                Map.of("toWarehouseId", 4, "partId", part, "quantity", 6)));

        assertThat(moved.path("from").path("warehouseId").asInt()).isEqualTo(3);
        assertThat(moved.path("to").path("warehouseId").asInt()).isEqualTo(4);
        assertThat(moved.path("from").path("quantity").asInt()).isEqualTo(14);
        assertThat(moved.path("to").path("quantity").asInt()).isEqualTo(6);
        assertThat(stockOf(part, 3) + stockOf(part, 4))
                .as("nothing is created or lost across the pair").isEqualTo(20);
    }

    @Test
    @DisplayName("a transfer the source cannot cover moves nothing")
    void transferShortMovesNothing() throws Exception {
        logIn();
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 2)));

        assertThat(status(post("/api/warehouses/3/stock/transfer", staff,
                Map.of("toWarehouseId", 4, "partId", part, "quantity", 99)))).isEqualTo(409);
        assertThat(stockOf(part, 3)).isEqualTo(2);
        assertThat(stockOf(part, 4)).isZero();
    }

    @Test
    @DisplayName("receiving adds, a stock-take replaces")
    void receiveAddsCountReplaces() throws Exception {
        logIn();
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 10)));
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 5)));
        assertThat(stockOf(part, 3)).as("two deliveries are two deliveries").isEqualTo(15);

        body(put("/api/warehouses/3/stock/" + part, staff, Map.of("quantity", 7)));
        assertThat(stockOf(part, 3)).as("a count says what is there").isEqualTo(7);
    }

    // ---------------------------------------------------------------- reports and invoice

    @Test
    @DisplayName("revenue counts what was sold and excludes what was cancelled")
    void revenueExcludesCancelled() throws Exception {
        logIn();
        JsonNode customer = body(post("/api/customers", admin, Map.of(
                "name", "Journey Buyer " + tag(), "phoneNumber", "079" + tag())));
        long buyer = id(customer);
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 20)));

        JsonNode kept = body(post("/api/orders", staff, Map.of("customerId", buyer,
                "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 2)))));
        JsonNode dropped = body(post("/api/orders", staff, Map.of("customerId", buyer,
                "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 3)))));
        body(post("/api/orders/" + id(dropped) + "/cancel", staff, null));

        JsonNode row = revenueRowFor(buyer);
        assertThat(row.path("orderCount").asInt()).isEqualTo(1);
        assertThat(row.path("revenue").asDouble()).isEqualTo(kept.path("total").asDouble());
    }

    @Test
    @DisplayName("an invoice is a PDF that says what the order says")
    void invoiceIsAPdf() throws Exception {
        logIn();
        long orderId = id(placeSmallOrder());

        byte[] pdf = perform(get("/api/orders/" + orderId + "/invoice.pdf", staff))
                .getResponse().getContentAsByteArray();

        assertThat(pdf).startsWith("%PDF-".getBytes());
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("a PDF must end with its end-of-file marker").contains("%%EOF");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    // ---------------------------------------------------------------- cross-cutting

    @Test
    @DisplayName("every listing is capped, whatever is asked for")
    void listingsAreCapped() throws Exception {
        logIn();
        for (String path : new String[]{"/api/parts", "/api/customers", "/api/suppliers",
                "/api/departments", "/api/orders", "/api/employees",
                "/api/reports/revenue-by-customer"}) {
            assertThat(body(get(path + "?size=9999", admin)).path("size").asInt())
                    .as("%s", path).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("paging returns every row exactly once — no repeats, no gaps")
    void pagingIsStable() throws Exception {
        logIn();
        for (String path : new String[]{"/api/parts", "/api/customers", "/api/employees"}) {
            java.util.List<Long> seen = new java.util.ArrayList<>();
            long total = 0;
            for (int page = 0; page < 200; page++) {
                JsonNode d = body(get(path + "?size=3&page=" + page, admin));
                total = d.path("totalElements").asLong();
                if (d.path("content").isEmpty()) {
                    break;
                }
                d.path("content").forEach(r -> seen.add(r.path("id").asLong()));
            }
            assertThat(seen).as("%s repeated a row", path).doesNotHaveDuplicates();
            assertThat(seen).as("%s skipped a row", path).hasSize((int) total);
        }
    }

    @Test
    @DisplayName("failures are problem documents, never a bare status or a 500")
    void failuresAreProblemDocuments() throws Exception {
        logIn();
        record Case(String path, int expected) {}
        for (Case c : List.of(new Case("/api/parts/999999", 404),
                              new Case("/api/orders/999999", 404),
                              new Case("/api/parts/abc", 400),
                              new Case("/api/nothing-here", 404))) {
            JsonNode problem = body(get(c.path(), admin));
            assertThat(problem.path("status").asInt()).as("%s", c.path()).isEqualTo(c.expected());
            assertThat(problem.path("type").asText()).startsWith("https://");
        }
        assertThat(status(post("/api/customers", admin, "{not json"))).isEqualTo(400);
    }

    @Test
    @DisplayName("a delete is refused while anything still points at the row")
    void deletesAreRefusedWhileReferenced() throws Exception {
        logIn();
        assertThat(status(delete("/api/customers/1", admin))).isEqualTo(409);
        assertThat(status(delete("/api/departments/1", admin))).isEqualTo(409);
        JsonNode problem = body(delete("/api/customers/1", admin));
        assertThat(problem.path("detail").asText())
                .as("a refusal must say what still points at it").isNotBlank();
    }

    // ---------------------------------------------------------------- fixtures

    /** A part of its own, so a test's arithmetic cannot be disturbed by another's. */
    private long newPart() throws Exception {
        JsonNode supplier = body(post("/api/suppliers", admin,
                Map.of("name", "Journey Vendor " + tag() + "-" + System.nanoTime())));
        JsonNode part = body(post("/api/parts", admin, Map.of(
                "sku", "JN-" + System.nanoTime(), "name", "Journey Part",
                "price", 45.00, "weightKg", 1.0, "reorderLevel", 5,
                "supplierId", id(supplier))));
        return id(part);
    }

    private JsonNode placeSmallOrder() throws Exception {
        long part = newPart();
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 5)));
        return body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 1)))));
    }

    private int stockOf(long part, int warehouse) throws Exception {
        for (JsonNode row : body(get("/api/parts/" + part + "/stock", staff))) {
            if (row.path("warehouseId").asInt() == warehouse) {
                return row.path("quantity").asInt();
            }
        }
        return 0;
    }

    private JsonNode revenueRowFor(long customer) throws Exception {
        for (int page = 0; page < 200; page++) {
            JsonNode d = body(get("/api/reports/revenue-by-customer?size=100&page=" + page, staff));
            if (d.path("content").isEmpty()) {
                break;
            }
            for (JsonNode row : d.path("content")) {
                if (row.path("customerId").asLong() == customer) {
                    return row;
                }
            }
        }
        throw new AssertionError("customer " + customer + " missing from the revenue report");
    }
}
