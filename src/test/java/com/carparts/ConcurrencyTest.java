package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.carparts.service.InsufficientStockException;
import com.carparts.service.OrderService;
import com.carparts.service.PlaceOrderCommand;
import com.carparts.service.StockService;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two people reaching for the last unit at the same moment — acceptance criterion 3, under
 * contention rather than in sequence.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> A test-level transaction would make both
 * calls share one, so the row lock could never be contended: the second call would see the
 * first's uncommitted write as its own, both would succeed, and the test would pass while
 * proving the opposite of what it claims. This is the single most important annotation absent
 * from this file, which is why it is the first thing the class says.
 *
 * <p>The consequence is that these tests commit. Each builds its own part and its own stock row
 * so nothing else in the suite is disturbed.
 */
@DisplayName("concurrency")
class ConcurrencyTest extends IntegrationTest {

    @Autowired
    private OrderService orders;

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcClient jdbc;

    private static final long WAREHOUSE = 3;
    private static final long BRANCH = 1;
    private static final long CUSTOMER = 1;

    /** A part nothing else touches, stocked with exactly {@code quantity}. */
    private long partStockedWith(int quantity) {
        long supplier = jdbc.sql("SELECT supplier_id FROM supplier LIMIT 1")
                .query(Long.class).single();
        long part = jdbc.sql("""
                INSERT INTO part (sku, name, price, weight_kg, reorder_level, supplier_id)
                VALUES (?, 'Contended part', 45.00, 1.0, 0, ?)
                RETURNING part_id
                """)
                .params("CC-" + System.nanoTime(), supplier)
                .query(Long.class).single();
        jdbc.sql("INSERT INTO warehouse_stock (warehouse_id, part_id, quantity) VALUES (?, ?, ?)")
                .params(WAREHOUSE, part, quantity)
                .update();
        return part;
    }

    private Callable<Boolean> order(long part, int quantity) {
        return () -> {
            try {
                orders.placeOrder(new PlaceOrderCommand(CUSTOMER, BRANCH, WAREHOUSE,
                        List.of(new PlaceOrderCommand.Line(part, quantity))), null);
                return true;
            } catch (InsufficientStockException e) {
                return false;
            }
        };
    }

    private int quantityOf(long part) {
        return jdbc.sql("SELECT quantity FROM warehouse_stock WHERE warehouse_id = ? AND part_id = ?")
                .params(WAREHOUSE, part)
                .query(Integer.class)
                .single();
    }

    @Test
    @DisplayName("two orders for the last unit: exactly one succeeds")
    void lastUnitGoesToExactlyOneOrder() throws Exception {
        long part = partStockedWith(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results =
                    pool.invokeAll(List.of(order(part, 1), order(part, 1)));

            long succeeded = 0;
            for (Future<Boolean> r : results) {
                if (r.get(30, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }

            assertThat(succeeded)
                    .as("SELECT … FOR UPDATE should make the second wait and then see zero")
                    .isEqualTo(1);
            assertThat(quantityOf(part)).as("and stock lands at zero, never below").isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ten orders against five units: five succeed, and stock never goes negative")
    void contendedShelfIsNeverOversold() throws Exception {
        long part = partStockedWith(5);
        int attempts = 10;

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        AtomicInteger sold = new AtomicInteger();
        try {
            List<Callable<Boolean>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                calls.add(() -> {
                    boolean ok = order(part, 1).call();
                    if (ok) {
                        sold.incrementAndGet();
                    }
                    return ok;
                });
            }
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(sold.get()).as("five units, five sales").isEqualTo(5);
        assertThat(quantityOf(part)).isZero();
    }

    @Test
    @DisplayName("opposing transfers between the same pair do not deadlock")
    void opposingTransfersQueueRatherThanDeadlock() throws Exception {
        long part = partStockedWith(10);
        jdbc.sql("INSERT INTO warehouse_stock (warehouse_id, part_id, quantity) VALUES (4, ?, 10)")
                .param(part).update();

        // lockForTransfer takes both rows in one statement ordered by warehouse id. Without
        // that ordering these two would each hold the row the other needs. A deadlock would
        // show up as the timeout below rather than as a hang, so the failure is loud.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(
                    transfer(3, 4, part, 3),
                    transfer(4, 3, part, 3)));
            for (Future<Boolean> r : results) {
                assertThat(r.get(30, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        int total = quantityOf(part)
                + jdbc.sql("SELECT quantity FROM warehouse_stock WHERE warehouse_id = 4 AND part_id = ?")
                        .param(part).query(Integer.class).single();
        assertThat(total).as("whatever leaves one side arrives on the other").isEqualTo(20);
    }

    private Callable<Boolean> transfer(long from, long to, long part, int quantity) {
        return () -> {
            stockService.transfer(from, to, part, quantity);
            return true;
        };
    }
}
