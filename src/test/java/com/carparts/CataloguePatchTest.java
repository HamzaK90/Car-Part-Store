package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Changing one field of a part without disturbing the rest.
 *
 * <p>A PATCH has one characteristic failure: it applies the fields it was given and quietly
 * clears the ones it was not. Every optional field is a separate chance to get that wrong, and
 * the mistake is invisible in the response to the request that caused it — the part comes back
 * looking exactly as asked, and the loss only surfaces when somebody reads the record later.
 *
 * <p>So each field is patched alone here, and every <em>other</em> field checked afterwards.
 */
@DisplayName("patching a part")
class CataloguePatchTest extends AbstractWebTest {

    private String admin;

    private record Fixture(long partId, long supplierId, String sku) {}

    /** A part with every optional field filled in, so an erasure has something to erase. */
    private Fixture complete() throws Exception {
        admin = login("layla");
        long supplier = body(post("/api/suppliers", admin,
                Map.of("name", "Patch Vendor " + tag()))).path("id").asLong();
        String sku = "PC-" + tag();
        long part = body(post("/api/parts", admin, Map.of(
                "sku", sku, "name", "Original Name", "price", 50.00, "weightKg", 2.5,
                "reorderLevel", 7, "description", "Original description",
                "manufacturingPlace", "Nagoya", "supplierId", supplier))).path("id").asLong();
        return new Fixture(part, supplier, sku);
    }

    private JsonNode patchPart(long id, Map<String, Object> body) throws Exception {
        return body(patch("/api/parts/" + id, admin, body));
    }

    @Test
    @DisplayName("each field can be changed on its own, and changes nothing else")
    void oneFieldAtATime() throws Exception {
        Fixture f = complete();

        // Every optional field in turn. A patch of one must leave the other six as they were,
        // and the assertion after each is what would catch a setter called with null.
        record Change(String field, Object value) {}
        List<Change> changes = List.of(
                new Change("name", "New Name"),
                new Change("price", 99.99),
                new Change("weightKg", 3.75),
                new Change("reorderLevel", 12),
                new Change("description", "New description"),
                new Change("manufacturingPlace", "Osaka"));

        for (Change change : changes) {
            JsonNode after = patchPart(f.partId(), Map.of(change.field(), change.value()));

            assertThat(after.path(change.field()).asText())
                    .as("%s was applied", change.field())
                    .isEqualTo(String.valueOf(change.value()));

            // Nothing that was set may have become null along the way.
            for (String other : List.of("name", "price", "weightKg", "reorderLevel",
                    "description", "manufacturingPlace", "sku", "supplierId")) {
                assertThat(after.path(other).isNull())
                        .as("patching %s must not clear %s", change.field(), other)
                        .isFalse();
            }
            assertThat(after.path("sku").asText()).as("the SKU is not patchable").isEqualTo(f.sku());
        }
    }

    @Test
    @DisplayName("an empty patch is accepted and changes nothing at all")
    void emptyPatch() throws Exception {
        Fixture f = complete();
        JsonNode before = body(get("/api/parts/" + f.partId(), admin));
        JsonNode after = patchPart(f.partId(), Map.of());

        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("an explicit null is left alone, not written through")
    void explicitNullDoesNotErase() throws Exception {
        Fixture f = complete();

        // A JSON null and an absent field are indistinguishable to the service, which reads
        // null as "not given". Worth pinning, because it is also the reason there is no way to
        // clear a description through this endpoint — a real limitation, deliberately chosen
        // over the alternative where a partial patch silently wipes fields.
        JsonNode after = patchPart(f.partId(),
                Collections.singletonMap("description", null));

        assertThat(after.path("description").asText()).isEqualTo("Original description");
    }

    @Test
    @DisplayName("a part can be moved to another supplier")
    void supplierCanChange() throws Exception {
        Fixture f = complete();
        long other = body(post("/api/suppliers", admin,
                Map.of("name", "Other Vendor " + tag()))).path("id").asLong();

        JsonNode moved = patchPart(f.partId(), Map.of("supplierId", other));
        assertThat(moved.path("supplierId").asLong()).isEqualTo(other);
        assertThat(moved.path("name").asText()).as("and nothing else moved").isEqualTo("Original Name");

        assertThat(patchPart(f.partId(), Map.of("supplierId", 999999)).path("status").asInt())
                .as("but not to one that does not exist").isEqualTo(404);
        assertThat(body(get("/api/parts/" + f.partId(), admin)).path("supplierId").asLong())
                .as("and the failed move left it where it was").isEqualTo(other);
    }

    @Test
    @DisplayName("a patch that breaks a rule is refused and applies nothing")
    void invalidPatchAppliesNothing() throws Exception {
        Fixture f = complete();

        // A valid field alongside an invalid one. Validation runs on the whole body before the
        // service sees any of it, so the good half must not land either.
        assertThat(body(patch("/api/parts/" + f.partId(), admin,
                Map.of("name", "Should Not Stick", "price", -1))).path("status").asInt())
                .isEqualTo(400);

        assertThat(body(get("/api/parts/" + f.partId(), admin)).path("name").asText())
                .as("the name in the same rejected body was not applied")
                .isEqualTo("Original Name");
    }

    @Test
    @DisplayName("a part still on an order cannot be deleted")
    void deletionIsRefusedWhileSold() throws Exception {
        Fixture f = complete();
        String staff = login("omar");
        body(post("/api/warehouses/3/stock", staff,
                Map.of("partId", f.partId(), "quantity", 5)));
        body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", f.partId(), "quantity", 1)))));

        JsonNode refused = body(delete("/api/parts/" + f.partId(), admin));
        assertThat(refused.path("status").asInt())
                .as("an order that named this part would lose what it was for").isEqualTo(409);
        assertThat(status(delete("/api/parts/999999", admin))).isEqualTo(404);
    }

    @Test
    @DisplayName("correcting a fitment is guarded the same way as creating one")
    void fitmentCorrectionIsGuarded() throws Exception {
        Fixture f = complete();
        String model = "Model" + tag();
        body(post("/api/parts/" + f.partId() + "/fitments", admin, Map.of(
                "make", "Toyota", "model", model, "yearFrom", 2015, "yearTo", 2020)));

        String query = "?make=Toyota&model=" + model + "&yearFrom=2015";

        assertThat(status(patch("/api/parts/" + f.partId() + "/fitments" + query, admin,
                Map.of("yearTo", 2010))))
                .as("a correction cannot end the run before it began").isEqualTo(400);

        assertThat(status(patch("/api/parts/" + f.partId() + "/fitments?make=Toyota&model="
                + model + "&yearFrom=1999", admin, Map.of("yearTo", 2020))))
                .as("nor correct a fitment that was never recorded").isEqualTo(404);

        assertThat(status(delete("/api/parts/" + f.partId() + "/fitments?make=Toyota&model="
                + model + "&yearFrom=1999", admin)))
                .as("and removing one that is not there is a 404, not a silent success")
                .isEqualTo(404);
    }
}
