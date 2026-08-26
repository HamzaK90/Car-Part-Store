package com.carparts.domain;

/**
 * What a login account is allowed to do. Backed by the PostgreSQL {@code user_role} enum.
 *
 * <p>There is deliberately no {@code MANAGER} value. Managing is per-department, not global:
 * {@code department.manager_id} already records who manages what, so a second copy here would
 * drift the moment somebody is promoted. The {@code v_user_identity} view derives it instead.
 */
public enum UserRole {
    ADMIN,
    EMPLOYEE
}
