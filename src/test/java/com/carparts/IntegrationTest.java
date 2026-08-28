package com.carparts;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A real PostgreSQL, started once and shared by every test that extends this.
 *
 * <p><b>Not H2, and not a mock.</b> Half of what this application relies on does not exist
 * outside PostgreSQL: native enum types, deferred constraint triggers, {@code SELECT … FOR
 * UPDATE}, partial unique behaviour around NULLs, and the six views the reports read. A test
 * against a substitute database would pass while proving nothing about the thing that ships.
 *
 * <p>Flyway runs the real {@code V1}–{@code V6} against it, so the schema under test is the
 * schema in the repository, seed data included. Hibernate then validates every mapping against
 * it — {@code ddl-auto: validate} is the same setting production uses, and it is the check that
 * catches an entity drifting from its table.
 *
 * <p>One instance for the whole run, not one per class. Starting PostgreSQL costs a few seconds
 * and doing it per class would dominate the suite. The consequence is that <b>tests share a
 * database and must not depend on each other's state</b>; see the note on isolation in
 * {@code AbstractWebTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTest {

    /** The password behind both digests seeded by {@code application-test.yml}. */
    public static final String PASSWORD = "test-password";

    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the embedded PostgreSQL", e);
        }
    }

    /**
     * Points Spring at the database once it is listening.
     *
     * <p>A static property source rather than a fixed URL in {@code application-test.yml},
     * because the port is chosen when the server starts and is not knowable before then.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
