package com.carparts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules the entities carry themselves.
 *
 * <p>No Spring and no database: these are the parts of the model that hold whether or not
 * anything is persisted, and testing them in memory means the whole class runs in
 * milliseconds rather than waiting on a context.
 *
 * <p>They are not a substitute for the integration tests. A domain method can be perfectly
 * correct and still be reached with a detached entity, outside a transaction, or after a
 * mapping has drifted — which is why the same rules are checked again through the API and
 * again at the database. This layer is about the arithmetic and the guards.
 */
@DisplayName("domain rules, in memory")
class DomainTest {

    private static Supplier supplier() {
        return new Supplier("Vendor");
    }

    private static Part part(String sku, String price) {
        return new Part(sku, "A part", new BigDecimal(price), new BigDecimal("1.0"), supplier());
    }

    private static Warehouse warehouse() {
        return new Warehouse("Store", new Address("Zarqa", "Industrial Rd"), new BigDecimal("100"));
    }

    private static Branch branch() {
        return new Branch("Shop", new Address("Amman", "King St"));
    }

    private static Employee employee(String name, Department where) {
        return new Employee(name, new BigDecimal("1000"), ShiftType.MORNING, where);
    }

    // ---------------------------------------------------------------- Address

    @Nested
    @DisplayName("Address")
    class Addresses {

        @Test
        @DisplayName("a blank part is stored as null, so 'no city' has one representation")
        void blankBecomesNull() {
            // uq_customer_email is the reason this matters elsewhere: an empty string is not
            // NULL, and two rows holding '' collide where two holding NULL do not.
            assertThat(new Address("", "  ").getCity()).isNull();
            assertThat(new Address("", "  ").getStreet()).isNull();
            assertThat(new Address(null, null).getCity()).isNull();
        }

        @Test
        @DisplayName("values are trimmed rather than stored with their padding")
        void trimmed() {
            assertThat(new Address("  Amman  ", " King St ").getCity()).isEqualTo("Amman");
            assertThat(new Address("  Amman  ", " King St ").getStreet()).isEqualTo("King St");
        }

        @Test
        @DisplayName("merging replaces only what is named, and keeps the rest")
        void merging() {
            Address current = new Address("Amman", "King St");

            assertThat(Address.merged(current, "Zarqa", null))
                    .isEqualTo(new Address("Zarqa", "King St"));
            assertThat(Address.merged(current, null, "New Rd"))
                    .isEqualTo(new Address("Amman", "New Rd"));
            assertThat(Address.merged(current, null, null)).isEqualTo(current);
        }

        @Test
        @DisplayName("merging onto nothing is allowed, for something with no address yet")
        void mergingOntoNull() {
            assertThat(Address.merged(null, "Aqaba", null).getCity()).isEqualTo("Aqaba");
            assertThat(Address.merged(null, null, "Dock Rd").getStreet()).isEqualTo("Dock Rd");
            assertThat(Address.merged(null, null, null)).isEqualTo(new Address(null, null));
        }

        @Test
        @DisplayName("two addresses saying the same thing are the same address")
        void valueEquality() {
            // An address has no identity of its own; two employees on the same street are not
            // sharing a row, they each hold a copy.
            Address a = new Address("Amman", "King St");
            assertThat(a).isEqualTo(new Address("Amman", "King St"))
                    .hasSameHashCodeAs(new Address("Amman", "King St"))
                    .isNotEqualTo(new Address("Amman", "Other St"))
                    .isNotEqualTo(new Address("Zarqa", "King St"))
                    .isNotEqualTo("not an address")
                    .isEqualTo(a);
            assertThat(a.toString()).contains("King St").contains("Amman");
        }
    }

    // ---------------------------------------------------------------- composite keys

    @Nested
    @DisplayName("composite keys")
    class CompositeKeys {

        @Test
        @DisplayName("a warehouse-stock key is the pair, and nothing else")
        void warehouseStockId() {
            WarehouseStockId id = new WarehouseStockId(1L, 2L);
            assertThat(id).isEqualTo(new WarehouseStockId(1L, 2L))
                    .hasSameHashCodeAs(new WarehouseStockId(1L, 2L))
                    .isNotEqualTo(new WarehouseStockId(2L, 1L))
                    .isNotEqualTo(new WarehouseStockId(1L, 3L))
                    .isNotEqualTo(null)
                    .isNotEqualTo("no");
            assertThat(id.getWarehouseId()).isEqualTo(1L);
            assertThat(id.getPartId()).isEqualTo(2L);
            assertThat(id.toString()).contains("1").contains("2");
        }

        @Test
        @DisplayName("an order-line key is the order and the part")
        void orderItemId() {
            OrderItemId id = new OrderItemId(1001L, 5L);
            assertThat(id).isEqualTo(new OrderItemId(1001L, 5L))
                    .hasSameHashCodeAs(new OrderItemId(1001L, 5L))
                    .isNotEqualTo(new OrderItemId(1002L, 5L))
                    .isNotEqualTo(new OrderItemId(1001L, 6L))
                    .isNotEqualTo(null);
            assertThat(id.getOrderId()).isEqualTo(1001L);
            assertThat(id.getPartId()).isEqualTo(5L);
            assertThat(id.toString()).contains("1001");
        }

        @Test
        @DisplayName("a fitment key includes the first year, which is why only the last can change")
        void carFitmentId() {
            CarFitmentId id = new CarFitmentId(1L, "Toyota", "Corolla", (short) 2015);
            assertThat(id).isEqualTo(new CarFitmentId(1L, "Toyota", "Corolla", (short) 2015))
                    .hasSameHashCodeAs(new CarFitmentId(1L, "Toyota", "Corolla", (short) 2015))
                    .isNotEqualTo(new CarFitmentId(2L, "Toyota", "Corolla", (short) 2015))
                    .isNotEqualTo(new CarFitmentId(1L, "Honda", "Corolla", (short) 2015))
                    .isNotEqualTo(new CarFitmentId(1L, "Toyota", "Civic", (short) 2015))
                    .isNotEqualTo(new CarFitmentId(1L, "Toyota", "Corolla", (short) 2016))
                    .isNotEqualTo(null);
            assertThat(id.getMake()).isEqualTo("Toyota");
            assertThat(id.getModel()).isEqualTo("Corolla");
            assertThat(id.getYearFrom()).isEqualTo((short) 2015);
            assertThat(id.toString()).contains("Corolla");
        }
    }

    // ---------------------------------------------------------------- stock

    @Nested
    @DisplayName("WarehouseStock")
    class Stock {

        @Test
        @DisplayName("increasing and decreasing move the quantity, and hasAtLeast reads it")
        void arithmetic() {
            WarehouseStock row = new WarehouseStock(warehouse(), part("P-1", "10"), 10);

            assertThat(row.hasAtLeast(10)).isTrue();
            assertThat(row.hasAtLeast(11)).isFalse();

            row.decrease(4);
            assertThat(row.getQuantity()).isEqualTo(6);
            row.increase(9);
            assertThat(row.getQuantity()).isEqualTo(15);
        }

        @Test
        @DisplayName("a shelf cannot be taken below zero, whatever the caller asks")
        void cannotGoNegative() {
            WarehouseStock row = new WarehouseStock(warehouse(), part("P-2", "10"), 3);
            // ck_warehouse_stock_quantity is the guarantee; refusing here means the caller gets
            // a sentence instead of a constraint violation from three layers down.
            assertThatThrownBy(() -> row.decrease(4)).isInstanceOf(IllegalArgumentException.class);
            assertThat(row.getQuantity()).as("and nothing moved").isEqualTo(3);
        }

        @Test
        @DisplayName("a move of nothing, or of a negative amount, is refused")
        void refusesNonPositiveMoves() {
            WarehouseStock row = new WarehouseStock(warehouse(), part("P-3", "10"), 5);
            assertThatThrownBy(() -> row.decrease(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> row.increase(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> row.decrease(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> row.increase(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("low is per part, from its own reorder level")
        void lowIsPerPart() {
            Part p = part("P-4", "10");
            p.setReorderLevel(5);
            WarehouseStock row = new WarehouseStock(warehouse(), p, 5);

            assertThat(row.isLow()).as("at the level is not below it").isFalse();
            row.decrease(1);
            assertThat(row.isLow()).isTrue();

            p.setReorderLevel(0);
            assertThat(row.isLow()).as("a level of zero never flags, since stock cannot go below")
                    .isFalse();
        }
    }

    // ---------------------------------------------------------------- catalogue

    @Nested
    @DisplayName("Part and CarFitment")
    class Catalogue {

        @Test
        @DisplayName("a fitment covers its years inclusively")
        void fitmentCoverage() {
            CarFitment f = new CarFitment(part("P-5", "10"), "Toyota", "Corolla",
                    (short) 2015, (short) 2020);

            assertThat(f.covers((short) 2015)).isTrue();
            assertThat(f.covers((short) 2020)).isTrue();
            assertThat(f.covers((short) 2017)).isTrue();
            assertThat(f.covers((short) 2014)).isFalse();
            assertThat(f.covers((short) 2021)).isFalse();
        }

        @Test
        @DisplayName("a part fits a car when one of its fitments covers it, ignoring case")
        void partFitsCar() {
            Part p = part("P-6", "10");
            p.getFitments().add(new CarFitment(p, "Toyota", "Corolla",
                    (short) 2015, (short) 2020));

            assertThat(p.fitsCar("Toyota", "Corolla", (short) 2017)).isTrue();
            assertThat(p.fitsCar("toyota", "corolla", (short) 2017)).as("case").isTrue();
            assertThat(p.fitsCar("Toyota", "Corolla", (short) 2024)).as("year").isFalse();
            assertThat(p.fitsCar("Honda", "Corolla", (short) 2017)).as("make").isFalse();
            assertThat(p.fitsCar("Toyota", "Civic", (short) 2017)).as("model").isFalse();
        }

        @Test
        @DisplayName("a part with no fitments recorded fits nothing")
        void noFitments() {
            assertThat(part("P-7", "10").fitsCar("Toyota", "Corolla", (short) 2017)).isFalse();
        }

        @Test
        @DisplayName("entity identity: unsaved rows are distinct, saved ones compare by id")
        void identity() {
            Part a = part("P-8", "10");
            Part b = part("P-8", "10");
            // Both have a null id, so neither is "the same row" as the other yet.
            assertThat(a).isEqualTo(a).isNotEqualTo(b).isNotEqualTo(null).isNotEqualTo("no");
            assertThat(a.toString()).contains("P-8");
        }
    }

    // ---------------------------------------------------------------- orders

    @Nested
    @DisplayName("CustomerOrder")
    class Orders {

        private CustomerOrder order() {
            return new CustomerOrder(new Customer("Buyer", "0790000000"), branch(), warehouse());
        }

        @Test
        @DisplayName("a line captures today's price, and the total is the sum of the lines")
        void totals() {
            CustomerOrder o = order();
            o.addLine(part("A", "45.00"), 2);
            o.addLine(part("B", "10.50"), 3);

            assertThat(o.total()).isEqualByComparingTo("121.50");
            assertThat(o.unitCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("an empty order totals zero rather than nothing")
        void emptyTotal() {
            assertThat(order().total()).isEqualByComparingTo("0.00");
            assertThat(order().unitCount()).isZero();
        }

        @Test
        @DisplayName("the same part twice is merged into one line, not added again")
        void addingTheSamePartTwice() {
            CustomerOrder o = order();
            Part p = part("A", "45.00");
            o.addLine(p, 2);
            o.addLine(p, 3);

            // The alternative is a flush-time failure on the composite primary key.
            assertThat(o.getItems()).hasSize(1);
            assertThat(o.lineFor(p).getQuantity()).isEqualTo(5);
            assertThat(o.total()).isEqualByComparingTo("225.00");
        }

        @Test
        @DisplayName("lineFor answers null for a part the order does not carry")
        void lineForMissingPart() {
            CustomerOrder o = order();
            o.addLine(part("A", "45.00"), 1);
            assertThat(o.lineFor(part("B", "10.00"))).isNull();
        }

        @Test
        @DisplayName("a repriced part does not move a line already sold")
        void repricingDoesNotMoveALine() {
            CustomerOrder o = order();
            Part p = part("A", "45.00");
            o.addLine(p, 2);

            p.setPrice(new BigDecimal("999.00"));

            assertThat(o.total()).as("unit_price was captured at sale").isEqualByComparingTo("90.00");
            assertThat(o.getItems().getFirst().lineTotal()).isEqualByComparingTo("90.00");
        }

        @Test
        @DisplayName("only a PLACED order may be fulfilled or cancelled")
        void statusGuards() {
            CustomerOrder placed = order();
            assertThat(placed.getStatus()).isEqualTo(OrderStatus.PLACED);

            assertThatCode(placed::fulfil).doesNotThrowAnyException();
            assertThat(placed.getStatus()).isEqualTo(OrderStatus.FULFILLED);
            assertThatThrownBy(placed::cancel).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(placed::fulfil).isInstanceOf(IllegalStateException.class);

            CustomerOrder other = order();
            assertThatCode(other::cancel).doesNotThrowAnyException();
            assertThat(other.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThatThrownBy(other::fulfil).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a line of nothing is refused")
        void zeroQuantityLine() {
            assertThatThrownBy(() -> order().addLine(part("A", "10"), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------------------------------------------------------------- people

    @Nested
    @DisplayName("Department and Employee")
    class People {

        @Test
        @DisplayName("hiring sets both ends, so the headcount cannot go stale")
        void hiring() {
            Department d = branch();
            assertThat(d.headcount()).isZero();
            assertThat(d.isHeadless()).isTrue();

            Employee e = employee("Anna", null);
            d.addEmployee(e);

            assertThat(d.headcount()).isEqualTo(1);
            assertThat(e.getDepartment()).isSameAs(d);
            assertThat(e.worksAt(d)).isTrue();
        }

        @Test
        @DisplayName("only somebody who works there may be promoted")
        void promotion() {
            Department here = branch();
            Department elsewhere = warehouse();
            Employee ours = employee("Ours", null);
            Employee theirs = employee("Theirs", elsewhere);
            here.addEmployee(ours);

            assertThatThrownBy(() -> here.promote(theirs))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(here.isHeadless()).isTrue();

            here.promote(ours);
            assertThat(here.getManager()).isSameAs(ours);
            assertThat(ours.isManager()).isTrue();
            assertThat(here.isHeadless()).isFalse();
        }

        @Test
        @DisplayName("vacating the post is allowed — it is how every department begins")
        void vacating() {
            Department d = branch();
            Employee e = employee("Anna", null);
            d.addEmployee(e);
            d.promote(e);

            d.vacateManagerPost();

            assertThat(d.isHeadless()).isTrue();
            assertThat(e.isManager()).isFalse();
        }

        @Test
        @DisplayName("transferring a manager empties the post rather than blocking the move")
        void transferVacates() {
            Department from = branch();
            Department to = warehouse();
            Employee e = employee("Anna", null);
            from.addEmployee(e);
            from.promote(e);

            e.transferTo(to);

            assertThat(e.getDepartment()).isSameAs(to);
            assertThat(from.isHeadless()).as("losing a manager is ordinary").isTrue();
            assertThat(from.headcount()).isZero();
            assertThat(to.headcount()).isEqualTo(1);
        }

        @Test
        @DisplayName("transferring somewhere they already are does nothing")
        void transferToSameDepartment() {
            Department d = branch();
            Employee e = employee("Anna", null);
            d.addEmployee(e);
            d.promote(e);

            e.transferTo(d);
            e.transferTo(null);

            assertThat(d.headcount()).isEqualTo(1);
            assertThat(e.isManager()).as("no spurious vacancy").isTrue();
        }

        @Test
        @DisplayName("worksAt is false for null and for somewhere else")
        void worksAt() {
            Department d = branch();
            Employee e = employee("Anna", d);
            assertThat(e.worksAt(d)).isTrue();
            assertThat(e.worksAt(null)).isFalse();
            assertThat(e.worksAt(warehouse())).isFalse();
        }

        @Test
        @DisplayName("a warehouse carries its free area; a branch adds no state")
        void subtypes() {
            Warehouse w = warehouse();
            assertThat(w.getType()).isEqualTo(DepartmentType.WAREHOUSE);
            assertThat(w.getFreeAreaSqm()).isEqualByComparingTo("100");
            w.setFreeAreaSqm(new BigDecimal("250"));
            assertThat(w.getFreeAreaSqm()).isEqualByComparingTo("250");

            assertThat(branch().getType()).isEqualTo(DepartmentType.BRANCH);
        }
    }

    // ---------------------------------------------------------------- accounts

    @Nested
    @DisplayName("AppUser")
    class Accounts {

        @Test
        @DisplayName("an account defaults to EMPLOYEE and enabled, with nobody behind it")
        void defaults() {
            AppUser u = new AppUser("someone", "$2a$04$" + "x".repeat(53), UserRole.EMPLOYEE);
            assertThat(u.getUsername()).isEqualTo("someone");
            assertThat(u.getRole()).isEqualTo(UserRole.EMPLOYEE);
            assertThat(u.isEnabled()).isTrue();
            assertThat(u.getEmployee()).as("a login is not the same thing as a person").isNull();
            assertThat(u.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("disabling keeps the account rather than deleting it")
        void disabling() {
            AppUser u = new AppUser("someone", "$2a$04$" + "x".repeat(53), UserRole.ADMIN);
            u.setEnabled(false);
            assertThat(u.isEnabled()).isFalse();
            assertThat(u.getRole()).isEqualTo(UserRole.ADMIN);
            assertThat(u.toString()).contains("someone").doesNotContain("$2a$");
        }

        @Test
        @DisplayName("the digest is never in toString, whatever else is")
        void digestIsNotPrinted() {
            String digest = "$2a$12$" + "y".repeat(53);
            AppUser u = new AppUser("someone", digest, UserRole.EMPLOYEE);
            assertThat(u.getPasswordHash()).isEqualTo(digest);
            assertThat(u.toString()).doesNotContain(digest);
        }
    }
}
