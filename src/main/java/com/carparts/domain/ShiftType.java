package com.carparts.domain;

/** The shift an employee works. Backed by the PostgreSQL {@code shift_type} enum. */
public enum ShiftType {
    MORNING,
    EVENING,
    NIGHT
}
