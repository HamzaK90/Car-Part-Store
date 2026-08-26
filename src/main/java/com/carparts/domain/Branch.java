package com.carparts.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;

/**
 * A department that sells to customers.
 *
 * <p>It adds no state of its own. The table exists anyway, because being a branch is what the
 * composite foreign key checks: {@code customer_order.branch_id} points here, not at
 * {@code department}, so a warehouse cannot be recorded as the place a sale happened.
 *
 * <p>As with {@link Warehouse}, the {@code type} column is left unmapped and handled by its
 * DEFAULT and CHECK.
 */
@Entity
@Table(name = "branch")
@PrimaryKeyJoinColumn(name = "department_id")
public class Branch extends Department {

    protected Branch() {
        // for JPA
    }

    public Branch(String name, Address address) {
        super(DepartmentType.BRANCH, name, address);
    }
}
