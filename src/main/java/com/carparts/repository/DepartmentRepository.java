package com.carparts.repository;

import com.carparts.domain.Branch;
import com.carparts.domain.Department;
import com.carparts.domain.Warehouse;
import java.util.List;
import java.util.Optional;
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

    Optional<Department> findByName(String name);

    boolean existsByName(String name);

    /** Only the branches: the return type is the filter. */
    List<Branch> findAllBranchesBy();

    /** Only the warehouses. */
    List<Warehouse> findAllWarehousesBy();

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

    // Departments without a manager are not queried here. That question belongs to
    // ReportingRepository.departmentsWithoutManager(), which reads v_department_without_manager
    // and returns the eligible-employee count in the same trip. Two ways to ask it would be two
    // answers to keep in step.
}
