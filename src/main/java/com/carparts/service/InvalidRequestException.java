package com.carparts.service;

/**
 * The request is well-formed but asks for something the domain does not allow — no lines, a
 * quantity of zero, a handler who works at a different branch. Becomes a 400 in step 6.
 *
 * <p>Distinct from {@link InsufficientStockException}, which is not the caller's mistake: the
 * same request might well succeed once stock arrives, so it earns a 409 rather than a 400.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
