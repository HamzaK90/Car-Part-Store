package com.carparts.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.carparts.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Every friendly constraint message, checked against the schema Flyway just built.
 *
 * <p>Naming every constraint in V1–V3 was what made those messages possible: PostgreSQL reports
 * the name it was given, so a violation can be explained instead of arriving as a stack trace.
 * The link between the two halves is a string, and nothing else enforces it.
 *
 * <p>A rename in a later migration therefore orphans a message quietly. No test goes red, no
 * exception is thrown — the API simply stops explaining that particular failure and falls back to
 * the generic 409. This is the only thing that notices.
 *
 * <p>In {@code com.carparts.web} so it can read the map, which is package-private on purpose: the
 * wording is the handler's business and nothing else should be branching on it.
 */
@DisplayName("the constraint messages against the real schema")
class ConstraintMappingTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("every mapped name is a rule the database really has")
    void everyMappedConstraintExists() {
        // Both kinds. A CHECK or a foreign key is in pg_constraint; V3's rules are constraint
        // triggers, which are only in pg_trigger — looking in one place would pass the mapping
        // for the other by never checking it.
        List<String> real = jdbc.sql("""
                SELECT conname FROM pg_constraint
                UNION SELECT tgname FROM pg_trigger WHERE NOT tgisinternal
                """).query(String.class).list();

        assertThat(ApiExceptionHandler.mappedConstraints())
                .isNotEmpty()
                .allSatisfy(name -> assertThat(real)
                        .as("%s has a message but no such rule in the schema", name)
                        .contains(name));
    }
}
