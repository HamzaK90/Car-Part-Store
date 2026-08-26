package com.carparts.domain;

/**
 * Which kind of department a row is. Backed by the PostgreSQL {@code department_type} enum,
 * and the value {@code warehouse} and {@code branch} pin with a CHECK so a department can
 * never be both.
 */
public enum DepartmentType {
    WAREHOUSE,
    BRANCH
}
