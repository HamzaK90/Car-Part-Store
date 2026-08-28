package com.carparts.service;

/**
 * The credentials offered did not identify anybody who may log in.
 *
 * <p>One exception for three causes — no such username, wrong password, disabled account — and
 * deliberately no field saying which. The moment a caller can tell them apart, the login
 * endpoint becomes a way of asking whether an account exists, and "that account is disabled"
 * confirms a real one outright.
 *
 * <p>A dedicated type rather than {@code ResponseStatusException}: this API maps its own
 * exceptions in {@code ApiExceptionHandler}, whose catch-all outranks Spring's built-in
 * handling. A framework exception thrown from a controller would be caught there and reported
 * as a 500 — which is how a mistyped URL used to come back as a server fault.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("username or password is incorrect");
    }
}
