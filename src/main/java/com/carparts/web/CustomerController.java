package com.carparts.web;

import com.carparts.domain.Customer;
import com.carparts.repository.CustomerRepository;
import com.carparts.service.CustomerService;
import com.carparts.web.dto.Requests.CreateCustomerRequest;
import com.carparts.web.dto.Requests.UpdateCustomerRequest;
import com.carparts.web.dto.Responses.CustomerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
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

/**
 * People who buy parts.
 *
 * <p>No {@code @Transactional} here. A customer's response is built entirely from its own
 * columns, so there is nothing lazy left to resolve while it is being written — see the note on
 * {@link OrderController}.
 */
@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customers", description = "People who buy parts")
public class CustomerController {

    private final CustomerService service;
    private final CustomerRepository customers;

    public CustomerController(CustomerService service, CustomerRepository customers) {
        this.service = service;
        this.customers = customers;
    }

    /**
     * Lists customers, or finds one.
     *
     * <p>{@code search} matches name, phone number or email. Phone is the one a counter actually
     * asks for, so it is matched as readily as the name.
     */
    @GetMapping
    @Operation(summary = "List or search customers",
               description = "search matches name, phone number or email, case-insensitively.")
    public Page<CustomerResponse> list(
            @Parameter(description = "matched against name, phone number and email")
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = Paging.of(page, size, Sort.by("name"), "id");

        return customers.search(search, pageable).map(CustomerResponse::from);
    }

    // A customer's order history is not served here. GET /api/orders?customerId={id} answers the
    // same question, paged, in two queries, and composes with every other order filter. The
    // sub-resource that used to live here returned every order a customer had ever placed,
    // unpaged, and cost about six queries per order to assemble.

    @GetMapping("/{id}")
    @Operation(summary = "Read one customer")
    public CustomerResponse get(@PathVariable Long id) {
        return CustomerResponse.from(service.get(id));
    }

    /** A duplicate phone number or email comes back as 409 from the unique constraint. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @Operation(summary = "Register a customer",
               description = "Phone number is required and unique; email is optional but unique where given.")
    public ResponseEntity<CustomerResponse> create(
            @Valid @RequestBody CreateCustomerRequest request) {
        Customer created = service.create(request.name(), request.phoneNumber(), request.email());
        return ResponseEntity
                .created(URI.create("/api/customers/" + created.getId()))
                .body(CustomerResponse.from(created));
    }

    /**
     * Changes only the fields supplied.
     *
     * <p>PATCH rather than PUT: requiring a whole object to change a phone number means two
     * people editing different things each send everything they last read, and the second
     * silently undoes the first.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}")
    @Operation(summary = "Update a customer",
               description = "Only the fields supplied change.")
    public CustomerResponse update(
            @PathVariable Long id, @Valid @RequestBody UpdateCustomerRequest request) {
        return CustomerResponse.from(
                service.update(id, request.name(), request.phoneNumber(), request.email()));
    }

    /**
     * Removes a customer.
     *
     * <p>Refused with 409 once they have ordered anything. An invoice with no customer on it is
     * worse than a customer who cannot be deleted.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a customer", description = "Refused while they still have orders.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
