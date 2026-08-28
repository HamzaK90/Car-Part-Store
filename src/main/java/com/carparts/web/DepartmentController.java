package com.carparts.web;

import com.carparts.domain.Department;
import com.carparts.domain.DepartmentType;
import com.carparts.repository.DepartmentRepository;
import com.carparts.service.DepartmentService;
import com.carparts.web.dto.Requests.CreateDepartmentRequest;
import com.carparts.web.dto.Requests.SetManagerRequest;
import com.carparts.web.dto.Responses.DepartmentResponse;
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
 * Branches and warehouses.
 *
 * <p>No {@code @Transactional} here. Every query fetches the manager it reports, so nothing is
 * left to resolve while the response is being written — see the note on {@link OrderController}.
 */
@RestController
@RequestMapping("/api/departments")
@Tag(name = "Departments", description = "Branches and warehouses")
public class DepartmentController {

    private final DepartmentService service;
    private final DepartmentRepository departments;

    public DepartmentController(DepartmentService service, DepartmentRepository departments) {
        this.service = service;
        this.departments = departments;
    }

    /**
     * Lists departments of both kinds, or one kind with {@code ?type=}.
     *
     * <p>Paged and capped like every other listing, and sorted by name in the database rather
     * than in memory — sorting a page after it has been fetched orders only that page, which is
     * a different and much less useful thing.
     */
    @GetMapping
    @Operation(summary = "List departments",
               description = "type=BRANCH or WAREHOUSE narrows to one kind; both are listed by default.")
    public Page<DepartmentResponse> list(
            @Parameter(description = "BRANCH or WAREHOUSE; omit for both")
            @RequestParam(required = false) DepartmentType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = Paging.of(page, size, Sort.by("name"), "id");

        return departments.search(type, pageable).map(DepartmentResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one department")
    public DepartmentResponse get(@PathVariable Long id) {
        return DepartmentResponse.from(service.get(id));
    }

    /**
     * Opens a department. It starts with no manager — see {@link #setManager}.
     *
     * <p>{@code freeAreaSqm} is required for a warehouse and refused for a branch.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @Operation(summary = "Open a department",
               description = "freeAreaSqm is required for a WAREHOUSE and rejected for a BRANCH.")
    public ResponseEntity<DepartmentResponse> create(
            @Valid @RequestBody CreateDepartmentRequest request) {

        Department created = service.create(request.type(), request.name(),
                request.city(), request.street(), request.freeAreaSqm());

        return ResponseEntity
                .created(URI.create("/api/departments/" + created.getId()))
                .body(DepartmentResponse.from(created));
    }

    /**
     * Appoints a manager, or vacates the post with a null {@code managerId}.
     *
     * <p>This is the other half of the prompt returned when somebody is hired into a headless
     * department. Only an employee of this department may take it — a 400 otherwise, and the
     * database refuses it independently.
     *
     * <p>PATCH rather than PUT: it changes one field and leaves the rest of the department alone.
     */
    @PreAuthorize("hasRole('ADMIN') or @access.managesDepartment(#id)")
    @PatchMapping("/{id}")
    @Operation(summary = "Appoint or remove a manager",
               description = "A null managerId vacates the post, which is a legitimate state.")
    public DepartmentResponse setManager(
            @PathVariable Long id, @Valid @RequestBody SetManagerRequest request) {
        return DepartmentResponse.from(service.setManager(id, request.managerId()));
    }

    /**
     * Closes a department.
     *
     * <p>Refused with 409 while it still has staff — {@code fk_employee_department} sees to that,
     * and it is right to: deleting people because their office closed is never what was meant.
     * Orders taken at a branch and stock held in a warehouse hold it in place the same way.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @Operation(summary = "Close a department",
               description = "Refused while employees, orders or stock still point at it.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
