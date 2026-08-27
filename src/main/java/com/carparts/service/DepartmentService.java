package com.carparts.service;

import com.carparts.domain.Address;
import com.carparts.domain.Branch;
import com.carparts.domain.Department;
import com.carparts.domain.DepartmentType;
import com.carparts.domain.Employee;
import com.carparts.domain.Warehouse;
import com.carparts.repository.DepartmentRepository;
import com.carparts.repository.EmployeeRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating departments and deciding who runs them. */
@Service
public class DepartmentService {

    private final DepartmentRepository departments;
    private final EmployeeRepository employees;

    public DepartmentService(DepartmentRepository departments, EmployeeRepository employees) {
        this.departments = departments;
        this.employees = employees;
    }

    /**
     * Opens a new department.
     *
     * <p>It starts with no manager, which is not an oversight: nobody has been hired into it yet.
     * The post is filled later by {@link #setManager}, once there is somebody to promote.
     *
     * <p>{@code freeAreaSqm} belongs to a warehouse and only to a warehouse. Supplying it for a
     * branch, or omitting it for a warehouse, is refused rather than quietly ignored — accepting
     * a field that does nothing teaches callers a shape the system does not actually have.
     */
    @Transactional
    public Department create(DepartmentType type, String name, String city, String street,
                             BigDecimal freeAreaSqm) {
        Address address = new Address(city, street);

        if (type == DepartmentType.WAREHOUSE) {
            if (freeAreaSqm == null) {
                throw new InvalidRequestException("a warehouse must state its free area");
            }
            return departments.save(new Warehouse(name, address, freeAreaSqm));
        }

        if (freeAreaSqm != null) {
            throw new InvalidRequestException("a branch has no free area; that belongs to a warehouse");
        }
        return departments.save(new Branch(name, address));
    }

    /**
     * Appoints a manager, or vacates the post when {@code employeeId} is null.
     *
     * <p>Only an employee of this department may take it. That is checked here for a readable
     * message and again by {@code ct_department_manager_membership}, which is the actual
     * guarantee because it holds however the row is written.
     *
     * <p>Vacating is deliberately allowed. A department without a manager is a legitimate state —
     * it is how every department begins, and where one ends up when a manager leaves —
     * and {@code v_department_without_manager} is what surfaces it rather than a constraint
     * refusing the change.
     */
    @Transactional
    public Department setManager(Long departmentId, Long employeeId) {
        Department department = departments.findById(departmentId)
                .orElseThrow(() -> NotFoundException.of("department", departmentId));

        if (employeeId == null) {
            department.vacateManagerPost();
            return department;
        }

        Employee candidate = employees.findById(employeeId)
                .orElseThrow(() -> NotFoundException.of("employee", employeeId));
        if (!candidate.worksAt(department)) {
            // Asked before promoting rather than catching afterwards: promote() signals this with
            // IllegalArgumentException, and translating an exception into a different one loses
            // the distinction between "this employee works elsewhere" and any other bad argument.
            throw new InvalidRequestException(candidate.getFullName()
                    + " works at " + candidate.getDepartment().getName()
                    + " and cannot manage " + department.getName());
        }
        department.promote(candidate);
        return department;
    }

    /**
     * One department, with its manager loaded.
     *
     * <p>{@code findWithManager} rather than {@code findById}. The response names the manager, and
     * {@code open-in-view} is disabled, so resolving that association after this transaction has
     * closed is a {@code LazyInitializationException} rather than an extra query.
     */
    @Transactional(readOnly = true)
    public Department get(Long id) {
        return departments.findWithManager(id)
                .orElseThrow(() -> NotFoundException.of("department", id));
    }

    /**
     * Closes a department.
     *
     * <p>No cascade to staff on purpose. {@code fk_employee_department} refuses to delete a
     * department that still has employees, and that refusal is correct: deleting people because
     * their office closed is never what was meant. The same goes for
     * {@code fk_customer_order_branch} and {@code fk_warehouse_stock_warehouse} — orders taken
     * there and stock held there both hold it in place, and each has its own message.
     */
    @Transactional
    public void delete(Long id) {
        departments.delete(get(id));
    }
}
