package com.carparts.service;

import com.carparts.domain.Branch;
import com.carparts.domain.Customer;
import com.carparts.domain.CustomerOrder;
import com.carparts.domain.Employee;
import com.carparts.domain.OrderStatus;
import com.carparts.domain.Part;
import com.carparts.domain.Warehouse;
import com.carparts.domain.WarehouseStock;
import com.carparts.repository.CustomerOrderRepository;
import com.carparts.repository.CustomerRepository;
import com.carparts.repository.DepartmentRepository;
import com.carparts.repository.EmployeeRepository;
import com.carparts.repository.PartRepository;
import com.carparts.repository.WarehouseStockRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Placing an order: the one operation in this system where getting the order of events wrong
 * costs real money.
 *
 * <p>Everything below happens in a single transaction. Either an order exists and stock has been
 * decremented to match, or neither happened. There is no state in between that another request
 * could observe.
 */
@Service
public class OrderService {

    private final CustomerRepository customers;
    private final DepartmentRepository departments;
    private final EmployeeRepository employees;
    private final PartRepository parts;
    private final WarehouseStockRepository stock;
    private final CustomerOrderRepository orders;

    public OrderService(CustomerRepository customers,
                        DepartmentRepository departments,
                        EmployeeRepository employees,
                        PartRepository parts,
                        WarehouseStockRepository stock,
                        CustomerOrderRepository orders) {
        this.customers = customers;
        this.departments = departments;
        this.employees = employees;
        this.parts = parts;
        this.stock = stock;
        this.orders = orders;
    }

    /**
     * Places an order and takes the parts out of stock.
     *
     * <p>The sequence matters and is the whole point of the method:
     *
     * <ol>
     *   <li>check the shape of the request before touching the database
     *   <li>load the customer, branch, warehouse, handler and parts, failing on anything missing
     *   <li>lock the stock rows with {@code SELECT … FOR UPDATE}
     *   <li>compare demand against what those locked rows say
     *   <li>build the order, capturing each part's price as it stands now
     *   <li>save, and decrement the rows still held under the lock
     * </ol>
     *
     * <p>Steps 3 and 4 are inseparable. Reading stock without the lock, then deciding, would
     * leave a window in which another transaction sells the same unit — and both callers would
     * have been told there was enough. Holding the lock until commit is what makes acceptance
     * criterion 3 hold under concurrency rather than only when tested one request at a time.
     *
     * @param handlingEmployeeId the salesperson from the authenticated session, or null if the
     *     order was not taken by a named member of staff. Deliberately a parameter rather than a
     *     field of the command: a request body can never supply it.
     * @throws NotFoundException if the customer, branch, warehouse, handler or any part is
     *     unknown — including an id that exists but is of the wrong kind
     * @throws InvalidRequestException if the order has no lines, a quantity is not positive, or
     *     the handler works at a different branch
     * @throws InsufficientStockException if the warehouse cannot cover it, listing every short
     *     part; nothing is written
     */
    @Transactional
    public CustomerOrder placeOrder(PlaceOrderCommand command, Long handlingEmployeeId) {
        requireIds(command);
        Map<Long, Integer> demand = demandByPart(command);

        Customer customer = customers.findById(command.customerId())
                .orElseThrow(() -> NotFoundException.of("customer", command.customerId()));
        Branch branch = departments.findBranch(command.branchId())
                .orElseThrow(() -> NotFoundException.of("branch", command.branchId()));
        Warehouse warehouse = departments.findWarehouse(command.warehouseId())
                .orElseThrow(() -> NotFoundException.of("warehouse", command.warehouseId()));
        Employee handler = resolveHandler(handlingEmployeeId, branch);
        Map<Long, Part> requested = loadParts(demand.keySet());

        // The rows stay locked until this transaction ends, so what they say below is still true
        // when we act on it. Ordered by part id inside the query, because two transactions taking
        // the same rows in different orders would deadlock instead of queueing.
        Map<Long, WarehouseStock> locked = stock
                .lockForUpdate(warehouse.getId(), demand.keySet().stream().sorted().toList())
                .stream()
                .collect(Collectors.toMap(s -> s.getPart().getId(), Function.identity()));

        rejectIfShort(warehouse, demand, requested, locked);

        CustomerOrder order = new CustomerOrder(customer, branch, warehouse);
        order.setEmployee(handler);
        demand.forEach((partId, quantity) -> order.addLine(requested.get(partId), quantity));

        // Saved before the decrement so the order has an id; both land in the same commit, so the
        // sequence within the transaction is invisible from outside it.
        CustomerOrder saved = orders.save(order);
        demand.forEach((partId, quantity) -> locked.get(partId).decrease(quantity));
        return saved;
    }

    /**
     * Marks an order delivered.
     *
     * <p>Stock is untouched: it left the shelf when the order was placed, and fulfilment only
     * records that it reached the customer. Decrementing again here would double-count, which is
     * exactly the mistake this method exists to not make.
     *
     * <p>Loaded with {@code findByIdWithItems} rather than {@code findById}, as {@link #cancel}
     * and {@link #amendLines} already were. Fulfilment itself needs nothing but the status, so a
     * plain finder looked sufficient — but the caller is handed the whole order back, and
     * {@code open-in-view} is disabled, so rendering it walked a lazy customer proxy after this
     * transaction had closed and threw. The write had already committed by then, so the order
     * really was fulfilled and the caller was told the server had failed: the worst shape a bug
     * can take, because retrying looks like the right thing to do and is not.
     *
     * @throws NotFoundException if there is no such order
     * @throws IllegalStateException if it is no longer PLACED — mapped to 409
     */
    @Transactional
    public CustomerOrder fulfil(Long orderId) {
        CustomerOrder order = orders.findByIdWithItems(orderId)
                .orElseThrow(() -> NotFoundException.of("order", orderId));
        order.fulfil();
        return order;
    }

    /**
     * Cancels an order and puts the parts back on the shelf.
     *
     * <p>The restoration is the point. {@code CustomerOrder.cancel()} only changes a status, and
     * wiring that to an endpoint on its own would leak inventory silently — stock removed when
     * the order was placed, never returned, and nothing anywhere reporting a discrepancy.
     *
     * <p>Rows are locked exactly as {@code placeOrder} locks them, in the same part-id order, so
     * a cancellation and a sale contending for the same row queue rather than deadlock.
     *
     * <p>A stock row that has since been deleted is recreated rather than skipped. Losing the
     * returned units because the row went away would be the same silent leak by another route.
     *
     * @throws IllegalStateException if the order is not PLACED — a fulfilled order has already
     *     reached the customer, and reversing that is a return, which this schema does not model
     */
    @Transactional
    public CustomerOrder cancel(Long orderId) {
        CustomerOrder order = orders.findByIdWithItems(orderId)
                .orElseThrow(() -> NotFoundException.of("order", orderId));

        order.cancel();   // refuses anything already FULFILLED or CANCELLED

        Map<Long, Integer> returning = new LinkedHashMap<>();
        order.getItems().forEach(i -> returning.merge(i.getPart().getId(), i.getQuantity(), Integer::sum));

        Warehouse warehouse = order.getWarehouse();
        Map<Long, WarehouseStock> locked = stock
                .lockForUpdate(warehouse.getId(), returning.keySet().stream().sorted().toList())
                .stream()
                .collect(Collectors.toMap(s -> s.getPart().getId(), Function.identity()));

        order.getItems().forEach(item -> {
            Long partId = item.getPart().getId();
            WarehouseStock row = locked.get(partId);
            if (row == null) {
                stock.save(new WarehouseStock(warehouse, item.getPart(), item.getQuantity()));
            } else {
                row.increase(item.getQuantity());
            }
        });

        return order;
    }

    /**
     * Replaces the lines of an order that has not yet been fulfilled.
     *
     * <p>Takes the complete desired set rather than a delta, because a caller saying "make it
     * five" should not have to know it is currently three. The delta is worked out here, against
     * rows locked exactly as {@code placeOrder} locks them.
     *
     * <p>What moves in each direction:
     *
     * <ul>
     *   <li>an increased or added line takes more stock, and is refused if the warehouse is short
     *   <li>a reduced or removed line puts stock back
     *   <li>a line already on the order keeps the {@code unitPrice} it was sold at; a newly added
     *       part is billed at today's catalogue price
     * </ul>
     *
     * <p>That price rule is the point of amending rather than cancelling and re-placing: the
     * customer keeps the price they were quoted on what they already ordered.
     *
     * @throws IllegalStateException if the order is no longer PLACED — its stock has already
     *     moved on, and the schema does not model a return
     * @throws InvalidRequestException if the new set is empty; {@code ct_order_has_lines} would
     *     refuse it at commit anyway, and a clear message beats a constraint violation
     */
    @Transactional
    public CustomerOrder amendLines(Long orderId, List<PlaceOrderCommand.Line> requested) {
        CustomerOrder order = orders.findByIdWithItems(orderId)
                .orElseThrow(() -> NotFoundException.of("order", orderId));

        if (order.getStatus() != OrderStatus.PLACED) {
            throw new IllegalStateException(
                    "order " + orderId + " is " + order.getStatus() + " and can no longer be changed");
        }
        if (requested == null || requested.isEmpty()) {
            throw new InvalidRequestException(
                    "an order must keep at least one line; cancel it instead");
        }

        Map<Long, Integer> desired = new LinkedHashMap<>();
        for (PlaceOrderCommand.Line line : requested) {
            if (line == null || line.partId() == null) {
                throw new InvalidRequestException("every line must name a part");
            }
            if (line.quantity() <= 0) {
                throw new InvalidRequestException(
                        "quantity for part " + line.partId() + " must be greater than zero;"
                                + " omit the line to remove it");
            }
            desired.merge(line.partId(), line.quantity(), Integer::sum);
        }

        Map<Long, Integer> current = new LinkedHashMap<>();
        order.getItems().forEach(i -> current.merge(i.getPart().getId(), i.getQuantity(), Integer::sum));

        // Every part on either side of the change, so one lock covers the whole amendment.
        List<Long> touched = Stream.concat(current.keySet().stream(), desired.keySet().stream())
                .distinct().sorted().toList();

        Map<Long, Part> partsById = loadParts(desired.keySet());
        Warehouse warehouse = order.getWarehouse();
        Map<Long, WarehouseStock> locked = stock.lockForUpdate(warehouse.getId(), touched).stream()
                .collect(Collectors.toMap(s -> s.getPart().getId(), Function.identity()));

        // Check every increase before applying any of them, so a refusal changes nothing.
        List<InsufficientStockException.Shortage> shortages = new ArrayList<>();
        desired.forEach((partId, want) -> {
            int extra = want - current.getOrDefault(partId, 0);
            if (extra <= 0) {
                return;
            }
            WarehouseStock row = locked.get(partId);
            int available = row == null ? 0 : row.getQuantity();
            if (available < extra) {
                shortages.add(new InsufficientStockException.Shortage(
                        partId, partsById.get(partId).getSku(), extra, available));
            }
        });
        if (!shortages.isEmpty()) {
            shortages.sort(Comparator.comparing(InsufficientStockException.Shortage::sku));
            throw new InsufficientStockException(warehouse.getId(), shortages);
        }

        applyLineChanges(order, desired, current, partsById);
        applyStockChanges(order, warehouse, desired, current, locked, partsById);
        return order;
    }

    /** Updates, adds and removes the lines themselves. */
    private void applyLineChanges(CustomerOrder order, Map<Long, Integer> desired,
                                  Map<Long, Integer> current, Map<Long, Part> partsById) {
        // Removals first, so the collection is not being grown and shrunk at once.
        order.getItems().removeIf(item -> !desired.containsKey(item.getPart().getId()));

        desired.forEach((partId, want) -> {
            if (current.containsKey(partId)) {
                order.getItems().stream()
                        .filter(i -> i.getPart().getId().equals(partId))
                        .findFirst()
                        .ifPresent(i -> i.setQuantity(want));
            } else {
                // New to this order, so billed at what the catalogue says today.
                order.addLine(partsById.get(partId), want);
            }
        });
    }

    /** Moves stock by the difference, in whichever direction each part went. */
    private void applyStockChanges(CustomerOrder order, Warehouse warehouse,
                                   Map<Long, Integer> desired, Map<Long, Integer> current,
                                   Map<Long, WarehouseStock> locked, Map<Long, Part> partsById) {
        Stream.concat(current.keySet().stream(), desired.keySet().stream())
                .distinct()
                .forEach(partId -> {
                    int delta = desired.getOrDefault(partId, 0) - current.getOrDefault(partId, 0);
                    if (delta == 0) {
                        return;
                    }
                    WarehouseStock row = locked.get(partId);
                    if (row == null) {
                        // Only reachable when stock is coming back and the row has since gone.
                        stock.save(new WarehouseStock(warehouse, partsById.get(partId), -delta));
                    } else if (delta > 0) {
                        row.decrease(delta);
                    } else {
                        row.increase(-delta);
                    }
                });
    }

    /**
     * Rejects a command that omits one of the three ids every order needs.
     *
     * <p>Without this the failures are inconsistent and unhelpful: a null customer id reaches
     * {@code findById}, which asserts non-null and throws {@code IllegalArgumentException} — a
     * 500 for what is plainly a bad request — while a null branch id slips into the JPQL
     * comparison, matches nothing, and reports "branch null does not exist".
     *
     * <p>Bean Validation on the request body will catch these at step 6 too. That is not a
     * reason to skip it here: the service is also called from tests and, later, from the invoice
     * and reporting paths, and it should not depend on someone else having validated first.
     */
    private void requireIds(PlaceOrderCommand command) {
        if (command == null) {
            throw new InvalidRequestException("no order was supplied");
        }
        if (command.customerId() == null) {
            throw new InvalidRequestException("an order must name a customer");
        }
        if (command.branchId() == null) {
            throw new InvalidRequestException("an order must name the branch that took it");
        }
        if (command.warehouseId() == null) {
            throw new InvalidRequestException("an order must name the warehouse filling it");
        }
    }

    /**
     * Folds the requested lines into one quantity per part, rejecting anything malformed.
     *
     * <p>Adding duplicates together is a correctness requirement, not a tidy-up: two lines of
     * three would otherwise be checked as three and then three again, selling six units of a
     * part that had four.
     */
    private Map<Long, Integer> demandByPart(PlaceOrderCommand command) {
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new InvalidRequestException("an order must contain at least one line");
        }
        Map<Long, Integer> demand = new LinkedHashMap<>();
        for (PlaceOrderCommand.Line line : command.lines()) {
            if (line == null) {
                throw new InvalidRequestException("an order line cannot be empty");
            }
            if (line.partId() == null) {
                throw new InvalidRequestException("every line must name a part");
            }
            if (line.quantity() <= 0) {
                throw new InvalidRequestException(
                        "quantity for part " + line.partId() + " must be greater than zero");
            }
            demand.merge(line.partId(), line.quantity(), Integer::sum);
        }
        return demand;
    }

    /**
     * Resolves the handler and checks they belong to the branch that took the order.
     *
     * <p>The same rule {@code ct_order_employee_at_branch} enforces. Checking it here turns what
     * would surface as a trigger exception into a message naming the employee and the branch;
     * the trigger stays the guarantee, because it holds however the row is written.
     */
    private Employee resolveHandler(Long employeeId, Branch branch) {
        if (employeeId == null) {
            return null;
        }
        Employee handler = employees.findById(employeeId)
                .orElseThrow(() -> NotFoundException.of("employee", employeeId));
        if (!handler.worksAt(branch)) {
            throw new InvalidRequestException(
                    handler.getFullName() + " does not work at " + branch.getName()
                            + " and cannot handle its orders");
        }
        return handler;
    }

    /**
     * Loads every requested part, failing if any is unknown.
     *
     * <p>Done before the stock check so a typo in a part id reports as "no such part" rather than
     * as a shortage of something that never existed.
     */
    private Map<Long, Part> loadParts(Iterable<Long> partIds) {
        Map<Long, Part> found = parts.findAllById(partIds).stream()
                .collect(Collectors.toMap(Part::getId, Function.identity()));
        List<Long> missing = new ArrayList<>();
        for (Long id : partIds) {
            if (!found.containsKey(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new NotFoundException("no such part: " + missing.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", ")));
        }
        return found;
    }

    /**
     * Compares demand against the locked rows and refuses the whole order if anything is short.
     *
     * <p>All shortages are collected rather than throwing on the first, so one response tells the
     * caller everything they need to fix.
     *
     * <p>A part with no row in this warehouse counts as zero available. From the caller's side
     * there is no difference between a warehouse that has run out and one that never stocked it.
     */
    private void rejectIfShort(Warehouse warehouse,
                               Map<Long, Integer> demand,
                               Map<Long, Part> requested,
                               Map<Long, WarehouseStock> locked) {
        List<InsufficientStockException.Shortage> shortages = new ArrayList<>();
        demand.forEach((partId, quantity) -> {
            WarehouseStock row = locked.get(partId);
            int available = row == null ? 0 : row.getQuantity();
            if (available < quantity) {
                shortages.add(new InsufficientStockException.Shortage(
                        partId, requested.get(partId).getSku(), quantity, available));
            }
        });
        if (!shortages.isEmpty()) {
            shortages.sort(Comparator.comparing(InsufficientStockException.Shortage::sku));
            throw new InsufficientStockException(warehouse.getId(), shortages);
        }
    }
}
