package com.carparts.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The identity contract every entity keeps, checked on all of them at once.
 *
 * <p>Entity equality is the one piece of boilerplate whose obvious implementation is wrong in a
 * way that only shows up after something has been lost. Hibernate hands out different instances
 * for the same row and puts them in sets, and the id these classes are identified by does not
 * exist until the row is inserted — so the rule has to work before and after a flush, and stay
 * consistent across the two.
 *
 * <p>Driven by reflection over a list rather than written out per class, because the failure this
 * guards against is a <em>new</em> entity doing it differently. A test naming today's ten would
 * not notice the eleventh, so {@link #theListIsComplete()} checks the list itself.
 */
@DisplayName("entity identity")
class EntityIdentityTest {

    /**
     * An entity and how to give it an id, since the two id shapes are set differently.
     *
     * <p>Fully-qualified {@code java.util.function.Supplier}, because the domain has a
     * {@link Supplier} of its own and an import of the other one silently turns
     * {@code Supplier.class} below into the wrong class.
     *
     * @param type    the concrete entity class
     * @param idOne   an id value, built fresh each call so the two instances get equal-but-not-same
     * @param idOther a different id of the same shape
     */
    private record Subject(Class<?> type,
                           java.util.function.Supplier<Object> idOne,
                           java.util.function.Supplier<Object> idOther) {
        @Override
        public String toString() {
            return type.getSimpleName();
        }
    }

    /** Everything identified by a generated {@code Long}. Department is abstract; its two
     *  subclasses stand in for it, which is also the only way the shared id space is exercised. */
    static Stream<Subject> simpleIds() {
        return Stream.of(Customer.class, Supplier.class, Part.class, Employee.class,
                        Branch.class, Warehouse.class, CustomerOrder.class, AppUser.class)
                .map(t -> new Subject(t, () -> 42L, () -> 43L));
    }

    /** Everything identified by an {@code @EmbeddedId}. */
    static Stream<Subject> compositeIds() {
        return Stream.of(
                new Subject(OrderItem.class,
                        () -> new OrderItemId(1L, 2L), () -> new OrderItemId(1L, 3L)),
                new Subject(WarehouseStock.class,
                        () -> new WarehouseStockId(1L, 2L), () -> new WarehouseStockId(1L, 3L)),
                new Subject(CarFitment.class,
                        () -> new CarFitmentId(1L, "Toyota", "Corolla", (short) 2015),
                        () -> new CarFitmentId(1L, "Toyota", "Corolla", (short) 2016)));
    }

    static Stream<Subject> allEntities() {
        return Stream.concat(simpleIds(), compositeIds());
    }

    /** A bare instance, without a constructor that would demand collaborators. */
    private static Object blank(Class<?> type) throws Exception {
        Constructor<?> c = type.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private static void setId(Object entity, Object value) throws Exception {
        for (Class<?> t = entity.getClass(); t != null; t = t.getSuperclass()) {
            for (Field f : t.getDeclaredFields()) {
                if (f.getName().equals("id")) {
                    f.setAccessible(true);
                    f.set(entity, value);
                    return;
                }
            }
        }
        throw new AssertionError(entity.getClass() + " has no id field");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEntities")
    @DisplayName("two unsaved instances are never equal, not even to an identical one")
    void unsavedInstancesAreDistinct(Subject subject) throws Exception {
        Object a = blank(subject.type());
        Object b = blank(subject.type());

        // Both have a null id. Treating them as equal would collapse two new rows into one the
        // moment they met a Set — a batch of new order lines arriving as a single line.
        assertThat(a).isNotEqualTo(b);
        assertThat(b).isNotEqualTo(a);
        assertThat(a).as("but still equal to itself, id or no id").isEqualTo(a);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEntities")
    @DisplayName("two instances of the same row are equal however they were loaded")
    void sameRowIsEqual(Subject subject) throws Exception {
        Object a = blank(subject.type());
        Object b = blank(subject.type());
        setId(a, subject.idOne().get());
        setId(b, subject.idOne().get());   // an equal id, deliberately not the same instance

        // Hibernate returns different instances for one row across sessions, so anything
        // comparing a detached copy with a freshly loaded one rests on this.
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(b).isEqualTo(a);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEntities")
    @DisplayName("different rows are not equal, and neither is another type or null")
    void differentRowsDiffer(Subject subject) throws Exception {
        Object a = blank(subject.type());
        Object b = blank(subject.type());
        setId(a, subject.idOne().get());
        setId(b, subject.idOther().get());

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not an entity");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEntities")
    @DisplayName("the hash does not move when the row is inserted")
    void hashIsStableAcrossInsert(Subject subject) throws Exception {
        Object entity = blank(subject.type());
        Set<Object> set = new HashSet<>();
        set.add(entity);

        int before = entity.hashCode();
        setId(entity, subject.idOne().get());   // what a flush does

        // This is why the hash is a constant rather than derived from the id. An id-based hash
        // moves the object to another bucket at flush, and the set it is already sitting in can
        // no longer find it: the entity is in the collection and contains() says otherwise.
        assertThat(entity.hashCode()).as("the hash must not move").isEqualTo(before);
        assertThat(set).as("and the set must still contain it").contains(entity);
    }

    /**
     * Stand-ins for the proxies Hibernate generates for a lazy association.
     *
     * <p>A real proxy is a bytecode subclass made at runtime; these are the same thing written by
     * hand. Declared here rather than produced by a library because the only property under test
     * is that they are a <em>different class</em> extending the entity, which is all a proxy is
     * as far as {@code equals} can tell.
     */
    private static final class CustomerProxy extends Customer {}

    private static final class PartProxy extends Part {}

    private static final class OrderItemProxy extends OrderItem {}

    @Test
    @DisplayName("an entity equals its own lazy proxy, in both directions")
    void equalsSurvivesAProxy() throws Exception {
        // What comes back for a lazy association is not the entity class — it is a generated
        // subclass. `instanceof` is therefore not the laxer choice, it is the only one that
        // works: rewriting these as `getClass() != o.getClass()`, which is what an IDE generates
        // and what several style guides ask for, makes every entity unequal to its own proxy.
        // Nothing looks broken; associations just quietly stop matching.
        record Pair(Object entity, Object proxy, Object id) {}

        List<Pair> pairs = List.of(
                new Pair(blank(Customer.class), new CustomerProxy(), 42L),
                new Pair(blank(Part.class), new PartProxy(), 42L),
                new Pair(blank(OrderItem.class), new OrderItemProxy(), new OrderItemId(1L, 2L)));

        for (Pair pair : pairs) {
            setId(pair.entity(), pair.id());
            setId(pair.proxy(), pair.id());

            assertThat(pair.entity().getClass())
                    .as("the fixture is only meaningful if the proxy really is another class")
                    .isNotEqualTo(pair.proxy().getClass());
            assertThat(pair.entity())
                    .as("%s equals its proxy", pair.entity().getClass().getSimpleName())
                    .isEqualTo(pair.proxy());
            assertThat(pair.proxy())
                    .as("and symmetrically, which the equals contract requires")
                    .isEqualTo(pair.entity());
        }
    }

    @Test
    @DisplayName("a digest never reaches a log through toString")
    void toStringNeverCarriesTheDigest() {
        AppUser user = new AppUser("layla",
                "$2a$12$aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", UserRole.ADMIN);

        // toString is what ends up in an exception message, a debugger and a log line. The
        // digest is not a password, but it is offline-crackable and belongs in none of those.
        assertThat(user.toString())
                .contains("layla").contains("ADMIN")
                .doesNotContain("$2a$").doesNotContain("passwordHash");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEntities")
    @DisplayName("toString on an unsaved entity does not walk a lazy association")
    void toStringIsSafeOnUnsavedEntities(Subject subject) throws Exception {
        // A toString that reaches for a parent is a LazyInitializationException raised from
        // inside a log statement: an error thrown by the code trying to report an error.
        assertThat(blank(subject.type()).toString()).isNotBlank();
    }

    @Test
    @DisplayName("every entity in the domain is on the list above")
    void theListIsComplete() throws Exception {
        List<String> named = allEntities().map(s -> s.type().getSimpleName()).sorted().toList();
        List<String> found = new ArrayList<>();

        try (var files = java.nio.file.Files.list(
                java.nio.file.Path.of("src/main/java/com/carparts/domain"))) {
            for (java.nio.file.Path file : files.toList()) {
                String source = java.nio.file.Files.readString(file);
                // Department is abstract and has no instances of its own; Branch and Warehouse
                // are what actually exist, and both are on the list.
                if (source.contains("@Entity") && !source.contains("abstract class")) {
                    found.add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }

        assertThat(found).as("an entity was added without joining this test")
                .containsExactlyInAnyOrderElementsOf(named);
    }
}
