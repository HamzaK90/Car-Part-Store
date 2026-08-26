package com.carparts.service;

/**
 * Something the request referred to does not exist. Becomes a 404 in step 6.
 *
 * <p>Also raised when an id exists but is of the wrong kind — a warehouse id given as the branch
 * of an order. From the caller's side there is no branch with that id, which is the truth and
 * gives away nothing about what else the id might be.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String what, Object id) {
        return new NotFoundException(what + " " + id + " does not exist");
    }
}
