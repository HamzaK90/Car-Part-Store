package com.carparts.web;

import com.carparts.repository.ReportingRepository;
import com.carparts.repository.ReportingRepository.CustomerRevenue;
import com.carparts.repository.ReportingRepository.DepartmentHeadcount;
import com.carparts.repository.ReportingRepository.DepartmentWithoutManager;
import com.carparts.repository.ReportingRepository.LowStock;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only reports, each backed by a view.
 *
 * <p>These return {@code ReportingRepository}'s records directly rather than remapping them. The
 * records are already read models — flat, immutable, shaped for exactly this — and a DTO layer
 * over them would copy field for field and add a second place to change. That is the one case
 * where the rule against serialising persistence types does not apply: these are not entities and
 * have no lazy associations to walk.
 *
 * <p>No {@code @Transactional}. Every figure is computed by the view and arrives flat, so nothing
 * is left to resolve while the response is written.
 *
 * <p><b>Two of the four are paged and two are not, deliberately.</b> The rule is that a listing
 * must be capped when its length grows with the business. Headcount and vacancies have one row
 * per department — a company's physical locations, which are counted in dozens and change when
 * premises open. Revenue has a row per customer and low stock a row per warehouse-and-part, both
 * of which grow with trade and never shrink. A bare {@code List} is usually the tell for an
 * uncapped endpoint; here it is a claim about cardinality, and the two that could not make that
 * claim are pages.
 */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports", description = "Derived figures, computed on read")
public class ReportController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReportingRepository reporting;

    public ReportController(ReportingRepository reporting) {
        this.reporting = reporting;
    }

    /**
     * What each customer has bought, best first.
     *
     * <p>Revenue the shop earned, not money the customer owes. There is no payments table, so no
     * balance can be known — the view was briefly named {@code v_customer_balance}, which invited
     * exactly that misreading.
     *
     * <p>Paged: the view carries a row for every customer on the books, including those who have
     * never ordered, at zero.
     */
    @GetMapping("/revenue-by-customer")
    @Operation(summary = "Revenue per customer",
               description = "Total ordered, cancelled orders excluded. Not an amount owed.")
    public Page<CustomerRevenue> revenueByCustomer(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int number = Math.max(page, 0);

        List<CustomerRevenue> content = reporting.revenueByCustomer(limit, (long) number * limit);
        return new PageImpl<>(content, PageRequest.of(number, limit), reporting.countCustomers());
    }

    /**
     * Stock below its part's reorder level, worst shortfall first.
     *
     * <p>The purchasing view, across every warehouse, carrying the supplier and the price so a
     * buyer can act on it. To ask the same question of one warehouse, use
     * {@code GET /api/warehouses/{id}/stock?lowOnly=true} — this report deliberately has no
     * warehouse filter rather than answering it a second way.
     */
    @GetMapping("/low-stock")
    @Operation(summary = "Stock needing reorder",
               description = "Across all warehouses; the threshold is per part, from part.reorder_level.")
    public Page<LowStock> lowStock(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int number = Math.max(page, 0);

        List<LowStock> content = reporting.lowStock(limit, (long) number * limit);
        return new PageImpl<>(content, PageRequest.of(number, limit), reporting.countLowStock());
    }

    /**
     * Staff per department, including departments with nobody in them.
     *
     * <p>Unpaged, and bounded by how many departments the business has. A zero row is the useful
     * one here — an empty department is a thing an admin needs to see, not a row to omit.
     */
    @GetMapping("/department-headcount")
    @Operation(summary = "Headcount per department",
               description = "One row per department, including empty ones.")
    public List<DepartmentHeadcount> departmentHeadcount() {
        return reporting.departmentHeadcount();
    }

    /**
     * Departments currently running with no manager.
     *
     * <p>The standing vacancy list. A manager who transfers away or leaves the company vacates
     * the post silently — deliberately, since blocking the move would be worse — so this report
     * is the only thing that surfaces it. {@code POST /api/employees} raises the same vacancy at
     * the moment somebody is hired into a headless department; this is how the backlog is worked
     * through.
     *
     * <p>Unpaged: a subset of departments, so smaller still than the headcount above.
     */
    @GetMapping("/departments-without-manager")
    @Operation(summary = "Departments with no manager",
               description = "eligibleEmployees is how many of its staff could be promoted.")
    public List<DepartmentWithoutManager> departmentsWithoutManager() {
        return reporting.departmentsWithoutManager();
    }
}
