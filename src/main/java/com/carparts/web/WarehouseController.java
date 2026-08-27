package com.carparts.web;

import com.carparts.repository.DepartmentRepository;
import com.carparts.repository.PartRepository;
import com.carparts.repository.WarehouseStockRepository;
import com.carparts.service.NotFoundException;
import com.carparts.service.StockService;
import com.carparts.web.dto.Requests.ReceiveStockRequest;
import com.carparts.web.dto.Requests.StockCountRequest;
import com.carparts.web.dto.Requests.TransferStockRequest;
import com.carparts.web.dto.Responses.PartLocationResponse;
import com.carparts.web.dto.Responses.StockResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock held at warehouses.
 *
 * <p>No {@code @Transactional} here: every query fetches the part or warehouse it reports on,
 * so nothing is left to resolve while the response is written — see {@link OrderController}.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Stock", description = "What each warehouse holds, and moving it about")
public class WarehouseController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WarehouseStockRepository stock;
    private final DepartmentRepository departments;
    private final PartRepository parts;
    private final StockService stockService;

    public WarehouseController(WarehouseStockRepository stock,
                               DepartmentRepository departments,
                               PartRepository parts,
                               StockService stockService) {
        this.stock = stock;
        this.departments = departments;
        this.parts = parts;
        this.stockService = stockService;
    }

    /**
     * What this warehouse holds.
     *
     * <p>Paged and capped, like every other listing. It previously returned a bare array of
     * every row: fine for a demo warehouse with ten parts, not for a real one with thousands.
     *
     * <p>{@code findWarehouse} rather than {@code findById}: handed a branch's id this 404s
     * instead of returning an empty list, which would read as "this branch holds nothing"
     * rather than "branches do not hold stock".
     */
    @GetMapping("/warehouses/{id}/stock")
    @Operation(summary = "List a warehouse's stock",
               description = "lowOnly=true narrows to rows below their part's reorder level.")
    public Page<StockResponse> stock(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean lowOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (departments.findWarehouse(id).isEmpty()) {
            throw NotFoundException.of("warehouse", id);
        }
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.by("part.sku"));

        return stock.findByWarehouse(id, lowOnly, pageable).map(StockResponse::from);
    }

    /**
     * Where a part can be found.
     *
     * <p>The fulfilment question — <em>which warehouse has this?</em> — which was otherwise
     * unanswerable without asking each warehouse in turn. Warehouses holding none are left out:
     * somewhere with zero is not somewhere to send a picker.
     *
     * <p>Most stock first, so the obvious place to pick from is the first row.
     */
    @GetMapping("/parts/{id}/stock")
    @Operation(summary = "Find which warehouses hold a part",
               description = "Ordered by quantity, most first. Warehouses holding none are omitted.")
    public List<PartLocationResponse> locations(@PathVariable Long id) {
        if (!parts.existsById(id)) {
            throw NotFoundException.of("part", id);
        }
        return stock.findByPartId(id).stream().map(PartLocationResponse::from).toList();
    }

    /**
     * Receives a delivery, adding to whatever the warehouse already holds.
     *
     * <p>Adds rather than replaces: two deliveries of the same part in a day are two deliveries.
     * To correct a count after a physical stock-take, use the PUT below.
     */
    @PostMapping("/warehouses/{id}/stock")
    @Operation(summary = "Receive a delivery",
               description = "Adds to the current quantity, creating the row if it does not exist.")
    public StockResponse receive(
            @PathVariable Long id, @Valid @RequestBody ReceiveStockRequest request) {
        return StockResponse.from(stockService.receive(id, request.partId(), request.quantity()));
    }

    /**
     * Moves units to another warehouse, in one transaction.
     *
     * <p>One request, not a decrease here and an increase there. Two calls can half-fail, and
     * the units are then simply gone — taken off one shelf, never put on the other, with nothing
     * recording that they existed.
     *
     * <p>409 with the shortfall if the source cannot cover it; nothing moves.
     */
    @PostMapping("/warehouses/{id}/stock/transfer")
    @Operation(summary = "Transfer stock to another warehouse",
               description = "Atomic: both sides move together or neither does.")
    public StockResponse transfer(
            @PathVariable Long id, @Valid @RequestBody TransferStockRequest request) {
        return StockResponse.from(stockService.transfer(
                id, request.toWarehouseId(), request.partId(), request.quantity()));
    }

    /**
     * Sets a quantity outright, after counting the shelf.
     *
     * <p>Deliberately a different endpoint from receiving. "We received 20 more" and "we counted
     * and there are 20" are different claims, and one endpoint serving both would leave the
     * caller's intent ambiguous exactly where it matters.
     */
    @PutMapping("/warehouses/{id}/stock/{partId}")
    @Operation(summary = "Correct a stock count",
               description = "Sets the quantity outright, for use after a physical stock-take.")
    public StockResponse count(
            @PathVariable Long id,
            @PathVariable Long partId,
            @Valid @RequestBody StockCountRequest request) {
        return StockResponse.from(stockService.setQuantity(id, partId, request.quantity()));
    }

    /** Stops a warehouse carrying a part at all. Refused while any units remain. */
    @DeleteMapping("/warehouses/{id}/stock/{partId}")
    @Operation(summary = "Stop carrying a part",
               description = "Refused while the warehouse still holds units of it.")
    public ResponseEntity<Void> removeLine(
            @Parameter(description = "the warehouse") @PathVariable Long id,
            @PathVariable Long partId) {
        stockService.remove(id, partId);
        return ResponseEntity.noContent().build();
    }
}
