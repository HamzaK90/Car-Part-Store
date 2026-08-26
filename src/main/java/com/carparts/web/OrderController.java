package com.carparts.web;

import com.carparts.domain.CustomerOrder;
import com.carparts.domain.OrderStatus;
import com.carparts.repository.CustomerOrderRepository;
import com.carparts.repository.ReportingRepository;
import com.carparts.repository.ReportingRepository.OrderFilter;
import com.carparts.repository.ReportingRepository.OrderSort;
import com.carparts.repository.ReportingRepository.OrderSummary;
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
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Placing, amending and reading orders")
public class OrderController {

    private static final int MAX_PAGE_SIZE = 100;

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
     * <p>The handling employee is not read from the body. Step 7 will take it from the
     * authenticated session; until then an order simply has no named handler. Wiring it from the
     * request would let a salesperson record an order as handled by a colleague.
     */
    @PostMapping
    @Operation(summary = "Place an order",
               description = "Decrements warehouse stock and captures each part's price at sale.")
    public ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderCommand command = new PlaceOrderCommand(
                request.customerId(), request.branchId(), request.warehouseId(),
                request.lines().stream()
                        .map(l -> new PlaceOrderCommand.Line(l.partId(), l.quantity()))
                        .toList());

        CustomerOrder placed = orderService.placeOrder(command, handlerFromSession());

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

        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int number = Math.max(page, 0);

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
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order",
               description = "Returns the parts to warehouse stock. Only a PLACED order can be cancelled.")
    public OrderResponse cancel(@PathVariable Long id) {
        return OrderResponse.from(orderService.cancel(id));
    }

    /**
     * Who is placing this order. Null until step 7 puts a real identity behind the request.
     *
     * <p>A method rather than a literal so there is one obvious place to change it, and so the
     * absence reads as deliberate rather than as a forgotten argument.
     */
    private Long handlerFromSession() {
        return null;
    }
}
