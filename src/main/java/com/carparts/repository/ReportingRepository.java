package com.carparts.repository;

import com.carparts.domain.DepartmentType;
import com.carparts.domain.OrderStatus;
import com.carparts.domain.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only reporting, in SQL rather than JPA.
 *
 * <p>Every query here reads a view. The views already express the aggregation, so going through
 * entities would mean either re-deriving the same sums in Java or fetching far more rows than
 * the answer needs. {@code JdbcClient} maps a view row straight onto a record.
 *
 * <p>Nothing in this class returns an entity. These are read models — flat, immutable, and
 * shaped for the endpoint that asks for them.
 */
@Repository
public class ReportingRepository {

    private final JdbcClient jdbc;

    public ReportingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Row types
    // ---------------------------------------------------------------------

    /** A department and how many people work in it. */
    public record DepartmentHeadcount(
            Long departmentId, String name, DepartmentType type, Long managerId, long headcount) {}

    /** A department with no manager, and how many of its staff could be promoted. */
    public record DepartmentWithoutManager(
            Long departmentId, String name, DepartmentType type, long eligibleEmployees) {}

    /** A stock row that has fallen below its part's reorder level. */
    public record LowStock(
            Long warehouseId,
            String warehouseName,
            Long partId,
            String sku,
            String partName,
            BigDecimal price,
            String supplierName,
            int quantity,
            int reorderLevel,
            int shortfall) {}

    /** An order and what it came to, from the prices captured at sale. */
    public record OrderTotal(
            Long orderId,
            Long customerId,
            Long branchId,
            Long warehouseId,
            LocalDate orderDate,
            OrderStatus status,
            long lineCount,
            long unitCount,
            BigDecimal totalAmount) {}

    /**
     * One row of an order list: everything a listing shows, and nothing it does not.
     *
     * <p>Deliberately without the lines. A list needs to say what an order is worth, not itemise
     * it — and fetching lines for every row is what turns one request into hundreds of queries.
     * {@code GET /api/orders/{id}} is where the detail lives.
     */
    public record OrderSummary(
            Long orderId,
            Long customerId,
            String customerName,
            Long employeeId,
            String employeeName,
            Long branchId,
            String branchName,
            Long warehouseId,
            String warehouseName,
            LocalDate orderDate,
            OrderStatus status,
            long lineCount,
            long unitCount,
            BigDecimal totalAmount) {}

    /** Lifetime spend for one customer. Cancelled orders are excluded. */
    public record CustomerRevenue(
            Long customerId, String name, String phoneNumber, long orderCount, BigDecimal revenue) {}

    /**
     * Who a login belongs to and what they may do.
     *
     * <p>{@code isManager} is derived by the view from {@code department.manager_id}, not stored
     * on the account, so promoting somebody takes effect without touching their login.
     */
    public record UserIdentity(
            Long userId,
            String username,
            UserRole role,
            boolean enabled,
            Long employeeId,
            String fullName,
            Long departmentId,
            String departmentName,
            DepartmentType departmentType,
            boolean isManager) {}

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    public List<DepartmentHeadcount> departmentHeadcount() {
        return jdbc.sql("""
                SELECT department_id, name, type, manager_id, headcount
                FROM v_department_headcount
                ORDER BY name
                """)
                .query(DepartmentHeadcount.class)
                .list();
    }

    /** The standing vacancy alert an admin works through. */
    public List<DepartmentWithoutManager> departmentsWithoutManager() {
        return jdbc.sql("""
                SELECT department_id, name, type, eligible_employees
                FROM v_department_without_manager
                ORDER BY name
                """)
                .query(DepartmentWithoutManager.class)
                .list();
    }

    /** True when this department has nobody in charge — the check behind the promotion prompt. */
    public boolean hasNoManager(Long departmentId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM v_department_without_manager WHERE department_id = ?)")
                .param(departmentId)
                .query(Boolean.class)
                .single();
    }

    /** Worst shortfalls first, so the top of the list is what to reorder now. */
    public List<LowStock> lowStock() {
        return jdbc.sql("""
                SELECT warehouse_id, warehouse_name, part_id, sku, part_name,
                       price, supplier_name, quantity, reorder_level, shortfall
                FROM v_low_stock
                ORDER BY shortfall DESC, sku
                """)
                .query(LowStock.class)
                .list();
    }

    public Optional<OrderTotal> orderTotal(Long orderId) {
        return jdbc.sql("""
                SELECT order_id, customer_id, branch_id, warehouse_id,
                       order_date, status, line_count, unit_count, total_amount
                FROM v_order_total
                WHERE order_id = ?
                """)
                .param(orderId)
                .query(OrderTotal.class)
                .optional();
    }

    /**
     * Revenue per customer, best customer first.
     *
     * <p>Reads {@code v_customer_revenue}. The aggregation lives in the view with every other
     * derived value, so a second caller cannot arrive at a different total; only the ordering,
     * which is a presentation choice rather than a fact, is applied here.
     *
     * <p>This is revenue the shop earned, not money the customer holds or owes — see the note
     * on the view.
     */
    public List<CustomerRevenue> revenueByCustomer() {
        return jdbc.sql("""
                SELECT customer_id, name, phone_number, order_count, revenue
                FROM v_customer_revenue
                ORDER BY revenue DESC, name
                """)
                .query(CustomerRevenue.class)
                .list();
    }

    /**
     * A page of order summaries, in two queries regardless of page size.
     *
     * <p>Written here rather than mapped through JPA for a measured reason. Building the list
     * from entities cost roughly six statements per row — customer, employee, branch, warehouse,
     * the lines, and a part for each line — so a page of twenty issued about 120 queries and the
     * maximum page around 600. This reads {@code v_order_total}, which has already done the
     * aggregation, and joins only for the names a listing displays.
     *
     * <p>Every filter is optional. An absent one widens rather than arriving as null: PostgreSQL
     * type-checks the whole predicate even where it short-circuits, and a null enum has nothing
     * to infer a type from.
     */
    public List<OrderSummary> orderSummaries(OrderFilter filter, OrderSort sort,
                                             int limit, long offset) {
        return bind(jdbc.sql(ORDER_SUMMARY_SELECT + ORDER_SUMMARY_WHERE
                        + "ORDER BY " + sort.sql() + "\nLIMIT :limit OFFSET :offset\n"), filter)
                .param("limit", limit)
                .param("offset", offset)
                .query(OrderSummary.class)
                .list();
    }

    /** How many orders match the same filters, for the page metadata. */
    public long countOrders(OrderFilter filter) {
        return bind(jdbc.sql("SELECT COUNT(*) FROM v_order_total t "
                + "JOIN customer_order o ON o.order_id = t.order_id " + ORDER_SUMMARY_WHERE), filter)
                .query(Long.class)
                .single();
    }

    /**
     * The filters an order listing accepts.
     *
     * <p>Gathered into one object rather than passed as eight parameters, so the page query and
     * its count cannot drift apart and a new filter is added in one place.
     *
     * @param statuses which statuses to include; empty or null means all of them
     * @param partId only orders containing this part — the question asked when a batch turns out
     *     to be faulty and you need to know who received it
     * @param minTotal lower bound on the order's value, inclusive
     */
    public record OrderFilter(
            List<OrderStatus> statuses,
            Long branchId,
            Long warehouseId,
            Long customerId,
            Long employeeId,
            Long partId,
            LocalDate from,
            LocalDate to,
            BigDecimal minTotal,
            BigDecimal maxTotal) {}

    /**
     * The orderings a caller may ask for.
     *
     * <p>A closed set rather than a free-text {@code sort} parameter. Interpolating a column name
     * from a query string into SQL is how injection happens, and there is no legitimate need to
     * sort an order list by an arbitrary column.
     */
    public enum OrderSort {
        /** Most recent first. The default, and what a browsing screen wants. */
        NEWEST("t.order_date DESC, t.order_id DESC"),
        /**
         * Oldest first. What a picking queue wants: fulfilment is FIFO, and a customer who
         * ordered on Monday should not wait behind one who ordered this morning.
         */
        OLDEST("t.order_date ASC, t.order_id ASC"),
        LARGEST("t.total_amount DESC, t.order_id DESC"),
        SMALLEST("t.total_amount ASC, t.order_id ASC");

        private final String sql;

        OrderSort(String sql) {
            this.sql = sql;
        }

        String sql() {
            return sql;
        }
    }

    private org.springframework.jdbc.core.simple.JdbcClient.StatementSpec bind(
            org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec, OrderFilter f) {
        List<OrderStatus> statuses = f.statuses() == null || f.statuses().isEmpty()
                ? List.of(OrderStatus.values())
                : f.statuses();
        return spec
                .param("statuses", statuses.stream().map(Enum::name).toList())
                .param("branchId", f.branchId())
                .param("warehouseId", f.warehouseId())
                .param("customerId", f.customerId())
                .param("employeeId", f.employeeId())
                .param("partId", f.partId())
                .param("from", f.from())
                .param("to", f.to())
                .param("minTotal", f.minTotal())
                .param("maxTotal", f.maxTotal());
    }

    private static final String ORDER_SUMMARY_SELECT = """
            SELECT t.order_id, t.customer_id, c.name AS customer_name,
                   o.employee_id, e.full_name AS employee_name,
                   t.branch_id,    b.name AS branch_name,
                   t.warehouse_id, w.name AS warehouse_name,
                   t.order_date, t.status, t.line_count, t.unit_count, t.total_amount
            FROM v_order_total t
            JOIN customer_order o ON o.order_id      = t.order_id
            JOIN customer      c ON c.customer_id    = t.customer_id
            LEFT JOIN employee e ON e.employee_id    = o.employee_id
            JOIN department    b ON b.department_id  = t.branch_id
            JOIN department    w ON w.department_id  = t.warehouse_id
            """;

    /**
     * The filters, shared by the page query and its count so the two can never disagree.
     *
     * <p><b>Every parameter is cast, including the ones only tested for null.</b> That is not
     * belt-and-braces. In {@code ? IS NULL} the placeholder stands alone with nothing to infer a
     * type from, and PostgreSQL refuses the statement outright with <em>could not determine data
     * type of parameter $1</em> — before any row is examined and regardless of the value passed.
     *
     * <p>Hibernate hid this in the JPA version by declaring parameter types itself. Hand-written
     * SQL has no such help, so the cast is the type declaration.
     */
    private static final String ORDER_SUMMARY_WHERE = """
            WHERE CAST(t.status AS text) IN (:statuses)
              AND (CAST(:branchId    AS bigint) IS NULL OR t.branch_id    = CAST(:branchId    AS bigint))
              AND (CAST(:warehouseId AS bigint) IS NULL OR t.warehouse_id = CAST(:warehouseId AS bigint))
              AND (CAST(:customerId  AS bigint) IS NULL OR t.customer_id  = CAST(:customerId  AS bigint))
              AND (CAST(:employeeId  AS bigint) IS NULL OR o.employee_id  = CAST(:employeeId  AS bigint))
              AND (CAST(:from        AS date)   IS NULL OR t.order_date  >= CAST(:from AS date))
              AND (CAST(:to          AS date)   IS NULL OR t.order_date  <= CAST(:to   AS date))
              AND (CAST(:minTotal AS numeric) IS NULL OR t.total_amount >= CAST(:minTotal AS numeric))
              AND (CAST(:maxTotal AS numeric) IS NULL OR t.total_amount <= CAST(:maxTotal AS numeric))
              AND (CAST(:partId AS bigint) IS NULL OR EXISTS (
                       SELECT 1 FROM order_item oi
                       WHERE oi.order_id = t.order_id
                         AND oi.part_id  = CAST(:partId AS bigint)))
            """;

    /**
     * Everything the login endpoint needs, in one row.
     *
     * <p>Used instead of loading {@code AppUser} and walking to employee and department: those
     * are lazy associations and would cost three round trips for a fact the view already joins.
     */
    public Optional<UserIdentity> findIdentityByUsername(String username) {
        return jdbc.sql("""
                SELECT user_id, username, role, enabled, employee_id, full_name,
                       department_id, department_name, department_type, is_manager
                FROM v_user_identity
                WHERE username = ?
                """)
                .param(username)
                .query(UserIdentity.class)
                .optional();
    }
}
