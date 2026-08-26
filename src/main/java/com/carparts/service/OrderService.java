package com.carparts.service;

import com.carparts.domain.Branch;
import com.carparts.domain.Customer;
import com.carparts.domain.CustomerOrder;
import com.carparts.domain.Employee;
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
