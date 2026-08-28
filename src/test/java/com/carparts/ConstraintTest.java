package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.postgresql.util.PSQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the database refuses, whatever writes to it.
 *
 * <p>These go through raw SQL on purpose. Every rule here is also checked in Java, with a
 * friendlier message — but the Java check is a courtesy and the constraint is the guarantee. A
 * test that went through the service layer would prove the courtesy works and say nothing about
 * what happens when a migration, a fix-up script, or a future code path writes the row instead.
 *
 * <p>Transactional so each attempt rolls back. That is safe here because none of these
 * assertions depends on a lock being contended; the one that does lives in
 * {@code ConcurrencyTest} and is deliberately not transactional.
 */
@Transactional
@DisplayName("constraints the database enforces itself")
class ConstraintTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    private void exec(String sql) {
        jdbc.sql(sql).update();
    }

    /**
     * Asserts the statement is refused, and that the named constraint is what refused it.
     *
     * <p>The name is read from the error's metadata, not from its text. A CHECK or a foreign
     * key mentions itself in the message, but a constraint trigger raises a sentence written
     * for a person — <em>"order 1001 is already FULFILLED; it cannot become CANCELLED"</em> —
     * and carries the name in the {@code CONSTRAINT} field instead. Matching on the message
     * would therefore pass for one kind of rule and fail for the other, while proving less.
     *
     * <p>This also tests something the application depends on: {@code ApiExceptionHandler}
     * translates a violation by looking up exactly this name, so a trigger that forgot to set
     * {@code CONSTRAINT} would leave a caller with the generic 409 and no way to act on it.
     */
    private void refusedBy(String constraint, String sql) {
        Throwable thrown = catchThrowable(() -> exec(sql));
        assertThat(thrown).as("expected %s to refuse this, but it succeeded", constraint)
                .isNotNull();
        assertThat(constraintNameOf(thrown))
                .as("refused by the wrong rule; the message was: %s", thrown.getMessage())
                .isEqualTo(constraint);
    }

    /**
     * As {@link #refusedBy}, for a rule that is {@code DEFERRABLE INITIALLY DEFERRED}.
     *
     * <p>Two of them are, and inherently so rather than incidentally: a department may name a
     * manager whose employee row is written later in the same transaction, and an order header
     * necessarily exists before the lines that reference it. Both questions can only be asked
     * once the transaction has finished speaking.
     *
     * <p>Which means the statement <em>succeeds</em>. Nothing is refused until COMMIT — and
     * these tests roll back, so a naive assertion here passes vacuously while the rule is never
     * exercised. Forcing the check with {@code SET CONSTRAINTS ALL IMMEDIATE} is what makes the
     * deferral visible without committing anything.
     */
    private void refusedAtCommitBy(String constraint, String sql) {
        assertThatCode(() -> exec(sql))
                .as("a deferred rule should not refuse the statement itself")
                .doesNotThrowAnyException();

        Throwable thrown = catchThrowable(() -> jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update());
        assertThat(thrown).as("expected %s to refuse this at commit", constraint).isNotNull();
        assertThat(constraintNameOf(thrown))
                .as("refused by the wrong rule; the message was: %s", thrown.getMessage())
                .isEqualTo(constraint);
    }

    /**
     * Digs the constraint name out of the exception chain.
     *
     * <p>Reaching for {@code PSQLException} is deliberate here and deliberately avoided in
     * production: {@code ApiExceptionHandler} asks Hibernate instead, so the web layer does not
     * put a specific database on its compile classpath. This class is entirely about what
     * PostgreSQL does, so knowing which database it is talking to costs nothing.
     */
    private static String constraintNameOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return psql.getServerErrorMessage().getConstraint();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- column rules

    @Test
    @DisplayName("a negative salary is refused — acceptance criterion 6")
    void negativeSalary() {
        refusedBy("ck_employee_salary", """
                INSERT INTO employee (full_name, salary, work_shift, department_id)
                VALUES ('Underpaid', -5, 'MORNING', 1)
                """);
    }

    @Test
    @DisplayName("stock cannot go negative")
    void negativeStock() {
        refusedBy("ck_warehouse_stock_quantity",
                "UPDATE warehouse_stock SET quantity = -1 WHERE warehouse_id = 3");
    }

    @Test
    @DisplayName("an order line of zero is refused")
    void zeroQuantityLine() {
        refusedBy("ck_order_item_quantity",
                "UPDATE order_item SET quantity = 0 WHERE order_id = 1001");
    }

    @Test
    @DisplayName("a negative price is refused")
    void negativePrice() {
        refusedBy("ck_part_price", "UPDATE part SET price = -1 WHERE part_id = 1");
    }

    @Test
    @DisplayName("a fitment cannot end before it begins")
    void invertedFitmentYears() {
        refusedBy("ck_car_fitment_year_range",
                "UPDATE car_fitment SET year_to = 1990 WHERE year_from > 1990");
    }

    @Test
    @DisplayName("a password column will not hold a plaintext password")
    void plaintextPassword() {
        refusedBy("ck_app_user_password_hashed",
                "UPDATE app_user SET password_hash = 'hunter2' WHERE user_id = 1");
    }

    // ---------------------------------------------------------------- subtype disjointness

    @Test
    @DisplayName("a department cannot be both a branch and a warehouse")
    void bothSubtypes() {
        // The composite foreign key (department_id, type) is what enforces this, without a
        // trigger: department 1 is a BRANCH, so the warehouse row cannot claim it.
        assertThatThrownBy(() -> exec(
                "INSERT INTO warehouse (department_id, free_area_sqm) VALUES (1, 100)"))
                .hasMessageContaining("fk_warehouse_department");
    }

    @Test
    @DisplayName("a branch cannot hold stock")
    void branchHoldingStock() {
        // warehouse_stock references warehouse, not department, so a branch id simply is not
        // a warehouse as far as the foreign key is concerned.
        assertThatThrownBy(() -> exec(
                "INSERT INTO warehouse_stock (warehouse_id, part_id, quantity) VALUES (1, 1, 5)"))
                .hasMessageContaining("fk_warehouse_stock_warehouse");
    }

    @Test
    @DisplayName("a warehouse cannot be where a sale happened")
    void warehouseAsSalesLocation() {
        assertThatThrownBy(() -> exec("""
                INSERT INTO customer_order (customer_id, branch_id, warehouse_id, order_date, status)
                VALUES (1, 3, 3, CURRENT_DATE, 'PLACED')
                """))
                .hasMessageContaining("fk_customer_order_branch");
    }

    // ---------------------------------------------------------------- cross-table rules

    @Test
    @DisplayName("an order's handler must work at the branch that took it — criterion 5")
    void handlerFromAnotherDepartment() {
        // Employee 6 works at a warehouse; branch 1 took the order.
        refusedBy("ct_order_employee_at_branch", """
                INSERT INTO customer_order (customer_id, employee_id, branch_id, warehouse_id,
                                            order_date, status)
                VALUES (1, 6, 1, 3, CURRENT_DATE, 'PLACED')
                """);
    }

    @Test
    @DisplayName("an outsider cannot be named a department's manager")
    void outsiderAsManager() {
        // Employee 1 works at department 1, so department 2 may not name them.
        refusedAtCommitBy("ct_department_manager_membership",
                "UPDATE department SET manager_id = 1 WHERE department_id = 2");
    }

    @Test
    @DisplayName("only a PLACED order may be fulfilled or cancelled")
    void statusTransition() {
        // Order 1001 is already FULFILLED.
        refusedBy("ct_order_status_transition",
                "UPDATE customer_order SET status = 'CANCELLED' WHERE order_id = 1001");
    }

    @Test
    @DisplayName("an order must keep at least one line, checked at commit")
    void orderWithoutLines() {
        refusedAtCommitBy("ct_order_has_lines", "DELETE FROM order_item WHERE order_id = 1001");
    }

    @Test
    @DisplayName("a manager who transfers away vacates the post rather than blocking the move")
    void transferVacatesPost() {
        // Employee 1 manages department 1. Moving them is allowed, and the trigger clears the
        // post — losing a manager is an ordinary event; a manager who works elsewhere is not.
        assertThatCode(() -> exec("UPDATE employee SET department_id = 2 WHERE employee_id = 1"))
                .doesNotThrowAnyException();
        Long manager = jdbc.sql("SELECT manager_id FROM department WHERE department_id = 1")
                .query(Long.class).optional().orElse(null);
        assertThat(manager)
                .as("tg_employee_transfer_vacates_post should have emptied the post")
                .isNull();
    }

    // ---------------------------------------------------------------- uniqueness

    @Test
    @DisplayName("a SKU cannot be issued twice — invoices print it")
    void duplicateSku() {
        refusedBy("uq_part_sku",
                "UPDATE part SET sku = (SELECT sku FROM part WHERE part_id = 2) WHERE part_id = 1");
    }

    @Test
    @DisplayName("two customers cannot share a phone number")
    void duplicatePhone() {
        refusedBy("uq_customer_phone", """
                UPDATE customer SET phone_number =
                    (SELECT phone_number FROM customer WHERE customer_id = 2)
                WHERE customer_id = 1
                """);
    }

    @Test
    @DisplayName("but any number of customers may have no email")
    void manyNullEmails() {
        // uq_customer_email is a plain unique index, and PostgreSQL allows repeated NULLs under
        // one. This is why blank input is normalised to NULL rather than stored as an empty
        // string: two customers with '' would collide, which is not what "no email" means.
        assertThatCode(() -> exec("UPDATE customer SET email = NULL"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one person cannot be behind two logins")
    void oneAccountPerEmployee() {
        refusedBy("uq_app_user_employee",
                "INSERT INTO app_user (username, password_hash, employee_id) VALUES "
                        + "('second', '$2a$04$bALtfA.5bLPEwXI0maxfmueBLQOpfU6gWcraTPIF9SqBBRCDzhDjC', 1)");
    }

    // ---------------------------------------------------------------- referential integrity

    @Test
    @DisplayName("a part that has been sold cannot be deleted")
    void deletingSoldPart() {
        // The stock rows go first, deliberately. Every seeded part is stocked as well as sold,
        // so fk_warehouse_stock_part would otherwise refuse the delete before the order lines
        // were ever consulted — and this test would pass while proving nothing about them.
        exec("DELETE FROM warehouse_stock WHERE part_id = 1");
        refusedBy("fk_order_item_part", "DELETE FROM part WHERE part_id = 1");
    }

    @Test
    @DisplayName("a department with staff cannot be closed")
    void deletingStaffedDepartment() {
        refusedBy("fk_employee_department", "DELETE FROM department WHERE department_id = 1");
    }

    @Test
    @DisplayName("but a person can always leave, and their posts are vacated")
    void deletingEmployeeIsAllowed() {
        // All three foreign keys pointing at employee are ON DELETE SET NULL. A leaver must not
        // be undeletable because they once served a customer.
        assertThatCode(() -> exec("DELETE FROM employee WHERE employee_id = 2"))
                .doesNotThrowAnyException();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("deleting an employee clears them from the orders they handled")
    void deletingEmployeeClearsOrders() {
        long handled = jdbc.sql("SELECT COUNT(*) FROM customer_order WHERE employee_id = 2")
                .query(Long.class).single();
        assertThat(handled)
                .as("fixture: employee 2 should handle at least one seeded order")
                .isPositive();

        exec("DELETE FROM employee WHERE employee_id = 2");

        long orphaned = jdbc.sql("SELECT COUNT(*) FROM customer_order WHERE employee_id = 2")
                .query(Long.class).single();
        long surviving = jdbc.sql(
                "SELECT COUNT(*) FROM customer_order WHERE order_id BETWEEN 1001 AND 1005")
                .query(Long.class).single();
        assertThat(orphaned).isZero();
        assertThat(surviving)
                .as("the orders themselves must survive; only the handler is cleared")
                .isEqualTo(5);
    }
}
