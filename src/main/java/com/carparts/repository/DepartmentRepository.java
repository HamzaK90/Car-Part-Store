package com.carparts.repository;

import com.carparts.domain.Branch;
import com.carparts.domain.Department;
import com.carparts.domain.Warehouse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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

    // Departments without a manager are not queried here. That question belongs to
    // ReportingRepository.departmentsWithoutManager(), which reads v_department_without_manager
    // and returns the eligible-employee count in the same trip. Two ways to ask it would be two
    // answers to keep in step.
}
