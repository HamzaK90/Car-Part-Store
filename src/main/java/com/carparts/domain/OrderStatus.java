package com.carparts.domain;

/** Where an order stands. Backed by the PostgreSQL {@code order_status} enum. */
public enum OrderStatus {
    PLACED,
    FULFILLED,
    CANCELLED
}
