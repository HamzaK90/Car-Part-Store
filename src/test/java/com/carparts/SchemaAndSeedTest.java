package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The foundation everything else stands on: the real migrations ran, the mappings match, and
 * the seed data is there.
 *
 * <p>If this fails, nothing below it is worth reading. It exists so that a broken schema or a
 * drifted entity is reported once, plainly, instead of as a hundred confusing failures.
 */
@DisplayName("schema and seed data")
class SchemaAndSeedTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    private List<String> names(String sql) {
        return jdbc.sql(sql).query(String.class).list();
    }

    @Test
    @DisplayName("the context loads, which means every mapping validated against the schema")
    void contextLoads() {
        // Reaching this method at all is the assertion: ddl-auto is `validate`, so Spring would
        // have failed to start if any entity disagreed with the table Flyway built. The query
        // below just proves the datasource is the embedded server and not something inherited.
        String database = jdbc.sql("SELECT current_database()").query(String.class).single();
        assertThat(database).isEqualTo("postgres");
    }

    @Test
    @DisplayName("all six migrations applied, in order and without repair")
    void migrationsApplied() {
        List<String> versions = names(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank");
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    @DisplayName("the twelve tables exist")
    void tables() {
        assertThat(names("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """))
                .containsExactlyInAnyOrder("app_user", "branch", "car_fitment", "customer",
                        "customer_order", "department", "employee", "order_item", "part",
                        "supplier", "warehouse", "warehouse_stock");
    }

    @Test
    @DisplayName("the six views exist — derived values are never stored")
    void views() {
        assertThat(names("""
                SELECT table_name FROM information_schema.views
                WHERE table_schema = 'public' ORDER BY table_name
                """))
                .containsExactlyInAnyOrder("v_customer_revenue", "v_department_headcount",
                        "v_department_without_manager", "v_low_stock", "v_order_total",
                        "v_user_identity");
    }

    @Test
    @DisplayName("the cross-table rules are constraint triggers, not application code")
    void constraintTriggers() {
        assertThat(names("""
                SELECT tgname FROM pg_trigger
                WHERE NOT tgisinternal AND tgname LIKE 'ct_%' OR tgname LIKE 'tg_%'
                ORDER BY tgname
                """))
                .contains("ct_department_manager_membership", "ct_order_employee_at_branch",
                        "ct_order_has_lines", "ct_order_status_transition",
                        "tg_employee_transfer_vacates_post");
    }

    @Test
    @DisplayName("V6 seeded the demo business")
    void seedData() {
        // Counted by the ids V6 assigns explicitly, not as table totals. Tests that drive the
        // API commit rows, and a total would then depend on which classes had already run —
        // the assertion would fail for a reason that has nothing to do with the seed.
        assertThat(seeded("department", "department_id <= 4")).isEqualTo(4);
        assertThat(seeded("employee", "employee_id <= 8")).isEqualTo(8);
        assertThat(seeded("customer", "customer_id <= 4")).isEqualTo(4);
        assertThat(seeded("supplier", "supplier_id <= 3")).isEqualTo(3);
        assertThat(seeded("part", "part_id <= 10")).isEqualTo(10);
        assertThat(seeded("customer_order", "order_id BETWEEN 1001 AND 1005")).isEqualTo(5);
        assertThat(seeded("app_user", "user_id <= 5")).isEqualTo(5);
    }

    private long seeded(String table, String where) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + where)
                .query(Long.class).single();
    }

    @Test
    @DisplayName("every seeded password is a BCrypt digest, never a password")
    void passwordsAreHashed() {
        // ck_app_user_password_hashed enforces this however the row is written. Asserting it
        // here is about the seed itself: a placeholder that failed to resolve, or a plaintext
        // value slipped in, would be caught by the migration — this proves the migration ran
        // with a real digest rather than something that merely looked like one.
        List<String> hashes = names("SELECT password_hash FROM app_user");
        assertThat(hashes).isNotEmpty().allSatisfy(hash ->
                assertThat(hash).matches("^\\$2[aby]\\$\\d{2}\\$.{53}$"));
    }

    @Test
    @DisplayName("the demo dataset is the one the tests expect to find")
    void knownFixtures() {
        // Every other test leans on these. Naming them once means a changed seed breaks here,
        // with an obvious message, instead of somewhere that looks unrelated.
        assertThat(one("SELECT name FROM department WHERE department_id = 1"))
                .isEqualTo("Downtown Branch");
        assertThat(one("SELECT type::text FROM department WHERE department_id = 3"))
                .isEqualTo("WAREHOUSE");
        assertThat(one("SELECT full_name FROM employee WHERE employee_id = 2"))
                .isEqualTo("Omar Nasser");
        assertThat(one("SELECT username FROM app_user WHERE employee_id IS NULL AND enabled"))
                .isEqualTo("admin");
        assertThat(one("SELECT username FROM app_user WHERE NOT enabled"))
                .isEqualTo("svc-reporting");
    }

    private String one(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }
}
