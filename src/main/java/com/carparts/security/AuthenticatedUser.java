package com.carparts.security;

import com.carparts.domain.UserRole;

/**
 * Who made this request, as the rest of the application sees it.
 *
 * <p>Everything here is read from the token rather than the database, which is the point of
 * putting it in the claims: a request knows who it is without a lookup per call.
 *
 * <p>{@code employeeId} is nullable, and deliberately so. A login is not the same thing as a
 * person — {@code app_user.employee_id} is nullable for service accounts — so anything that
 * needs a member of staff must handle its absence rather than assume one.
 *
 * @param userId the login account
 * @param employeeId the person behind it, or null for an account belonging to nobody on the
 *     payroll
 * @param role what they may do globally
 * @param departmentId where they work, or null when there is no employee
 * @param manager whether they manage {@code departmentId} — derived by {@code v_user_identity}
 *     from {@code department.manager_id}, never stored on the account
 */
public record AuthenticatedUser(
        Long userId,
        Long employeeId,
        UserRole role,
        Long departmentId,
        boolean manager) {

    /**
     * Whether this caller manages the given department.
     *
     * <p>Both conditions are needed: the flag alone would let any manager edit any department,
     * and the department alone would let every employee edit the one they work in.
     */
    public boolean manages(Long department) {
        return manager && departmentId != null && departmentId.equals(department);
    }
}
