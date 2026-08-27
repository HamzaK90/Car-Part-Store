package com.carparts.support;

/**
 * Turning what a caller typed into what the database should hold or be asked.
 *
 * <p>Both halves exist because the same input — a blank string — has to mean different things
 * in different places, and getting either wrong fails quietly rather than loudly.
 */
public final class Text {

    private Text() {
    }

    /**
     * A value the caller left empty, as {@code null}.
     *
     * <p>An omitted optional field arrives as null; one the caller sent as {@code ""} arrives as
     * an empty string. Storing the difference is a bug waiting on a unique constraint: an empty
     * email is not NULL, so PostgreSQL treats two customers who both have no email as a
     * collision and the second is told the address is already registered. Nullable-unique only
     * behaves as intended if "absent" has exactly one representation.
     */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * A search term as a {@code LIKE} pattern, with an absent term matching everything.
     *
     * <p>The widening is not convenience. PostgreSQL type-checks the whole predicate even where
     * it would short-circuit, and a null string parameter arrives with nothing to infer a type
     * from, so {@code LOWER(?)} fails with <em>function lower(bytea) does not exist</em> before
     * a single row is examined. Passing {@code %} instead of null is what avoids that.
     *
     * <p>{@code PartRepository} still builds this inline. Folding it in here belongs with the
     * wider tidy-up of the paging and address duplication, once every category has shipped.
     */
    public static String likePattern(String search) {
        String term = blankToNull(search);
        return term == null ? "%" : "%" + term.toLowerCase() + "%";
    }
}
