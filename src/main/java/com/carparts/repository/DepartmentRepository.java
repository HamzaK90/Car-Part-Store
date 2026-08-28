package com.carparts.repository;

import com.carparts.domain.Branch;
import com.carparts.domain.Department;
import com.carparts.domain.DepartmentType;
import com.carparts.domain.Warehouse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Departments of either kind.
 *
 * <p>Because {@link Department} is a JOINED hierarchy, a query typed to {@link Branch} or
 * {@link Warehouse} returns only that subtype — Spring Data narrows it for us, so there is no
 * need for separate repositories or a {@code type} predicate.
 */
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    // No findByName or existsByName. Neither had a caller, and a pre-check for a duplicate name
    // would be both a second way to ask what uq_department_name already answers and a race: two
    // requests can each find nothing and then both insert. The constraint is the guarantee, and
    // ApiExceptionHandler already turns it into "a department with that name already exists".

    /**
     * Loads a department only if it is a branch.
     *
     * <p>Typing the query to the subtype is the point: handed a warehouse's id this returns
     * empty rather than a department of the wrong kind, so a caller cannot record a sale as
     * having happened at a warehouse. It is the same guarantee
     * {@code fk_customer_order_branch} gives in the database, applied one layer earlier where
     * the error can still be a readable message.
     */
    @Query("SELECT b FROM Branch b WHERE b.id = :id")
    Optional<Branch> findBranch(@Param("id") Long id);

    /** Loads a department only if it is a warehouse. See {@link #findBranch(Long)}. */
    @Query("SELECT w FROM Warehouse w WHERE w.id = :id")
    Optional<Warehouse> findWarehouse(@Param("id") Long id);

    /**
     * The department listing, optionally narrowed to one kind.
     *
     * <p>An absent {@code type} widens to every kind rather than being passed as null. That is
     * not a stylistic choice: PostgreSQL type-checks the whole predicate even where it would
     * short-circuit, and a null native-enum parameter arrives with nothing to infer a type from,
     * so {@code :type IS NULL OR d.type = :type} fails with <em>could not determine data type of
     * parameter</em>. The same trap {@code ReportingRepository.orderSummaries} works around for
     * status, and {@code PartRepository.search} for a null search term.
     *
     * <p>This replaces the two unpaged {@code findAllBranchesBy} / {@code findAllWarehousesBy}
     * finders. They answered the same question without a page or a cap, and leaving both would
     * have been two ways to ask it.
     */
    default Page<Department> search(DepartmentType type, Pageable pageable) {
        return searchByType(
                type == null ? List.of(DepartmentType.values()) : List.of(type), pageable);
    }

    /**
     * The filter, written once.
     *
     * <p>A paged query needs its own count query, and spelling the predicate out twice is how the
     * copies drift — a page returning five rows while reporting eighteen, with nothing erroring.
     * Sharing the constant makes that impossible, as it does in {@code PartRepository.FILTERS}.
     */
    String TYPE_FILTER = "WHERE d.type IN :types";

    /**
     * Departments with their managers already loaded.
     *
     * <p>{@code LEFT JOIN FETCH} rather than a plain select. Every row reports its manager's
     * name, and {@code Department.manager} is lazy, so without the fetch each department that has
     * one costs a further select — the same N+1 measured at twelve statements for eight parts and
     * fourteen for ten stock rows. {@code LEFT} because a vacant post is a normal state and an
     * inner join would silently drop those departments from the listing.
     *
     * <p>Fetching a to-one keeps the page in SQL. Fetch-joining a collection alongside pagination
     * would force Hibernate to page in memory instead.
     */
    @Query(value = "SELECT d FROM Department d LEFT JOIN FETCH d.manager " + TYPE_FILTER,
           countQuery = "SELECT COUNT(d) FROM Department d " + TYPE_FILTER)
    Page<Department> searchByType(@Param("types") List<DepartmentType> types, Pageable pageable);

    /**
     * One department with its manager already loaded, for a detail response.
     *
     * <p>Needed for the same reason as the listing above, and more sharply: {@code open-in-view}
     * is disabled, so a lazy manager read while the response is being written is not a slow query
     * but a {@code LazyInitializationException}.
     */
    @Query("SELECT d FROM Department d LEFT JOIN FETCH d.manager WHERE d.id = :id")
    Optional<Department> findWithManager(@Param("id") Long id);

    // Departments without a manager are not queried here. That question belongs to
    // ReportingRepository.departmentsWithoutManager(), which reads v_department_without_manager
    // and returns the eligible-employee count in the same trip. Two ways to ask it would be two
    // answers to keep in step.
}
