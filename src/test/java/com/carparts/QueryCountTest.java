package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * How many queries a page costs, asserted rather than hoped for.
 *
 * <p>This is the check that has found more defects in this project than any other, and until now
 * it was done by hand: log every statement on a throwaway cluster, hit an endpoint, count. The
 * orders listing once cost about six queries per row; parts cost twelve for a page of ten; stock
 * cost fourteen. Each was a lazy association read during serialization, and each looked perfectly
 * correct in the code.
 *
 * <p><b>What this catches that nothing else does.</b> Dropping a {@code JOIN FETCH} on its own is
 * already loud here: {@code open-in-view} is disabled and controllers map their DTOs outside any
 * transaction, so the lazy association throws and the endpoint returns 500. Half the suite would
 * go red. That case does not need these tests.
 *
 * <p>The case that does is the repair somebody reaches for next. Faced with that
 * {@code LazyInitializationException}, the obvious fix is to make the association {@code EAGER}
 * rather than to add the fetch join back — and it works. The exception goes away, the responses
 * are byte-for-byte what they were, and the page silently costs a query per row forever after.
 *
 * <p>That is not hypothetical: with {@code Part.supplier} switched to {@code EAGER} and the fetch
 * join removed, every other test in this project still passes, and the parts listing goes from
 * two queries to fourteen. These tests are what fails.
 *
 * <p><b>The row count is the test.</b> Three rows cannot distinguish two queries from N+1, which
 * is exactly how the parts N+1 survived its first review: there were three suppliers. Every
 * fixture here creates comfortably more rows than the page size it then asks for, so a per-row
 * query shows up as a number far outside the bound rather than as one or two extra.
 *
 * <p>Counts come from Hibernate's own statistics, which cover precisely the surface that can
 * N+1 — entity loading through JPA. The reports and the order listing read hand-written SQL
 * through {@code JdbcClient}, one statement each by construction, and are not counted here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("what a page costs in queries")
class QueryCountTest extends AbstractWebTest {

    /** Comfortably more rows than any page asked for below, so a per-row query cannot hide. */
    private static final int SEEDED = 12;
    private static final int PAGE = 10;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private String admin;
    private String staff;

    @BeforeEach
    void resetCounters() throws Exception {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        admin = login("layla");
        staff = login("omar");
    }

    /**
     * The statements Hibernate prepared while serving one request.
     *
     * <p>Cleared immediately before, so nothing the fixture did is counted. The request is driven
     * through MockMvc rather than by calling a repository, because the bugs this guards against
     * are not in the query — they happen after it returns, when the response is rendered outside
     * the transaction and a lazy association is touched.
     */
    private long queriesFor(MockHttpServletRequestBuilder request) throws Exception {
        statistics.clear();
        int status = perform(request).getResponse().getStatus();
        assertThat(status).as("the request has to succeed for its cost to mean anything")
                .isEqualTo(200);
        return statistics.getPrepareStatementCount();
    }

    private String suffix() {
        return "QC" + tag();
    }

    // ---------------------------------------------------------------- parts

    @Test
    @DisplayName("a page of parts costs the same whether it holds one supplier or twelve")
    void partsListingIsFlat() throws Exception {
        // A supplier each, deliberately. One shared supplier would be loaded once and cached in
        // the persistence context, and a genuine N+1 would look like a single extra query.
        for (int i = 0; i < SEEDED; i++) {
            long supplier = body(post("/api/suppliers", admin,
                    Map.of("name", "QC Vendor " + suffix() + "-" + i))).path("id").asLong();
            body(post("/api/parts", admin, Map.of(
                    "sku", "QC-" + suffix() + "-" + i, "name", "QC Part " + i,
                    "price", 10.00, "weightKg", 1.0, "reorderLevel", 0,
                    "supplierId", supplier)));
        }

        long queries = queriesFor(get("/api/parts?search=" + suffix() + "&size=" + PAGE, staff));

        // Two: the page and its count. The bound allows a little slack for anything Spring Data
        // adds, but stays far below the SEEDED rows an N+1 would cost.
        assertThat(queries)
                .as("a page of %d parts, each with its own supplier, must not query per row", PAGE)
                .isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("asking for more parts does not cost more queries")
    void partsCostDoesNotGrowWithPageSize() throws Exception {
        for (int i = 0; i < SEEDED; i++) {
            long supplier = body(post("/api/suppliers", admin,
                    Map.of("name", "QC Grow " + suffix() + "-" + i))).path("id").asLong();
            body(post("/api/parts", admin, Map.of(
                    "sku", "QG-" + suffix() + "-" + i, "name", "QC Part " + i,
                    "price", 10.00, "weightKg", 1.0, "reorderLevel", 0, "supplierId", supplier)));
        }

        long small = queriesFor(get("/api/parts?search=" + suffix() + "&size=2", staff));
        long large = queriesFor(get("/api/parts?search=" + suffix() + "&size=12", staff));

        // The sharpest form of the assertion, and the one that needs no magic number: if cost
        // tracks row count at all, six times the rows shows it. A fixed bound can be tuned until
        // it passes; this cannot.
        assertThat(large).as("six times the rows, the same number of queries").isEqualTo(small);
    }

    // ---------------------------------------------------------------- stock

    @Test
    @DisplayName("a warehouse's stock page does not query per shelf")
    void stockListingIsFlat() throws Exception {
        long supplier = body(post("/api/suppliers", admin,
                Map.of("name", "QC Stock Vendor " + suffix()))).path("id").asLong();
        for (int i = 0; i < SEEDED; i++) {
            long part = body(post("/api/parts", admin, Map.of(
                    "sku", "QS-" + suffix() + "-" + i, "name", "QC Stock Part " + i,
                    "price", 10.00, "weightKg", 1.0, "reorderLevel", 0,
                    "supplierId", supplier))).path("id").asLong();
            body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 5)));
        }

        long queries = queriesFor(get("/api/warehouses/3/stock?size=" + PAGE, staff));

        // Every row reports its part's SKU and name, which is a lazy association per row unless
        // the query fetches it. This listing cost fourteen queries once.
        assertThat(queries).as("a page of %d shelves", PAGE).isLessThanOrEqualTo(4);
    }

    // ---------------------------------------------------------------- people and places

    @Test
    @DisplayName("an employee page does not query per employee, nor per department")
    void employeeListingIsFlat() throws Exception {
        for (int i = 0; i < SEEDED; i++) {
            body(post("/api/employees", admin, Map.of(
                    "fullName", "QC Staff " + suffix() + "-" + i, "salary", 700,
                    "workShift", "MORNING", "departmentId", 1)));
        }

        long queries = queriesFor(get("/api/employees?size=" + PAGE, admin));

        // Two associations deep: each employee's department, and that department's manager.
        // A missing fetch on either is a query per row.
        assertThat(queries).as("a page of %d employees", PAGE).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("a department page does not query per manager")
    void departmentListingIsFlat() throws Exception {
        // Each with a manager of its own, since a department without one costs nothing to
        // render and would make the listing look flat whatever the query did.
        for (int i = 0; i < SEEDED; i++) {
            long department = body(post("/api/departments", admin, Map.of(
                    "name", "QC Dept " + suffix() + "-" + i, "type", "BRANCH",
                    "city", "Amman", "street", "St " + i))).path("id").asLong();
            long employee = body(post("/api/employees", admin, Map.of(
                    "fullName", "QC Boss " + suffix() + "-" + i, "salary", 900,
                    "workShift", "MORNING", "departmentId", department)))
                    .path("employee").path("id").asLong();
            body(patch("/api/departments/" + department, admin, Map.of("managerId", employee)));
        }

        long queries = queriesFor(get("/api/departments?size=" + PAGE, staff));

        assertThat(queries).as("a page of %d departments, each with a manager", PAGE)
                .isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("customer and supplier pages cost two queries, having nothing to fetch")
    void flatListingsStayFlat() throws Exception {
        for (int i = 0; i < SEEDED; i++) {
            body(post("/api/customers", admin, Map.of(
                    "name", "QC Buyer " + suffix() + "-" + i,
                    "phoneNumber", "07" + tag() + String.format("%02d", i))));
            body(post("/api/suppliers", admin, Map.of("name", "QC Flat " + suffix() + "-" + i)));
        }

        // No associations to render, so these are the control: if even these grew with row
        // count, the problem would be somewhere other than a missing fetch join.
        assertThat(queriesFor(get("/api/customers?search=" + suffix() + "&size=" + PAGE, staff)))
                .as("customers").isLessThanOrEqualTo(3);
        assertThat(queriesFor(get("/api/suppliers?search=" + suffix() + "&size=" + PAGE, staff)))
                .as("suppliers").isLessThanOrEqualTo(3);
    }

    // ---------------------------------------------------------------- one order

    @Test
    @DisplayName("an order is read whole in one query, lines included")
    void readingAnOrderIsOneQuery() throws Exception {
        long supplier = body(post("/api/suppliers", admin,
                Map.of("name", "QC Order Vendor " + suffix()))).path("id").asLong();
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long part = body(post("/api/parts", admin, Map.of(
                    "sku", "QO-" + suffix() + "-" + i, "name", "QC Order Part " + i,
                    "price", 10.00, "weightKg", 1.0, "reorderLevel", 0,
                    "supplierId", supplier))).path("id").asLong();
            body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 20)));
            lines.add(Map.of("partId", part, "quantity", 1));
        }
        long orderId = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3, "lines", lines)))
                .path("id").asLong();

        long queries = queriesFor(get("/api/orders/" + orderId, staff));

        // Eight lines, each naming its part. This is the endpoint whose sibling — fulfil —
        // returned a 500 after committing, because it loaded the order without its items and
        // then rendered it outside the transaction.
        assertThat(queries).as("an order with eight lines").isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("fulfilling an order does not fall back to a lazy load to answer")
    void fulfilRendersWhatItAlreadyLoaded() throws Exception {
        long supplier = body(post("/api/suppliers", admin,
                Map.of("name", "QC Fulfil Vendor " + suffix()))).path("id").asLong();
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long part = body(post("/api/parts", admin, Map.of(
                    "sku", "QF-" + suffix() + "-" + i, "name", "QC Fulfil Part " + i,
                    "price", 10.00, "weightKg", 1.0, "reorderLevel", 0,
                    "supplierId", supplier))).path("id").asLong();
            body(post("/api/warehouses/3/stock", staff, Map.of("partId", part, "quantity", 20)));
            lines.add(Map.of("partId", part, "quantity", 1));
        }
        long orderId = body(post("/api/orders", staff, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3, "lines", lines)))
                .path("id").asLong();

        // The write path, which is where the 500 was: it committed, then failed while
        // rendering. A count here is a proxy for "everything the response needs was fetched".
        long queries = queriesFor(post("/api/orders/" + orderId + "/fulfil", staff, null));

        assertThat(queries).as("a fulfil with six lines").isLessThanOrEqualTo(8);
    }

    // ---------------------------------------------------------------- the counter itself

    @Test
    @DisplayName("the counter is actually counting")
    void theCounterWorks() throws Exception {
        // Every assertion above is an upper bound, and an upper bound is satisfied by zero. If
        // statistics were disabled — one line in application-test.yml — every test in this class
        // would pass while measuring nothing at all. This is the one that would fail.
        assertThat(statistics.isStatisticsEnabled())
                .as("hibernate.generate_statistics must be on in the test profile").isTrue();

        long queries = queriesFor(get("/api/parts?size=5", staff));
        assertThat(queries).as("a real listing has to cost at least one query").isPositive();
    }

    @Test
    @DisplayName("a single part is one query, not one plus its supplier")
    void readingOnePartIsOneQuery() throws Exception {
        long supplier = body(post("/api/suppliers", admin,
                Map.of("name", "QC One Vendor " + suffix()))).path("id").asLong();
        JsonNode part = body(post("/api/parts", admin, Map.of(
                "sku", "Q1-" + suffix(), "name", "QC One Part", "price", 10.00,
                "weightKg", 1.0, "reorderLevel", 0, "supplierId", supplier)));

        assertThat(queriesFor(get("/api/parts/" + part.path("id").asLong(), staff)))
                .as("the response names the supplier, so it must be fetched with the part")
                .isLessThanOrEqualTo(2);
    }
}
