package com.carparts.repository;

import com.carparts.domain.Employee;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    // No existsByIdAndDepartmentId or countByDepartmentId. Neither had a caller: OrderService
    // loads the handler and asks Employee.worksAt(), which is the same question answered by an
    // object it already holds, and a headcount comes from v_department_headcount, which reports
    // every department in one query rather than one department per call.

    /**
     * The filter, written once.
     *
     * <p>A paged query needs its own count query, and spelling the predicate out twice is how the
     * two drift — a page returning five rows while reporting eighteen, with nothing erroring.
     * Shared as a constant for the same reason as {@code PartRepository.FILTERS}.
     *
     * <p>{@code departmentId} is safe to pass as null: it is only ever compared to a bigint
     * column, which the driver can type on its own. A null <em>enum</em> or <em>string</em> in
     * that position would not be — see {@code CustomerOrderRepository.search}.
     */
    String FILTER = "WHERE (:departmentId IS NULL OR e.department.id = :departmentId)";

    /**
     * Staff, optionally within one department, with everything a row reports already loaded.
     *
     * <p>Two fetches, because {@code EmployeeResponse} needs two hops. The department supplies
     * the name; the department's <em>manager</em> is what {@code Employee.isManager()} compares
     * against, so without the second fetch every row costs two further selects rather than one.
     * That is the deepest N+1 in the API and the least obvious, since {@code isManager()} reads
     * like a field rather than a traversal.
     *
     * <p>Both are to-one, so the page stays in SQL. {@code LEFT} on the manager because a vacant
     * post is a normal state, and an inner join would drop every employee of a headless
     * department from the listing.
     */
    @Query(value = """
            SELECT e FROM Employee e
            JOIN FETCH e.department d
            LEFT JOIN FETCH d.manager
            """ + FILTER,
           countQuery = "SELECT COUNT(e) FROM Employee e " + FILTER)
    Page<Employee> search(@Param("departmentId") Long departmentId, Pageable pageable);

    /**
     * One employee with the same two hops loaded, for a detail response.
     *
     * <p>{@code open-in-view} is disabled, so resolving either of them after the transaction has
     * closed is a {@code LazyInitializationException} rather than an extra query.
     */
    @Query("""
            SELECT e FROM Employee e
            JOIN FETCH e.department d
            LEFT JOIN FETCH d.manager
            WHERE e.id = :id
            """)
    Optional<Employee> findWithDepartment(@Param("id") Long id);
}
