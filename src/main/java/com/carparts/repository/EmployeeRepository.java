package com.carparts.repository;

import com.carparts.domain.Employee;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findByDepartmentId(Long departmentId);

    /**
     * Whether this employee works at that department.
     *
     * <p>The same rule {@code ct_order_employee_at_branch} enforces. Checking it here lets the
     * service reject a bad order with a clear message instead of letting the insert fail; the
     * trigger stays the thing that actually guarantees it.
     */
    boolean existsByIdAndDepartmentId(Long employeeId, Long departmentId);

    long countByDepartmentId(Long departmentId);
}
