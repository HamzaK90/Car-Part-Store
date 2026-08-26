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
