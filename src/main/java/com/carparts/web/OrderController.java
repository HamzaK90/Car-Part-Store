package com.carparts.web;

import com.carparts.domain.CustomerOrder;
import com.carparts.domain.OrderStatus;
import com.carparts.repository.CustomerOrderRepository;
import com.carparts.repository.ReportingRepository;
import com.carparts.repository.ReportingRepository.OrderFilter;
import com.carparts.repository.ReportingRepository.OrderSort;
import com.carparts.repository.ReportingRepository.OrderSummary;
import com.carparts.security.AuthenticatedUser;
import com.carparts.service.NotFoundException;
import com.carparts.service.OrderService;
import com.carparts.service.PlaceOrderCommand;
import com.carparts.web.dto.Requests.AmendOrderRequest;
import com.carparts.web.dto.Requests.PlaceOrderRequest;
import com.carparts.web.dto.Responses.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orders.
 *
 * <p>No {@code @Transactional} anywhere in here, deliberately. {@code open-in-view} is disabled
 * in {@code application.yml}, and holding a transaction open across serialization to let lazy
 * associations resolve would quietly reinstate the same thing — along with the query storm it
 * hides. Every method below is handed data already complete: the listing comes back as flat
 * rows, and the single-order read fetches its associations in the query.
 *
 * <p><b>Every mutating endpoint here carries an explicit rule.</b> These are operational rather than administrative — taking an order, moving stock — so they are open to any authenticated member of staff rather than to ADMIN alone. Saying {@code isAuthenticated()} out loud is deliberate: it makes an endpoint with <em>no</em> annotation an anomaly a reader can spot, instead of leaving "open to everyone" and "somebody forgot" looking identical. The filter chain already requires authentication for every path, so these add no enforcement today; they are here so that a {@code permitAll} matcher added later cannot silently open a write.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Placing, amending and reading orders")
public class OrderController {

    private final OrderService orderService;
    private final CustomerOrderRepository orders;
    private final ReportingRepository reporting;

    public OrderController(OrderService orderService,
                           CustomerOrderRepository orders,
                           ReportingRepository reporting) {
        this.orderService = orderService;
        this.orders = orders;
        this.reporting = reporting;
    }

    /**
     * Places an order and takes the parts out of stock, in one transaction.
     *
     * <p>The handling employee comes from the token, never the body — see {@link #handlerFor}.
     * Wiring it from the request would let a salesperson record an order as handled by a
     * colleague, so {@code PlaceOrderRequest} has no field to say it in.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    @Operation(summary = "Place an order",
               description = "Decrements warehouse stock and captures each part's price at sale. "
                       + "The handling employee is taken from your token, not the body, and must "
                       + "work at the branch named.")
    public ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser caller) {
        PlaceOrderCommand command = new PlaceOrderCommand(
                request.customerId(), request.branchId(), request.warehouseId(),
                request.lines().stream()
                        .map(l -> new PlaceOrderCommand.Line(l.partId(), l.quantity()))
                        .toList());

        CustomerOrder placed = orderService.placeOrder(command, handlerFor(caller));

        return ResponseEntity
                .created(URI.create("/api/orders/" + placed.getId()))
                .body(OrderResponse.from(placed));
    }

    /**
     * Lists orders, narrowed by any combination of the filters below.
     *
     * <p>Returns summaries, not full orders: what a list needs is what each order is worth, not
     * an itemisation of every one. Reading {@code v_order_total} gives that in two statements
     * whatever the page size — against roughly six per row when the same list was built from
     * entities, which made a full page cost several hundred queries.
     *
     * <p>{@code warehouseId} is the filter warehouse staff need: their own picking queue rather
     * than every branch's orders. Combined with {@code status=PLACED} it is the work list.
     */
    @GetMapping
    @Operation(summary = "List orders",
               description = "status=PLACED&warehouseId=N&sort=OLDEST is a warehouse's picking queue.")
    public Page<OrderSummary> list(
            @Parameter(description = "one or more of PLACED, FULFILLED, CANCELLED. Omit for all")
            @RequestParam(required = false) List<OrderStatus> status,
            @Parameter(description = "orders taken at this branch")
            @RequestParam(required = false) Long branchId,
            @Parameter(description = "orders filled from this warehouse")
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long customerId,
            @Parameter(description = "orders handled by this employee")
            @RequestParam(required = false) Long employeeId,
            @Parameter(description = "orders containing this part — the recall question")
            @RequestParam(required = false) Long partId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) BigDecimal minTotal,
            @RequestParam(required = false) BigDecimal maxTotal,
            @Parameter(description = "NEWEST (default), OLDEST for a FIFO picking queue, LARGEST, SMALLEST")
            @RequestParam(defaultValue = "NEWEST") OrderSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int limit = Paging.size(size);
        int number = Paging.number(page);

        OrderFilter filter = new OrderFilter(
                status, branchId, warehouseId, customerId, employeeId, partId,
                from, to, minTotal, maxTotal);

        List<OrderSummary> content =
                reporting.orderSummaries(filter, sort, limit, (long) number * limit);

        return new PageImpl<>(content, PageRequest.of(number, limit), reporting.countOrders(filter));
    }

    /** One order with its lines, fetched in a single query. */
    @GetMapping("/{id}")
    @Operation(summary = "Read an order")
    public OrderResponse get(@PathVariable Long id) {
        return orders.findByIdWithItems(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> NotFoundException.of("order", id));
    }

    /**
     * Replaces the lines of an order that has not been fulfilled.
     *
     * <p>Takes the complete desired set, not a delta — a caller saying "make it five" should not
     * have to know it is currently three. Stock moves by the difference in whichever direction
     * each part went, under the same locks placement uses.
     *
     * <p>Lines already on the order keep the price they were sold at; a newly added part is
     * billed at today's catalogue price. That is the reason to amend rather than cancel and
     * re-place: the customer keeps the quote they were given, and the order keeps its identity
     * and its date.
     *
     * <p>PATCH rather than PUT because it changes the lines and leaves the rest of the order —
     * customer, branch, warehouse, date — alone.
     */
    @PreAuthorize("isAuthenticated()")
    @PatchMapping("/{id}/lines")
    @Operation(summary = "Amend an order's lines",
               description = "Send the full desired set. Existing lines keep their original price.")
    public OrderResponse amend(@PathVariable Long id, @Valid @RequestBody AmendOrderRequest request) {
        List<PlaceOrderCommand.Line> lines = request.lines().stream()
                .map(l -> new PlaceOrderCommand.Line(l.partId(), l.quantity()))
                .toList();
        return OrderResponse.from(orderService.amendLines(id, lines));
    }

    /**
     * Marks an order delivered.
     *
     * <p>Stock is not touched: it left the shelf when the order was placed. Decrementing again
     * here would double-count.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/fulfil")
    @Operation(summary = "Mark an order delivered",
               description = "Only a PLACED order can be fulfilled. Stock is unchanged.")
    public OrderResponse fulfil(@PathVariable Long id) {
        return OrderResponse.from(orderService.fulfil(id));
    }

    /**
     * Cancels an order and returns the parts to the shelf.
     *
     * <p>The restoration is the substance. Changing the status alone would leak inventory
     * silently — stock removed at placement, never given back, nothing reporting the gap.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order",
               description = "Returns the parts to warehouse stock. Only a PLACED order can be cancelled.")
    public OrderResponse cancel(@PathVariable Long id) {
        return OrderResponse.from(orderService.cancel(id));
    }

    /**
     * The employee behind this request, or null when there is nobody on the payroll.
     *
     * <p>Taken from the token, never from the body — which is why {@code PlaceOrderRequest} has
     * no employee field at all. Leaving it out of the record is what makes it structural: a
     * salesperson cannot record an order as handled by a colleague, because there is nowhere in
     * the request to say so.
     *
     * <p>Null is a legitimate answer. {@code app_user.employee_id} is nullable for an account
     * that belongs to nobody on the payroll — an administrator or an integration — and
     * {@code customer_order.employee_id} is nullable to match, which V6 exercises with an order
     * seeded as "taken without a named salesperson". Such a caller may still place an order; it
     * simply records no handler rather than being refused.
     *
     * <p>When a handler <em>is</em> named, {@code OrderService.resolveHandler} insists they work
     * at the branch that took the order, and {@code ct_order_employee_at_branch} insists on it
     * again in the database. That now bites for the first time: before this, the handler was
     * always null and the rule had nothing to check. Warehouse staff therefore cannot place
     * branch orders, which is the rule working rather than a regression.
     *
     * <p>The null check on {@code caller} is not reachable today — the endpoint requires
     * authentication, so a principal is always present. It is here because
     * {@code @AuthenticationPrincipal} yields null on an anonymous request, and an endpoint
     * opened later should record no handler rather than throw.
     */
    private Long handlerFor(AuthenticatedUser caller) {
        return caller == null ? null : caller.employeeId();
    }
}
