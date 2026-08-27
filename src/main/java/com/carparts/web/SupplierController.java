package com.carparts.web;

import com.carparts.domain.Supplier;
import com.carparts.repository.SupplierRepository;
import com.carparts.service.SupplierService;
import com.carparts.web.dto.Requests.CreateSupplierRequest;
import com.carparts.web.dto.Requests.UpdateSupplierRequest;
import com.carparts.web.dto.Responses.SupplierResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Vendors the shop buys from. No {@code @Transactional} — see {@link CustomerController}. */
@RestController
@RequestMapping("/api/suppliers")
@Tag(name = "Suppliers", description = "Vendors the shop buys from")
public class SupplierController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SupplierService service;
    private final SupplierRepository suppliers;

    public SupplierController(SupplierService service, SupplierRepository suppliers) {
        this.service = service;
        this.suppliers = suppliers;
    }

    /**
     * Lists suppliers, or finds one.
     *
     * <p>Searching by name is how a caller gets the id that
     * {@code GET /api/parts?supplierId=} wants, so the two compose: find the vendor here, then
     * list what the shop buys from them.
     */
    @GetMapping
    @Operation(summary = "List or search suppliers",
               description = "search matches name or phone number, case-insensitively.")
    public Page<SupplierResponse> list(
            @Parameter(description = "matched against name and phone number")
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.by("name"));

        return suppliers.search(search, pageable).map(SupplierResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one supplier")
    public SupplierResponse get(@PathVariable Long id) {
        return SupplierResponse.from(service.get(id));
    }

    /** A duplicate name comes back as 409 from {@code uq_supplier_name}. */
    @PostMapping
    @Operation(summary = "Add a supplier", description = "The name must be unique.")
    public ResponseEntity<SupplierResponse> create(
            @Valid @RequestBody CreateSupplierRequest request) {
        Supplier created = service.create(
                request.name(), request.city(), request.street(), request.phoneNumber());
        return ResponseEntity
                .created(URI.create("/api/suppliers/" + created.getId()))
                .body(SupplierResponse.from(created));
    }

    /**
     * Changes only the fields supplied.
     *
     * <p>The address is one embedded value, so naming only the city keeps the street rather than
     * blanking it.
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Update a supplier",
               description = "Only the fields supplied change; a partial address keeps what it omits.")
    public SupplierResponse update(
            @PathVariable Long id, @Valid @RequestBody UpdateSupplierRequest request) {
        return SupplierResponse.from(service.update(
                id, request.name(), request.city(), request.street(), request.phoneNumber()));
    }

    /**
     * Removes a supplier.
     *
     * <p>Refused with 409 while any part still names them. {@code part.supplier_id} is NOT NULL,
     * so those parts have nowhere to go — deleting the vendor would have to take the catalogue
     * with it.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a supplier",
               description = "Refused while parts in the catalogue still come from them.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
