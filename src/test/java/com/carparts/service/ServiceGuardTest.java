package com.carparts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carparts.IntegrationTest;
import com.carparts.domain.CustomerOrder;
import com.carparts.domain.Part;
import com.carparts.domain.Supplier;
import com.carparts.domain.WarehouseStock;
import com.carparts.domain.WarehouseStockId;
import com.carparts.repository.PartRepository;
import com.carparts.repository.SupplierRepository;
import com.carparts.repository.WarehouseStockRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The service guards that a request can no longer reach.
 *
 * <p>Bean Validation on the request bodies rejects a null customer id, an empty line list and a
 * quantity of zero before any of this runs, so these branches are unreachable over HTTP. They
 * are still the right place for the rule — {@code OrderService} is called by the invoice and
 * reporting paths too, and a service that trusts its caller to have validated first is a service
 * that breaks the day something calls it without a controller in between.
 *
 * <p>Which leaves them untested unless something drives the service directly. That is this.
 *
 * <p>{@code @Transactional}, so everything here rolls back. These tests never assert on anything
 * a constraint decides at commit, which is what makes that safe — see {@code ConstraintTest} for
 * the ones that cannot roll back.
 */
@Transactional
@DisplayName("service guards below the request layer")
class ServiceGuardTest extends IntegrationTest {

    @Autowired
    private OrderService orders;

    @Autowired
    private StockService stocks;

    @Autowired
    private PartRepository parts;

    @Autowired
    private SupplierRepository suppliers;

    @Autowired
    private WarehouseStockRepository shelves;

    private static final long CUSTOMER = 1L;
    private static final long BRANCH = 1L;
    private static final long WAREHOUSE = 3L;

    /** A part nothing else in the suite has touched, optionally stocked. */
    private Part freshPart(int stocked) {
        Supplier supplier = suppliers.save(new Supplier("Guard Vendor " + System.nanoTime()));
        Part part = parts.save(new Part("GD-" + System.nanoTime(), "Guard Part",
                new BigDecimal("10.00"), new BigDecimal("1.00"), supplier));
        if (stocked > 0) {
            stocks.setQuantity(WAREHOUSE, part.getId(), stocked);
        }
        return part;
    }

    private PlaceOrderCommand order(List<PlaceOrderCommand.Line> lines) {
        return new PlaceOrderCommand(CUSTOMER, BRANCH, WAREHOUSE, lines);
    }

    private static PlaceOrderCommand.Line line(Long partId, int quantity) {
        return new PlaceOrderCommand.Line(partId, quantity);
    }

    /**
     * Reads the shelf, rather than asking a writer what it just wrote.
     *
     * <p>The obvious {@code setQuantity(w, p, 4).getQuantity()} returns 4 whatever the shelf held
     * a moment earlier, because setting it is what the call does. An assertion built on that
     * passes no matter what the code under test did.
     */
    private int onTheShelf(long warehouse, long part) {
        return shelves.findById(new WarehouseStockId(warehouse, part))
                .map(WarehouseStock::getQuantity)
                .orElse(0);
    }

    @Nested
    @DisplayName("what an order must say before anything is read")
    class RequiredFields {

        @Test
        @DisplayName("no command at all is refused by name, not by NullPointerException")
        void nullCommand() {
            assertThatThrownBy(() -> orders.placeOrder(null, null))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("no order was supplied");
        }

        @Test
        @DisplayName("each missing id says which one is missing")
        void everyIdIsNamed() {
            List<PlaceOrderCommand.Line> lines = List.of(line(1L, 1));

            // Three separate messages rather than one "invalid order". The caller is usually
            // fixing this by hand and the difference is between one attempt and three.
            assertThatThrownBy(() -> orders.placeOrder(
                    new PlaceOrderCommand(null, BRANCH, WAREHOUSE, lines), null))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("customer");
            assertThatThrownBy(() -> orders.placeOrder(
                    new PlaceOrderCommand(CUSTOMER, null, WAREHOUSE, lines), null))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("branch");
            assertThatThrownBy(() -> orders.placeOrder(
                    new PlaceOrderCommand(CUSTOMER, BRANCH, null, lines), null))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("warehouse");
        }

        @Test
        @DisplayName("the ids are checked before anything is loaded")
        void idsAreCheckedFirst() {
            // A null customer id and a nonexistent branch: the message must be about the null,
            // because that check runs first. If this ever reports the branch instead, the order
            // of the guards has changed and a null id is reaching a repository.
            assertThatThrownBy(() -> orders.placeOrder(
                    new PlaceOrderCommand(null, 999999L, WAREHOUSE, List.of(line(1L, 1))), null))
                    .hasMessageContaining("customer");
        }
    }

    @Nested
    @DisplayName("the lines")
    class Lines {

        @Test
        @DisplayName("an order with nothing on it is refused")
        void emptyOrNoLines() {
            assertThatThrownBy(() -> orders.placeOrder(order(null), null))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("at least one line");
            assertThatThrownBy(() -> orders.placeOrder(order(List.of()), null))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("at least one line");
        }

        @Test
        @DisplayName("a null line, a line naming no part, and a quantity of nothing")
        void malformedLines() {
            // Arrays.asList, because List.of refuses to hold a null and the null is the case.
            assertThatThrownBy(() -> orders.placeOrder(
                    order(Arrays.asList(line(1L, 1), null)), null))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("cannot be empty");

            assertThatThrownBy(() -> orders.placeOrder(order(List.of(line(null, 1))), null))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("must name a part");

            for (int quantity : new int[]{0, -1, Integer.MIN_VALUE}) {
                assertThatThrownBy(() -> orders.placeOrder(order(List.of(line(7L, quantity))), null))
                        .as("quantity %d", quantity)
                        .isInstanceOf(InvalidRequestException.class)
                        .hasMessageContaining("greater than zero")
                        .hasMessageContaining("part 7");
            }
        }

        @Test
        @DisplayName("the same part twice is one line for twice as many")
        void duplicateLinesAreAdded() {
            Part part = freshPart(10);

            CustomerOrder placed = orders.placeOrder(order(List.of(
                    line(part.getId(), 3), line(part.getId(), 3))), null);

            // Checked as 3 and then 3 again, this would sell six units against a shelf of four.
            // Folding them first is what makes the stock check mean anything.
            assertThat(placed.getItems()).hasSize(1);
            assertThat(placed.getItems().iterator().next().getQuantity()).isEqualTo(6);
            assertThat(onTheShelf(WAREHOUSE, part.getId()))
                    .as("and the shelf really was decremented by six, not by three")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("every unknown part is listed at once, not the first one found")
        void unknownPartsAreListedTogether() {
            Part real = freshPart(5);

            assertThatThrownBy(() -> orders.placeOrder(order(List.of(
                    line(real.getId(), 1), line(999998L, 1), line(999999L, 1))), null))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("999998")
                    .hasMessageContaining("999999");
        }
    }

    @Nested
    @DisplayName("amending an order")
    class Amending {

        @Test
        @DisplayName("an amendment is refused whole when any line is short")
        void refusedAmendmentChangesNothing() {
            Part a = freshPart(10);
            Part b = freshPart(1);
            CustomerOrder placed = orders.placeOrder(order(List.of(line(a.getId(), 2))), null);

            assertThatThrownBy(() -> orders.amendLines(placed.getId(), List.of(
                    line(a.getId(), 3), line(b.getId(), 99))))
                    .isInstanceOf(InsufficientStockException.class);

            // The first line was fine and could have been applied before the second failed. It
            // must not have been: an amendment is one decision, not a sequence of them.
            assertThat(onTheShelf(WAREHOUSE, a.getId()))
                    .as("the extra unit for the first line was never taken").isEqualTo(8);
            assertThat(onTheShelf(WAREHOUSE, b.getId()))
                    .as("and nothing was taken for the line that failed").isEqualTo(1);
            assertThat(placed.getItems()).hasSize(1);
            assertThat(placed.getItems().iterator().next().getQuantity())
                    .as("the order still says two").isEqualTo(2);
        }

        @Test
        @DisplayName("every shortage is reported, ordered by SKU")
        void shortagesAreCollected() {
            Part a = freshPart(1);
            Part b = freshPart(1);
            CustomerOrder placed = orders.placeOrder(order(List.of(line(a.getId(), 1))), null);

            assertThatThrownBy(() -> orders.amendLines(placed.getId(), List.of(
                    line(a.getId(), 50), line(b.getId(), 50))))
                    .isInstanceOf(InsufficientStockException.class)
                    .satisfies(e -> {
                        var shortages = ((InsufficientStockException) e).getShortages();
                        assertThat(shortages).as("both, not just the first").hasSize(2);
                        assertThat(shortages).extracting(
                                        InsufficientStockException.Shortage::sku)
                                .as("a stable order, so the message does not vary between runs")
                                .isSorted();
                    });
        }

        @Test
        @DisplayName("an order that does not exist cannot be amended")
        void unknownOrder() {
            assertThatThrownBy(() -> orders.amendLines(999999L, List.of(line(1L, 1))))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("counting and moving stock")
    class Stock {

        @Test
        @DisplayName("a stock-take of a part the warehouse never carried creates the row")
        void countingSomethingNew() {
            Part part = freshPart(0);

            // "We counted and there are 20" has to work whether or not a row existed, otherwise
            // the first stock-take after adding a part fails for no reason the counter can see.
            WarehouseStock row = stocks.setQuantity(WAREHOUSE, part.getId(), 20);
            assertThat(row.getQuantity()).isEqualTo(20);

            assertThat(stocks.setQuantity(WAREHOUSE, part.getId(), 5).getQuantity())
                    .as("and again on the existing row").isEqualTo(5);
        }

        @Test
        @DisplayName("a count of zero is a legitimate answer; a negative one is not")
        void zeroIsAllowedNegativeIsNot() {
            Part part = freshPart(4);

            assertThat(stocks.setQuantity(WAREHOUSE, part.getId(), 0).getQuantity()).isZero();
            assertThatThrownBy(() -> stocks.setQuantity(WAREHOUSE, part.getId(), -1))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("cannot be negative");
        }

        @Test
        @DisplayName("a delivery must be of something")
        void receiveRequiresUnits() {
            Part part = freshPart(1);
            for (int quantity : new int[]{0, -5}) {
                assertThatThrownBy(() -> stocks.receive(WAREHOUSE, part.getId(), quantity))
                        .as("received %d", quantity)
                        .isInstanceOf(InvalidRequestException.class);
            }
        }

        @Test
        @DisplayName("a transfer needs two warehouses, some units, and both to exist")
        void transferGuards() {
            Part part = freshPart(5);

            assertThatThrownBy(() -> stocks.transfer(WAREHOUSE, WAREHOUSE, part.getId(), 1))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("two different warehouses");

            assertThatThrownBy(() -> stocks.transfer(WAREHOUSE, 4L, part.getId(), 0))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("at least one unit");

            // The source is checked as well as the destination, even though only the
            // destination entity is used afterwards — "no such warehouse" beats a shortage
            // reported against a warehouse that was never there.
            assertThatThrownBy(() -> stocks.transfer(999999L, 4L, part.getId(), 1))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> stocks.transfer(WAREHOUSE, 999999L, part.getId(), 1))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> stocks.transfer(WAREHOUSE, 4L, 999999L, 1))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("a transfer to a warehouse that never carried the part creates the shelf")
        void transferCreatesTheDestinationRow() {
            Part part = freshPart(5);

            StockService.Transferred moved = stocks.transfer(WAREHOUSE, 4L, part.getId(), 3);

            assertThat(moved.source().getQuantity()).isEqualTo(2);
            assertThat(moved.destination().getQuantity()).isEqualTo(3);
            assertThat(moved.destination().getWarehouse().getId())
                    .as("both ends named, so the caller can tell which is which").isEqualTo(4L);
        }

        @Test
        @DisplayName("a transfer of more than there is moves nothing")
        void transferRefusesWhatIsNotThere() {
            Part part = freshPart(2);

            assertThatThrownBy(() -> stocks.transfer(WAREHOUSE, 4L, part.getId(), 3))
                    .isInstanceOf(InsufficientStockException.class);

            assertThat(onTheShelf(WAREHOUSE, part.getId()))
                    .as("the two are still where they were").isEqualTo(2);
            assertThat(onTheShelf(4L, part.getId()))
                    .as("and none of them arrived at the other end").isZero();
        }

        @Test
        @DisplayName("removing a shelf that is not empty is refused, and one that never existed is a 404")
        void removalGuards() {
            Part part = freshPart(3);

            assertThatThrownBy(() -> stocks.remove(WAREHOUSE, part.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still holds 3");

            assertThatThrownBy(() -> stocks.remove(WAREHOUSE, 999999L))
                    .isInstanceOf(NotFoundException.class);

            stocks.setQuantity(WAREHOUSE, part.getId(), 0);
            stocks.remove(WAREHOUSE, part.getId());
            assertThatThrownBy(() -> stocks.remove(WAREHOUSE, part.getId()))
                    .as("gone, so removing it again is a 404 rather than a no-op")
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
