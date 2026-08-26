package com.carparts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Somebody on the payroll.
 *
 * <p>An employee belongs to exactly one department, and which subtype that department is
 * decides whether they are sales staff or warehouse staff. There is no role field, because a
 * second place to say the same thing is a second place to get it wrong.
 */
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "employee_id")
    private Long id;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    /** Guarded by {@code ck_employee_salary}: the database rejects anything not above zero. */
    @Column(name = "salary", nullable = false, precision = 10, scale = 2)
    private BigDecimal salary;

    @Column(name = "birthdate")
    private LocalDate birthdate;

    @Embedded
    private Address address;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "work_shift", nullable = false)
    private ShiftType workShift;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    /**
     * Defaulted here as well as in the schema. Hibernate sends every mapped column on insert,
     * so a null field would be written as NULL and violate NOT NULL rather than falling back
     * to the column's DEFAULT.
     */
    @Column(name = "hired_on", nullable = false)
    private LocalDate hiredOn = LocalDate.now();

    protected Employee() {
        // for JPA
    }

    public Employee(String fullName, BigDecimal salary, ShiftType workShift, Department department) {
        this.fullName = fullName;
        this.salary = salary;
        this.workShift = workShift;
        this.department = department;
    }

    /**
     * True when this employee manages the department they work in.
     *
     * <p>Derived from {@code department.manager_id} rather than stored, so promoting somebody
     * is one UPDATE and this answer follows on its own. The database guarantees a manager
     * always belongs to the department they manage, so there is no second department to check.
     */
    public boolean isManager() {
        return department != null && this.equals(department.getManager());
    }

    /**
     * Whether this employee belongs to that department.
     *
     * <p>The rule behind two database constraints: {@code ct_order_employee_at_branch}, which
     * refuses an order handled by somebody from another branch, and
     * {@code ct_department_manager_membership}. Asking it here lets a service reject the request
     * with a useful message rather than surfacing a trigger's exception.
     */
    public boolean worksAt(Department candidate) {
        return candidate != null && candidate.equals(department);
    }

    /**
     * Moves this employee to another department, vacating any post they held in the old one.
     *
     * <p>Mirrors {@code tg_employee_transfer_vacates_post}, which does the same in the database
     * however the transfer happens. Doing it here too keeps the objects already in memory
     * agreeing with what the row will say, instead of holding a manager who has left.
     */
    public void transferTo(Department destination) {
        if (destination == null || destination.equals(department)) {
            return;
        }
        if (isManager()) {
            department.vacateManagerPost();
        }
        department.getEmployees().remove(this);
        destination.addEmployee(this);
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }

    public LocalDate getBirthdate() {
        return birthdate;
    }

    public void setBirthdate(LocalDate birthdate) {
        this.birthdate = birthdate;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public ShiftType getWorkShift() {
        return workShift;
    }

    public void setWorkShift(ShiftType workShift) {
        this.workShift = workShift;
    }

    public Department getDepartment() {
        return department;
    }

    /**
     * Moving somebody to another department vacates any post they held in the old one — the
     * {@code tg_employee_transfer_vacates_post} trigger does that in the database, so it holds
     * however the transfer happens.
     */
    public void setDepartment(Department department) {
        this.department = department;
    }

    public LocalDate getHiredOn() {
        return hiredOn;
    }

    public void setHiredOn(LocalDate hiredOn) {
        this.hiredOn = hiredOn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Employee other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Employee.class.hashCode();
    }

    @Override
    public String toString() {
        return "Employee{id=" + id + ", fullName='" + fullName + "'}";
    }
}
