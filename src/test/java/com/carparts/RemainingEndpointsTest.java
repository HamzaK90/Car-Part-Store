package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The endpoints the first pass of the suite left uncovered.
 *
 * <p>Three of them are not incidental. Removing a stock line is where a lock bug lived — the
 * code read the quantity, checked it was zero, then deleted, so a delivery landing in that
 * window vanished with the row. Amending an order is the only place two pricing rules meet.
 * And fitments are the write side of the question this business is actually asked.
 */
@DisplayName("the remaining endpoints")
class RemainingEndpointsTest extends AbstractWebTest {

    private String admin;
    private String staff;

    private void logIn() throws Exception {
        admin = login("layla");
        staff = login("omar");
    }

    /** A part of its own, so nothing here competes with another test for a shelf. */
    private long newPart(double price) throws Exception {
        JsonNode supplier = body(post("/api/suppliers", admin,
                Map.of("name", "Remaining Vendor " + System.nanoTime())));
        return body(post("/api/parts", admin, Map.of(
                "sku", "RM-" + System.nanoTime(), "name", "Remaining Part",
                "price", price, "weightKg", 1.0, "reorderLevel", 0,
                "supplierId", supplier.path("id").asLong()))).path("id").asLong();
    }

    private int stockAt(long warehouse, long part) throws Exception {
        for (JsonNode row : body(get("/api/parts/" + part + "/stock", staff))) {
            if (row.path("warehouseId").asLong() == warehouse) {
                return row.path("quantity").asInt();
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- stock removal

    @Test
    @DisplayName("a warehouse stops carrying a part only once the shelf is empty")
    void removalRequiresAnEmptyShelf() throws Exception {
        logIn();
        long part = newPart(10.00);
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 4)));

        JsonNode refused = body(delete("/api/warehouses/3/stock/" + part, staff));
        assertThat(refused.path("status").asInt())
                .as("units remain, so the row must stay").isEqualTo(409);
        assertThat(stockAt(3, part)).isEqualTo(4);

        body(put("/api/warehouses/3/stock/" + part, staff, Map.of("quantity", 0)));
        assertThat(status(delete("/api/warehouses/3/stock/" + part, staff))).isEqualTo(204);
        assertThat(stockAt(3, part)).as("the row is gone, which reads as zero").isZero();
    }

    @Test
    @DisplayName("a delivery arriving before the removal is not silently discarded")
    void deliveryBeatsRemoval() throws Exception {
        logIn();
        long part = newPart(10.00);
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 0)));

        // The shape of the bug this endpoint had: it read the quantity, saw zero, and deleted.
        // Anything received in between went with the row and nothing recorded that it had
        // arrived. remove() takes the lock as its read now, so the refusal below is the proof
        // that it sees the delivery rather than a stale zero.
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 7)));

        assertThat(status(delete("/api/warehouses/3/stock/" + part, staff))).isEqualTo(409);
        assertThat(stockAt(3, part)).as("the seven units are still there").isEqualTo(7);
    }

    @Test
    @DisplayName("removing a line a warehouse never carried is a 404")
    void removingWhatWasNeverThere() throws Exception {
        logIn();
        assertThat(status(delete("/api/warehouses/4/stock/999999", staff))).isEqualTo(404);
    }

    // ---------------------------------------------------------------- amending an order

    @Test
    @DisplayName("an existing line keeps the price it was sold at; a new one bills at today's")
    void amendingPreservesTheAgreedPrice() throws Exception {
        logIn();
        long sold = newPart(45.00);
        long added = newPart(20.00);
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", sold, "quantity", 50)));
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", added, "quantity", 50)));

        JsonNode order = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", sold, "quantity", 2)))));
        long id = order.path("id").asLong();

        // Reprice after the sale. The existing line must not follow it.
        body(patch("/api/parts/" + sold, admin, Map.of("price", 999.00)));

        JsonNode amended = body(patch("/api/orders/" + id + "/lines", staff, Map.of(
                "lines", List.of(Map.of("partId", sold, "quantity", 2),
                                 Map.of("partId", added, "quantity", 1)))));

        assertThat(amended.path("lines")).hasSize(2);
        for (JsonNode line : amended.path("lines")) {
            if (line.path("partId").asLong() == sold) {
                assertThat(line.path("unitPrice").asDouble())
                        .as("the customer keeps the quote they were given").isEqualTo(45.00);
            } else {
                assertThat(line.path("unitPrice").asDouble())
                        .as("a part added now is billed at now's price").isEqualTo(20.00);
            }
        }
        assertThat(amended.path("total").asDouble()).isEqualTo(110.00);
    }

    @Test
    @DisplayName("amending returns the stock a removed line had taken")
    void amendingReturnsStock() throws Exception {
        logIn();
        long part = newPart(10.00);
        long other = newPart(10.00);
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 20)));
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", other, "quantity", 20)));

        long id = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 5))))).path("id").asLong();
        assertThat(stockAt(3, part)).isEqualTo(15);

        // The request is the complete desired set, not a delta: dropping a part removes it.
        body(patch("/api/orders/" + id + "/lines", staff, Map.of(
                "lines", List.of(Map.of("partId", other, "quantity", 1)))));

        assertThat(stockAt(3, part)).as("the five come back").isEqualTo(20);
        assertThat(stockAt(3, other)).as("and the new line takes one").isEqualTo(19);
    }

    @Test
    @DisplayName("an order cannot be amended down to nothing, or beyond the shelf")
    void amendmentIsGuarded() throws Exception {
        logIn();
        long part = newPart(10.00);
        body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 3)));
        long id = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", part, "quantity", 1))))).path("id").asLong();

        // Emptying an order is cancelling it, which has its own endpoint and returns the stock.
        assertThat(status(patch("/api/orders/" + id + "/lines", staff,
                Map.of("lines", List.of())))).isEqualTo(400);

        assertThat(status(patch("/api/orders/" + id + "/lines", staff, Map.of(
                "lines", List.of(Map.of("partId", part, "quantity", 999)))))).isEqualTo(409);
        assertThat(stockAt(3, part)).as("a refused amendment moves nothing").isEqualTo(2);
    }

    // ---------------------------------------------------------------- fitments

    @Test
    @DisplayName("a fitment makes the part findable by car, and only within its years")
    void fitmentsDriveTheCarSearch() throws Exception {
        logIn();
        long part = newPart(30.00);
        String model = "Model" + System.nanoTime();

        assertThat(status(post("/api/parts/" + part + "/fitments", admin, Map.of(
                "make", "Toyota", "model", model, "yearFrom", 2015, "yearTo", 2020))))
                .isEqualTo(201);

        assertThat(searchFinds(part, model, 2017)).as("inside the range").isTrue();
        assertThat(searchFinds(part, model, 2024)).as("after it").isFalse();
        assertThat(searchFinds(part, model, 2010)).as("before it").isFalse();
    }

    @Test
    @DisplayName("only the last model year can be corrected — the rest is the fitment's identity")
    void correctingAFitment() throws Exception {
        logIn();
        long part = newPart(30.00);
        String model = "Model" + System.nanoTime();
        body(post("/api/parts/" + part + "/fitments", admin, Map.of(
                "make", "Toyota", "model", model, "yearFrom", 2015, "yearTo", 2020)));

        String query = "?make=Toyota&model=" + model + "&yearFrom=2015";
        assertThat(status(patch("/api/parts/" + part + "/fitments" + query, admin,
                Map.of("yearTo", 2022)))).isEqualTo(200);

        assertThat(searchFinds(part, model, 2022))
                .as("the model stayed in production a year longer than expected").isTrue();
    }

    @Test
    @DisplayName("a fitment cannot end before it begins, or be recorded twice")
    void fitmentRulesAreEnforced() throws Exception {
        logIn();
        long part = newPart(30.00);
        String model = "Model" + System.nanoTime();

        assertThat(status(post("/api/parts/" + part + "/fitments", admin, Map.of(
                "make", "Toyota", "model", model, "yearFrom", 2020, "yearTo", 2015))))
                .as("inverted years").isEqualTo(400);

        body(post("/api/parts/" + part + "/fitments", admin, Map.of(
                "make", "Toyota", "model", model, "yearFrom", 2015, "yearTo", 2020)));
        assertThat(status(post("/api/parts/" + part + "/fitments", admin, Map.of(
                "make", "Toyota", "model", model, "yearFrom", 2015, "yearTo", 2021))))
                .as("same make, model and first year is the same fitment").isEqualTo(400);
    }

    @Test
    @DisplayName("a fitment can be removed, and listing one for an unknown part is a 404")
    void removingAFitment() throws Exception {
        logIn();
        long part = newPart(30.00);
        String model = "Model" + System.nanoTime();
        body(post("/api/parts/" + part + "/fitments", admin, Map.of(
                "make", "Toyota", "model", model, "yearFrom", 2015, "yearTo", 2020)));
        assertThat(body(get("/api/parts/" + part + "/fitments", staff))).hasSize(1);

        String query = "?make=Toyota&model=" + model + "&yearFrom=2015";
        assertThat(status(delete("/api/parts/" + part + "/fitments" + query, admin)))
                .isEqualTo(204);
        assertThat(body(get("/api/parts/" + part + "/fitments", staff))).isEmpty();

        // "fits nothing" and "there is no such part" are different answers.
        assertThat(status(get("/api/parts/999999/fitments", staff))).isEqualTo(404);
    }

    // ---------------------------------------------------------------- transfers

    @Test
    @DisplayName("transferring an employee vacates any post they held")
    void transferVacatesTheirPost() throws Exception {
        logIn();
        JsonNode department = body(post("/api/departments", admin, Map.of(
                "name", "Transfer Dept " + System.nanoTime(), "type", "BRANCH",
                "city", "Amman", "street", "St")));
        long id = department.path("id").asLong();

        long employee = body(post("/api/employees", admin, Map.of(
                "fullName", "Transfer Person " + System.nanoTime(), "salary", 700,
                "workShift", "MORNING", "departmentId", id)))
                .path("employee").path("id").asLong();
        body(patch("/api/departments/" + id, admin, Map.of("managerId", employee)));
        assertThat(body(get("/api/departments/" + id, admin)).path("managerId").asLong())
                .isEqualTo(employee);

        // Losing a manager is an ordinary event; a manager who works elsewhere is the broken
        // state. So the move is allowed and the post empties.
        assertThat(status(patch("/api/employees/" + employee + "/department/1", admin, null)))
                .isEqualTo(200);
        assertThat(body(get("/api/departments/" + id, admin)).path("managerId").isNull())
                .as("the post is vacated, not the transfer refused").isTrue();
    }

    private boolean searchFinds(long part, String model, int year) throws Exception {
        JsonNode page = body(get("/api/parts?make=Toyota&model=" + model + "&year=" + year
                + "&size=100", staff));
        for (JsonNode row : page.path("content")) {
            if (row.path("id").asLong() == part) {
                return true;
            }
        }
        return false;
    }
}
