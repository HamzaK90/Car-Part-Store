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
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The shared identity of a branch and a warehouse: a name, an address, a manager and staff.
 *
 * <p><b>Inheritance.</b> {@code JOINED} matches the schema exactly — {@code department} holds
 * what both kinds share, and {@code warehouse} / {@code branch} each add their own table keyed
 * by the same id.
 *
 * <p><b>No discriminator column.</b> The obvious candidate is {@code department.type}, but it
 * is a native PostgreSQL enum and {@code @DiscriminatorColumn} expects a string, character or
 * integer. Rather than fight that, the mapping omits the discriminator entirely: with JOINED
 * inheritance Hibernate can already tell the subtypes apart by which child table holds a row,
 * which is the same fact the composite foreign key enforces in the database.
 *
 * <p>{@code type} is therefore mapped as an ordinary field. Subclasses set it in their
 * constructors and nothing else may change it, so it cannot disagree with the concrete class.
 * The redundant {@code type} columns on {@code warehouse} and {@code branch} stay unmapped;
 * they exist for the composite foreign key and their DEFAULT fills them in.
 */
@Entity
@Table(name = "department")
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "department_id")
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "type", nullable = false, updatable = false)
    private DepartmentType type;

    @Embedded
    private Address address;

    /**
     * The employee who manages this department, or null while the post is vacant.
     *
     * <p>Nullable on purpose: a department is created before anyone is hired into it. A manager
     * who transfers away or leaves vacates the post rather than blocking the move, so a null
     * here is a normal state to find, not a broken one. {@code v_department_without_manager}
     * is what surfaces it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Employee manager;

    @OneToMany(mappedBy = "department", fetch = FetchType.LAZY)
    private List<Employee> employees = new ArrayList<>();

    protected Department() {
        // for JPA
    }

    protected Department(DepartmentType type, String name, Address address) {
        this.type = type;
        this.name = name;
        this.address = address;
    }

    /**
     * How many people work here.
     *
     * <p>Derived, never stored. A column would be a second copy of a fact {@code employee}
     * already holds. This loads the staff list if it is not already in memory, so for a report
     * across many departments read {@code v_department_headcount} instead.
     */
    public int headcount() {
        return employees.size();
    }

    /** True when this department currently has no manager. */
    public boolean isHeadless() {
        return manager == null;
    }

    /**
     * Hires somebody into this department, setting both ends of the association.
     *
     * <p>Setting only {@code employee.department} would persist correctly but leave this
     * object's {@code employees} list stale, so {@link #headcount()} would under-report until
     * the entity was reloaded. Owning the update here is what stops that.
     */
    public void addEmployee(Employee employee) {
        employees.add(employee);
        employee.setDepartment(this);
    }

    /**
     * Puts one of this department's own employees in charge.
     *
     * <p>The same rule {@code ct_department_manager_membership} enforces. Checking it here
     * turns a constraint violation into a clear message; the trigger stays the thing that
     * actually guarantees it, since it holds however the row is written.
     *
     * @throws IllegalArgumentException if the employee works somewhere else
     */
    public void promote(Employee employee) {
        if (!employee.worksAt(this)) {
            throw new IllegalArgumentException(
                    employee.getFullName() + " does not work at " + name + " and cannot manage it");
        }
        this.manager = employee;
    }

    /**
     * Leaves the department without a manager.
     *
     * <p>A legitimate state, not a broken one: a manager who transfers or leaves vacates the
     * post rather than blocking the move. {@code v_department_without_manager} is what surfaces
     * it for an admin to fill.
     */
    public void vacateManagerPost() {
        this.manager = null;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Read-only: the concrete class decides this, and the database enforces the agreement. */
    public DepartmentType getType() {
        return type;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public Employee getManager() {
        return manager;
    }

    public void setManager(Employee manager) {
        this.manager = manager;
    }

    public List<Employee> getEmployees() {
        return employees;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Department other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        // Constant, not id-based: the id is null until the row is inserted, and an entity must
        // not change buckets after being added to a HashSet.
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id=" + id + ", name='" + name + "'}";
    }
}
