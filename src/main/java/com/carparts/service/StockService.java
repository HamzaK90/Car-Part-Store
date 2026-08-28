package com.carparts.service;

import com.carparts.domain.Part;
import com.carparts.domain.Warehouse;
import com.carparts.domain.WarehouseStock;
import com.carparts.repository.DepartmentRepository;
import com.carparts.repository.PartRepository;
import com.carparts.repository.WarehouseStockRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Putting stock onto the shelf.
 *
 * <p>Until this existed, quantities could only ever fall: orders decremented them and nothing
 * replenished them, so every warehouse drained to zero and stayed there. Receiving a delivery is
 * not a convenience — it is the other half of the inventory story.
 */
@Service
public class StockService {

    private final WarehouseStockRepository stock;
    private final DepartmentRepository departments;
    private final PartRepository parts;

    public StockService(WarehouseStockRepository stock,
                        DepartmentRepository departments,
                        PartRepository parts) {
        this.stock = stock;
        this.departments = departments;
        this.parts = parts;
    }

    /**
     * Receives a delivery, adding to whatever is already held.
     *
     * <p>Adds rather than replaces, because two deliveries of the same part on one day are two
     * deliveries. The row is locked first: a delivery arriving while an order is being placed
     * must not overwrite the decrement that order is midway through making.
     *
     * <p>Creates the row when the warehouse has never carried this part.
     */
    @Transactional
    public WarehouseStock receive(Long warehouseId, Long partId, int quantity) {
        if (quantity <= 0) {
            throw new InvalidRequestException("a delivery must be of at least one unit");
        }
        Warehouse warehouse = warehouse(warehouseId);
        Part part = part(partId);

        List<WarehouseStock> locked = stock.lockForUpdate(warehouseId, List.of(partId));
        if (locked.isEmpty()) {
            return stock.save(new WarehouseStock(warehouse, part, quantity));
        }
        WarehouseStock row = locked.getFirst();
        row.increase(quantity);
        return row;
    }

    /**
     * Sets the quantity outright, for correcting a count after a stock-take.
     *
     * <p>Separate from {@link #receive} on purpose. "We received 20 more" and "we counted and
     * there are 20" are different statements, and a single endpoint doing both would make the
     * caller's intent ambiguous at exactly the moment it matters.
     */
    @Transactional
    public WarehouseStock setQuantity(Long warehouseId, Long partId, int quantity) {
        if (quantity < 0) {
            throw new InvalidRequestException("a stock count cannot be negative");
        }
        Warehouse warehouse = warehouse(warehouseId);
        Part part = part(partId);

        List<WarehouseStock> locked = stock.lockForUpdate(warehouseId, List.of(partId));
        if (locked.isEmpty()) {
            return stock.save(new WarehouseStock(warehouse, part, quantity));
        }
        WarehouseStock row = locked.getFirst();
        row.setQuantity(quantity);
        return row;
    }

    /**
     * Removes a part from a warehouse's shelves entirely. Refused while any remain.
     *
     * <p>Locks the row before reading it, as {@link #receive} and {@link #setQuantity} do.
     * Without the lock a delivery landing between the zero-check and the delete would be
     * discarded silently — the row it was added to simply disappears, with nothing anywhere
     * recording that the units arrived.
     */
    @Transactional
    public void remove(Long warehouseId, Long partId) {
        List<WarehouseStock> locked = stock.lockForUpdate(warehouseId, List.of(partId));
        if (locked.isEmpty()) {
            throw new NotFoundException(
                    "warehouse " + warehouseId + " holds no record of part " + partId);
        }
        WarehouseStock row = locked.getFirst();
        if (row.getQuantity() > 0) {
            // 409, not 400, for the reason InsufficientStockException gives: the request is
            // not malformed, the shelf simply is not empty, and the same request succeeds once
            // the units are moved. A 400 sends the caller off to fix a request that was fine.
            // IllegalStateException is the route to it — the domain refusing an operation, as
            // when an already-cancelled order is asked to be fulfilled.
            throw new IllegalStateException(
                    "warehouse " + warehouseId + " still holds " + row.getQuantity()
                            + " of part " + partId + "; move or write them off first");
        }
        stock.delete(row);
    }

    /**
     * Moves units from one warehouse to another, in one transaction.
     *
     * <p>The alternative is two calls — a decrease here, an increase there — with nothing
     * binding them. If the second fails the units are simply gone: taken off one shelf and never
     * put on the other, with no record that they existed. One transaction means both happen or
     * neither does.
     *
     * <p>Both rows are locked in a single statement ordered by warehouse id. Two transfers
     * running in opposite directions between the same pair would otherwise each hold what the
     * other needs and deadlock; taking them in a fixed order makes one wait instead.
     *
     * @throws InvalidRequestException if source and destination are the same, or the quantity is
     *     not positive
     * @throws InsufficientStockException if the source cannot cover the move
     */
    /**
     * Both sides of a completed transfer.
     *
     * <p>Returning only one of them was ambiguous to the point of being useless: the caller
     * addressed the source warehouse and got back a bare quantity belonging to the destination,
     * with nothing in the response naming which warehouse it described. A move has two ends and
     * the answer should show both.
     */
    public record Transferred(WarehouseStock source, WarehouseStock destination) {}

    @Transactional
    public Transferred transfer(Long fromWarehouseId, Long toWarehouseId,
                                Long partId, int quantity) {
        if (quantity <= 0) {
            throw new InvalidRequestException("a transfer must move at least one unit");
        }
        if (fromWarehouseId.equals(toWarehouseId)) {
            throw new InvalidRequestException("a transfer needs two different warehouses");
        }
        warehouse(fromWarehouseId);   // called for the 404 it throws; the entity is not needed
        Warehouse to = warehouse(toWarehouseId);
        Part part = part(partId);

        // One statement, warehouses in a fixed order, so two opposing transfers queue instead of
        // deadlocking on each other's row.
        List<Long> ordered = Stream.of(fromWarehouseId, toWarehouseId).sorted().toList();
        Map<Long, WarehouseStock> locked = stock.lockForTransfer(ordered, partId).stream()
                .collect(Collectors.toMap(s -> s.getWarehouse().getId(), Function.identity()));

        WarehouseStock source = locked.get(fromWarehouseId);
        int available = source == null ? 0 : source.getQuantity();
        if (available < quantity) {
            throw new InsufficientStockException(fromWarehouseId, List.of(
                    new InsufficientStockException.Shortage(
                            partId, part.getSku(), quantity, available)));
        }

        source.decrease(quantity);

        WarehouseStock destination = locked.get(toWarehouseId);
        if (destination == null) {
            // The destination has never carried this part; the row is created by the move.
            destination = stock.save(new WarehouseStock(to, part, quantity));
        } else {
            destination.increase(quantity);
        }
        return new Transferred(source, destination);
    }

    private Warehouse warehouse(Long id) {
        return departments.findWarehouse(id)
                .orElseThrow(() -> NotFoundException.of("warehouse", id));
    }

    private Part part(Long id) {
        return parts.findById(id).orElseThrow(() -> NotFoundException.of("part", id));
    }
}
