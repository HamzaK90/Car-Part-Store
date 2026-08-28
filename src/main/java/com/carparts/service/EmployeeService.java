package com.carparts.service;

import com.carparts.domain.Address;
import com.carparts.domain.Department;
import com.carparts.domain.Employee;
import com.carparts.domain.ShiftType;
import com.carparts.repository.DepartmentRepository;
import com.carparts.repository.EmployeeRepository;
import com.carparts.repository.ReportingRepository;
import com.carparts.repository.ReportingRepository.DepartmentWithoutManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Hiring, transferring and removing staff. */
@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final DepartmentRepository departments;
    private final ReportingRepository reporting;

    public EmployeeService(EmployeeRepository employees,
                           DepartmentRepository departments,
                           ReportingRepository reporting) {
        this.employees = employees;
        this.departments = departments;
        this.reporting = reporting;
    }

    /**
     * What hiring somebody produced, and whether it left an opening worth mentioning.
     *
     * @param vacancy the department this person just joined, when it has no manager — otherwise
     *     empty. Present means the caller can offer the promotion immediately.
     */
    public record Hired(Employee employee, Optional<DepartmentWithoutManager> vacancy) {}

    /**
     * Hires somebody into a department.
     *
     * <p>Afterwards it checks whether that department has a manager. A vacancy is easy to create
     * and easy to forget — a manager transfers away and the post silently empties — so the moment
     * somebody is hired into a headless department is exactly when it is worth saying so, while
     * there is a candidate in hand.
     *
     * <p>The check reads {@code v_department_without_manager} for this one department, so
     * "nobody to promote" and "here are four" come back distinguished in a single lookup.
     */
    @Transactional
    public Hired hire(String fullName, BigDecimal salary, LocalDate birthdate,
                      String city, String street, ShiftType workShift,
                      Long departmentId, LocalDate hiredOn) {

        Department department = departments.findById(departmentId)
                .orElseThrow(() -> NotFoundException.of("department", departmentId));

        Employee employee = new Employee(fullName, salary, workShift, department);
        employee.setBirthdate(birthdate);
        employee.setAddress(new Address(city, street));
        if (hiredOn != null) {
            employee.setHiredOn(hiredOn);
        }
        department.addEmployee(employee);
        Employee saved = employees.save(employee);

        // Flushed so the new hire counts toward eligible_employees. The view is read with
        // JdbcClient on the same connection and so sees this transaction's uncommitted rows —
        // but only once Hibernate has actually sent the INSERT, which it defers until it must.
        employees.flush();

        return new Hired(saved, reporting.vacancyFor(departmentId));
    }

    /**
     * One employee, with their department and that department's manager loaded.
     *
     * <p>{@code findWithDepartment} rather than {@code findById}: the response reports the
     * department's name and whether this person manages it, and {@code open-in-view} is disabled,
     * so resolving either after this transaction closes is a {@code LazyInitializationException}.
     */
    @Transactional(readOnly = true)
    public Employee get(Long id) {
        return employees.findWithDepartment(id)
                .orElseThrow(() -> NotFoundException.of("employee", id));
    }

    /**
     * Changes only the details supplied; a null leaves that one alone.
     *
     * <p>PATCH rather than PUT, for the reason set out on {@code PartService.update}: a full
     * object means two people editing different fields each send everything they read, and the
     * second silently undoes the first. Here that would be a raise quietly reverted by somebody
     * correcting a shift.
     *
     * <p>The department is not among the fields — {@link #transfer} owns that, because moving
     * somebody has a consequence a field assignment would hide.
     */
    @Transactional
    public Employee update(Long id, String fullName, BigDecimal salary, LocalDate birthdate,
                           String city, String street, ShiftType workShift) {
        Employee employee = get(id);
        if (fullName != null) {
            employee.setFullName(fullName);
        }
        if (salary != null) {
            employee.setSalary(salary);
        }
        if (birthdate != null) {
            employee.setBirthdate(birthdate);
        }
        if (workShift != null) {
            employee.setWorkShift(workShift);
        }
        if (city != null || street != null) {
            employee.setAddress(Address.merged(employee.getAddress(), city, street));
        }
        return employee;
    }

    /**
     * Moves somebody to another department.
     *
     * <p>If they managed the department they are leaving, the post is vacated rather than the
     * move being refused — {@code tg_employee_transfer_vacates_post} does the same in the
     * database, so the objects in memory and the row agree. Losing a manager is an ordinary
     * event; a manager who works somewhere else is the broken state.
     */
    @Transactional
    public Employee transfer(Long employeeId, Long destinationId) {
        Employee employee = get(employeeId);
        Department destination = departments.findById(destinationId)
                .orElseThrow(() -> NotFoundException.of("department", destinationId));
        employee.transferTo(destination);
        return employee;
    }

    /**
     * Removes somebody from the payroll.
     *
     * <p>Nothing blocks this, and that is deliberate rather than an oversight: all three foreign
     * keys pointing at {@code employee} are {@code ON DELETE SET NULL}. Any department they
     * managed is left headless, orders they handled keep their record with the handler cleared,
     * and a login account of theirs is detached from the person. A leaver must not be undeletable
     * because they once served a customer.
     *
     * <p>The vacancy that leaves behind is silent by design — refusing the deletion would be
     * worse — so {@code v_department_without_manager}, and the report over it, is what surfaces
     * it for an admin to fill.
     */
    @Transactional
    public void delete(Long id) {
        employees.delete(get(id));
    }
}
