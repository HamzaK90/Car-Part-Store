package com.carparts.web;

import com.carparts.repository.EmployeeRepository;
import com.carparts.service.EmployeeService;
import com.carparts.web.dto.Requests.CreateEmployeeRequest;
import com.carparts.web.dto.Requests.UpdateEmployeeRequest;
import com.carparts.web.dto.Responses.EmployeeResponse;
import com.carparts.web.dto.Responses.ManagerVacancy;
import com.carparts.web.dto.Responses.NewEmployeeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

/**
 * Staff, and where they work.
 *
 * <p>No {@code @Transactional} here. Every query fetches the department and manager a row
 * reports on, so nothing is left to resolve while the response is being written — see the note
 * on {@link OrderController}.
 */
@RestController
@RequestMapping("/api/employees")
@Tag(name = "Employees", description = "Staff, and where they work")
public class EmployeeController {

    private final EmployeeService service;
    private final EmployeeRepository employees;

    public EmployeeController(EmployeeService service, EmployeeRepository employees) {
        this.service = service;
        this.employees = employees;
    }

    /**
     * Lists staff, optionally within one department.
     *
     * <p>Paged and capped, and sorted by name in the database. A payroll only grows, and an
     * endpoint that returns all of it works on demo data and falls over in a year.
     */
    @GetMapping
    @Operation(summary = "List staff",
               description = "departmentId narrows to one department; all staff by default.")
    public Page<EmployeeResponse> list(
            @Parameter(description = "restrict to the staff of one department")
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = Paging.of(page, size, Sort.by("fullName"), "id");

        return employees.search(departmentId, pageable).map(EmployeeResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one employee")
    public EmployeeResponse get(@PathVariable Long id) {
        return EmployeeResponse.from(service.get(id));
    }

    /**
     * Hires somebody.
     *
     * <p>If the department they joined has no manager, the response says so and reports how many
     * of its staff could take the post — the new hire included. A vacancy is easy to create and
     * easy to forget, and this is the moment it is worth raising, with a candidate already in
     * hand. Accepting is a separate {@code PATCH /api/departments/{id}}.
     *
     * <p>{@code managerVacancy} is null in the ordinary case, so a caller that ignores it sees
     * nothing unusual.
     */
    @PostMapping
    @Operation(summary = "Hire an employee",
               description = "Reports a manager vacancy in the department they joined, if there is one.")
    public ResponseEntity<NewEmployeeResponse> hire(
            @Valid @RequestBody CreateEmployeeRequest request) {

        EmployeeService.Hired hired = service.hire(
                request.fullName(), request.salary(), request.birthdate(),
                request.city(), request.street(), request.workShift(),
                request.departmentId(), request.hiredOn());

        NewEmployeeResponse body = new NewEmployeeResponse(
                EmployeeResponse.from(hired.employee()),
                hired.vacancy()
                        .map(v -> new ManagerVacancy(
                                v.departmentId(), v.name(), v.eligibleEmployees()))
                        .orElse(null));

        return ResponseEntity
                .created(URI.create("/api/employees/" + hired.employee().getId()))
                .body(body);
    }

    /**
     * Changes an employee's own details — a raise, a shift, a corrected name or address.
     *
     * <p>Only the fields supplied change. There is no department field here: moving somebody
     * vacates any manager post they held, which is too consequential to happen as a side effect
     * of an edit, so it has its own endpoint below.
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Update an employee",
               description = "Only the fields supplied change. Use the transfer endpoint to move somebody.")
    public EmployeeResponse update(
            @PathVariable Long id, @Valid @RequestBody UpdateEmployeeRequest request) {
        return EmployeeResponse.from(service.update(id, request.fullName(), request.salary(),
                request.birthdate(), request.city(), request.street(), request.workShift()));
    }

    /**
     * Moves somebody to another department.
     *
     * <p>If they managed the one they are leaving, that post is vacated rather than the transfer
     * being refused. Losing a manager is an ordinary event; a manager who works somewhere else
     * is the broken state.
     */
    @PatchMapping("/{id}/department/{departmentId}")
    @Operation(summary = "Transfer an employee",
               description = "Vacates any manager post they held in the department they leave.")
    public EmployeeResponse transfer(@PathVariable Long id, @PathVariable Long departmentId) {
        return EmployeeResponse.from(service.transfer(id, departmentId));
    }

    /**
     * Removes somebody from the payroll.
     *
     * <p>Never refused: any department they managed is left headless, orders they handled keep
     * their record with the handler cleared, and a login account is detached. A leaver must not
     * be undeletable because they once served a customer.
     *
     * <p>A vacancy left this way is silent, so
     * {@code GET /api/reports/departments-without-manager} is what surfaces it.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Remove an employee",
               description = "Never refused. Vacates any post they held and clears them from past orders.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
