package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Creating, reading, changing and removing the people and places.
 *
 * <p>The three PATCH endpoints were the largest untested surface left: updating a customer, a
 * supplier or an employee. Each carries a rule that only shows up when a <em>partial</em>
 * request arrives — a blank optional field, an address given half at a time — and a test that
 * only ever sends complete objects would never meet them.
 */
@DisplayName("customers, suppliers, employees and departments")
class CrudEndpointsTest extends AbstractWebTest {

    private String admin;
    private String staff;

    private void logIn() throws Exception {
        admin = login("layla");
        staff = login("omar");
    }

    private String phone() {
        return "079" + tag().substring(0, Math.min(7, tag().length()));
    }

    // ---------------------------------------------------------------- customers

    @Test
    @DisplayName("a customer is created, found by search, changed and removed")
    void customerLifecycle() throws Exception {
        logIn();
        String name = "Crud Buyer " + tag();
        JsonNode created = body(post("/api/customers", admin, Map.of(
                "name", name, "phoneNumber", phone(), "email", "crud" + tag() + "@example.com")));
        long id = created.path("id").asLong();
        assertThat(id).isPositive();

        assertThat(body(get("/api/customers?search=" + tag(), staff))
                .path("totalElements").asInt())
                .as("findable by the tag in their name").isEqualTo(1);
        assertThat(body(get("/api/customers?search=" + phone(), staff))
                .path("totalElements").asInt())
                .as("and by phone, which is what a counter asks for").isEqualTo(1);

        JsonNode renamed = body(patch("/api/customers/" + id, admin, Map.of("name", "Renamed")));
        assertThat(renamed.path("name").asText()).isEqualTo("Renamed");
        assertThat(renamed.path("phoneNumber").asText())
                .as("a name-only patch leaves the phone alone").isEqualTo(phone());

        assertThat(status(delete("/api/customers/" + id, admin))).isEqualTo(204);
        assertThat(status(get("/api/customers/" + id, staff))).isEqualTo(404);
    }

    @Test
    @DisplayName("two customers with no email do not collide")
    void blankEmailIsNotAValue() throws Exception {
        logIn();
        // An empty string is not NULL, and uq_customer_email would treat two of them as a
        // duplicate — telling the second customer their address is already registered when
        // neither supplied one.
        JsonNode first = body(post("/api/customers", admin, Map.of(
                "name", "Blank One " + tag(), "phoneNumber", phone() + "1", "email", "")));
        JsonNode second = body(post("/api/customers", admin, Map.of(
                "name", "Blank Two " + tag(), "phoneNumber", phone() + "2", "email", "   ")));

        assertThat(first.path("email").isNull()).isTrue();
        assertThat(second.path("email").isNull()).isTrue();
        assertThat(second.path("id").asLong()).isPositive();
    }

    @Test
    @DisplayName("a blank email on patch is a no-op, and whitespace behaves the same way")
    void blankEmailOnPatch() throws Exception {
        logIn();
        JsonNode c = body(post("/api/customers", admin, Map.of(
                "name", "Patch Buyer " + tag(), "phoneNumber", phone() + "3",
                "email", "keep" + tag() + "@example.com")));
        long id = c.path("id").asLong();

        // Blank is normalised before validation, so it arrives as null — and null means "leave
        // this alone". @Email would otherwise reject "   " while accepting "", which is the
        // same intent answered two ways.
        for (String blank : new String[]{"", "   "}) {
            JsonNode after = body(patch("/api/customers/" + id, admin, Map.of("email", blank)));
            assertThat(after.path("email").asText())
                    .as("blank %s", blank.isEmpty() ? "empty" : "whitespace")
                    .isEqualTo("keep" + tag() + "@example.com");
        }
    }

    @Test
    @DisplayName("a duplicate phone or email is a 409 naming the constraint")
    void customerUniqueness() throws Exception {
        logIn();
        String email = "dup" + tag() + "@example.com";
        body(post("/api/customers", admin, Map.of(
                "name", "First " + tag(), "phoneNumber", phone() + "4", "email", email)));

        JsonNode dupPhone = body(post("/api/customers", admin, Map.of(
                "name", "Second " + tag(), "phoneNumber", phone() + "4")));
        assertThat(dupPhone.path("constraint").asText()).isEqualTo("uq_customer_phone");

        JsonNode dupEmail = body(post("/api/customers", admin, Map.of(
                "name", "Third " + tag(), "phoneNumber", phone() + "5", "email", email)));
        assertThat(dupEmail.path("constraint").asText()).isEqualTo("uq_customer_email");
    }

    @Test
    @DisplayName("invalid customer input is a 400 naming the field")
    void customerValidation() throws Exception {
        logIn();
        assertThat(body(post("/api/customers", admin, Map.of("name", "No Phone " + tag())))
                .path("errors").has("phoneNumber")).isTrue();
        assertThat(body(post("/api/customers", admin, Map.of("phoneNumber", phone() + "6")))
                .path("errors").has("name")).isTrue();
        assertThat(body(post("/api/customers", admin, Map.of(
                "name", "Bad " + tag(), "phoneNumber", phone() + "7", "email", "not-an-email")))
                .path("errors").has("email")).isTrue();
    }

    // ---------------------------------------------------------------- suppliers

    @Test
    @DisplayName("a supplier's address is patched as one value, not two columns")
    void supplierAddressIsOneValue() throws Exception {
        logIn();
        JsonNode s = body(post("/api/suppliers", admin, Map.of(
                "name", "Crud Vendor " + tag(), "city", "Amman", "street", "Trade St",
                "phoneNumber", "06" + tag())));
        long id = s.path("id").asLong();

        JsonNode cityOnly = body(patch("/api/suppliers/" + id, admin, Map.of("city", "Zarqa")));
        assertThat(cityOnly.path("city").asText()).isEqualTo("Zarqa");
        assertThat(cityOnly.path("street").asText())
                .as("naming only the city must not erase the street").isEqualTo("Trade St");

        JsonNode streetOnly = body(patch("/api/suppliers/" + id, admin,
                Map.of("street", "New Rd")));
        assertThat(streetOnly.path("city").asText()).isEqualTo("Zarqa");
        assertThat(streetOnly.path("street").asText()).isEqualTo("New Rd");

        JsonNode nameOnly = body(patch("/api/suppliers/" + id, admin,
                Map.of("name", "Renamed Vendor " + tag())));
        assertThat(nameOnly.path("city").asText()).as("and neither must a rename").isEqualTo("Zarqa");
        assertThat(nameOnly.path("street").asText()).isEqualTo("New Rd");
    }

    @Test
    @DisplayName("a supplier with no parts can be removed; one with parts cannot")
    void supplierRemoval() throws Exception {
        logIn();
        JsonNode unused = body(post("/api/suppliers", admin,
                Map.of("name", "Unused Vendor " + tag())));
        assertThat(status(delete("/api/suppliers/" + unused.path("id").asLong(), admin)))
                .isEqualTo(204);

        JsonNode used = body(post("/api/suppliers", admin,
                Map.of("name", "Used Vendor " + tag())));
        body(post("/api/parts", admin, Map.of(
                "sku", "CR-" + System.nanoTime(), "name", "Crud Part", "price", 10.00,
                "weightKg", 1.0, "reorderLevel", 0, "supplierId", used.path("id").asLong())));

        JsonNode refused = body(delete("/api/suppliers/" + used.path("id").asLong(), admin));
        assertThat(refused.path("constraint").asText()).isEqualTo("fk_part_supplier");
        assertThat(refused.path("detail").asText())
                .as("the message must describe the delete, not the insert")
                .contains("parts in the catalogue still come from");
    }

    @Test
    @DisplayName("a duplicate supplier name is refused, and blanks are stored as nothing")
    void supplierUniquenessAndBlanks() throws Exception {
        logIn();
        String name = "Once Only " + tag();
        body(post("/api/suppliers", admin, Map.of("name", name)));
        assertThat(body(post("/api/suppliers", admin, Map.of("name", name)))
                .path("constraint").asText()).isEqualTo("uq_supplier_name");

        JsonNode blanks = body(post("/api/suppliers", admin, Map.of(
                "name", "Blank Vendor " + tag(), "city", "", "street", "  ",
                "phoneNumber", "")));
        assertThat(blanks.path("city").isNull()).isTrue();
        assertThat(blanks.path("street").isNull()).isTrue();
        assertThat(blanks.path("phoneNumber").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- employees

    @Test
    @DisplayName("an employee's own details are patched; the department is not among them")
    void employeeUpdate() throws Exception {
        logIn();
        JsonNode hired = body(post("/api/employees", admin, Map.of(
                "fullName", "Crud Staff " + tag(), "salary", 700, "workShift", "MORNING",
                "departmentId", 1, "city", "Amman", "street", "First St")));
        long id = hired.path("employee").path("id").asLong();

        JsonNode raised = body(patch("/api/employees/" + id, admin, Map.of("salary", 1234.50)));
        assertThat(raised.path("salary").asDouble()).isEqualTo(1234.50);
        assertThat(raised.path("workShift").asText()).as("untouched").isEqualTo("MORNING");
        assertThat(raised.path("departmentId").asInt()).isEqualTo(1);

        JsonNode moved = body(patch("/api/employees/" + id, admin, Map.of("city", "Zarqa")));
        assertThat(moved.path("city").asText()).isEqualTo("Zarqa");
        assertThat(moved.path("street").asText())
                .as("a city-only patch keeps the street").isEqualTo("First St");

        // Moving somebody vacates any post they hold, so it cannot ride along with an edit.
        JsonNode ignored = body(patch("/api/employees/" + id, admin,
                Map.of("departmentId", 2, "workShift", "NIGHT")));
        assertThat(ignored.path("workShift").asText()).isEqualTo("NIGHT");
        assertThat(ignored.path("departmentId").asInt())
                .as("a departmentId in the body is not a transfer").isEqualTo(1);
    }

    @Test
    @DisplayName("an empty patch changes nothing and is not an error")
    void emptyPatchIsANoOp() throws Exception {
        logIn();
        JsonNode before = body(get("/api/employees/2", admin));
        JsonNode after = body(patch("/api/employees/2", admin, Map.of()));
        assertThat(after.path("fullName").asText()).isEqualTo(before.path("fullName").asText());
        assertThat(after.path("salary").asDouble()).isEqualTo(before.path("salary").asDouble());
    }

    @Test
    @DisplayName("employee input is validated before it reaches the database")
    void employeeValidation() throws Exception {
        logIn();
        assertThat(body(post("/api/employees", admin, Map.of(
                "fullName", "Bad " + tag(), "salary", -5, "workShift", "MORNING",
                "departmentId", 1))).path("errors").has("salary")).isTrue();
        assertThat(body(patch("/api/employees/2", admin, Map.of("salary", -1)))
                .path("status").asInt()).isEqualTo(400);
        assertThat(status(post("/api/employees", admin, Map.of(
                "fullName", "Bad " + tag(), "salary", 100, "workShift", "SOMETIME",
                "departmentId", 1)))).isEqualTo(400);
        assertThat(status(post("/api/employees", admin, Map.of(
                "fullName", "Bad " + tag(), "salary", 100, "workShift", "MORNING",
                "departmentId", 999999)))).isEqualTo(404);
    }

    @Test
    @DisplayName("hiring into a department that already has a manager raises no vacancy")
    void hiringWhereThereIsAManager() throws Exception {
        logIn();
        JsonNode hired = body(post("/api/employees", admin, Map.of(
                "fullName", "No Vacancy " + tag(), "salary", 700, "workShift", "EVENING",
                "departmentId", 1)));
        JsonNode vacancy = hired.path("managerVacancy");
        assertThat(vacancy.isNull() || vacancy.isMissingNode())
                .as("department 1 already has one, so nothing to report").isTrue();
    }

    @Test
    @DisplayName("a departing employee is removed, never blocked")
    void employeeRemoval() throws Exception {
        logIn();
        long id = body(post("/api/employees", admin, Map.of(
                "fullName", "Leaver " + tag(), "salary", 700, "workShift", "MORNING",
                "departmentId", 1))).path("employee").path("id").asLong();

        assertThat(status(delete("/api/employees/" + id, admin))).isEqualTo(204);
        assertThat(status(get("/api/employees/" + id, admin))).isEqualTo(404);
        assertThat(status(delete("/api/employees/999999", admin))).isEqualTo(404);
    }

    @Test
    @DisplayName("staff can be listed for one department")
    void employeesByDepartment() throws Exception {
        logIn();
        JsonNode page = body(get("/api/employees?departmentId=1&size=100", admin));
        assertThat(page.path("totalElements").asInt()).isPositive();
        for (JsonNode row : page.path("content")) {
            assertThat(row.path("departmentId").asInt()).isEqualTo(1);
        }
        assertThat(body(get("/api/employees?departmentId=999999", admin))
                .path("totalElements").asInt()).as("unknown department, not an error").isZero();
    }

    // ---------------------------------------------------------------- departments

    @Test
    @DisplayName("departments can be listed by kind, and a warehouse reports its free area")
    void departmentsByType() throws Exception {
        logIn();
        JsonNode warehouses = body(get("/api/departments?type=WAREHOUSE&size=100", staff));
        assertThat(warehouses.path("totalElements").asInt()).isPositive();
        for (JsonNode row : warehouses.path("content")) {
            assertThat(row.path("type").asText()).isEqualTo("WAREHOUSE");
            assertThat(row.path("freeAreaSqm").isNull()).isFalse();
        }

        JsonNode branches = body(get("/api/departments?type=BRANCH&size=100", staff));
        for (JsonNode row : branches.path("content")) {
            assertThat(row.path("freeAreaSqm").isNull())
                    .as("free area belongs to a warehouse").isTrue();
        }
        assertThat(status(get("/api/departments?type=NONSENSE", staff))).isEqualTo(400);
    }

    @Test
    @DisplayName("a manager must be one of the department's own staff")
    void managerMustWorkThere() throws Exception {
        logIn();
        JsonNode outsider = body(patch("/api/departments/1", admin, Map.of("managerId", 3)));
        assertThat(outsider.path("status").asInt()).isEqualTo(400);
        assertThat(outsider.path("detail").asText()).contains("cannot manage");

        // Null vacates, which is a legitimate state rather than a refusal.
        assertThat(body(patch("/api/departments/1", admin,
                java.util.Collections.singletonMap("managerId", null)))
                .path("managerId").isNull()).isTrue();
        body(patch("/api/departments/1", admin, Map.of("managerId", 1)));
    }

    @Test
    @DisplayName("an empty department can be closed")
    void closingAnEmptyDepartment() throws Exception {
        logIn();
        long id = body(post("/api/departments", admin, Map.of(
                "name", "Closable " + tag(), "type", "BRANCH",
                "city", "Amman", "street", "Short St"))).path("id").asLong();
        assertThat(status(delete("/api/departments/" + id, admin))).isEqualTo(204);
        assertThat(status(delete("/api/departments/999999", admin))).isEqualTo(404);
    }
}
