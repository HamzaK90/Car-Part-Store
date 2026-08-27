package com.carparts.web;

import com.carparts.service.InsufficientStockException;
import com.carparts.service.InvalidRequestException;
import com.carparts.service.NotFoundException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns exceptions into RFC 7807 {@code ProblemDetail} responses.
 *
 * <p>One place decides what a failure looks like on the wire. Without it every controller
 * re-invents the shape, and a caller writing against the API has to discover each variation.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String BASE = "https://github.com/HamzaK90/Car-Part-Store/problems/";

    /**
     * Constraint names mapped to something a person can act on.
     *
     * <p>This is what naming every constraint in V1–V3 was for. PostgreSQL reports the name it
     * was given, so a violation that reaches here can be explained instead of surfacing as
     * "ERROR: duplicate key value violates unique constraint" with a stack trace attached.
     *
     * <p>Only rules a client could plausibly trip are listed. Anything absent falls through to a
     * generic 409, which is honest rather than guessing.
     */
    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.ofEntries(
            Map.entry("uq_department_name", "a department with that name already exists"),
            Map.entry("uq_customer_phone", "that phone number is already registered"),
            Map.entry("uq_customer_email", "that email address is already registered"),
            Map.entry("uq_supplier_name", "a supplier with that name already exists"),
            Map.entry("uq_part_sku", "that SKU is already in the catalogue"),
            Map.entry("uq_app_user_username", "that username is taken"),
            Map.entry("uq_app_user_employee", "that employee already has an account"),
            Map.entry("ck_employee_salary", "salary must be greater than zero"),
            Map.entry("ck_part_price", "price cannot be negative"),
            Map.entry("ck_part_weight", "weight must be greater than zero"),
            Map.entry("ck_part_reorder_level", "reorder level cannot be negative"),
            Map.entry("ck_warehouse_free_area", "free area cannot be negative"),
            Map.entry("ck_warehouse_stock_quantity", "stock cannot go negative"),
            Map.entry("ck_order_item_quantity", "quantity must be greater than zero"),
            Map.entry("ck_car_fitment_year_range", "the last model year cannot precede the first"),
            Map.entry("ct_order_employee_at_branch",
                    "the handling employee does not work at the branch that took the order"),
            Map.entry("ct_department_manager_membership",
                    "a manager must be an employee of the department they manage"),
            Map.entry("ct_order_status_transition",
                    "only an order still PLACED can be fulfilled or cancelled"),
            Map.entry("ct_order_has_lines", "an order must contain at least one line"),
            Map.entry("fk_part_supplier", "that supplier does not exist"),

            // Foreign keys refusing a delete. Each of these means "something still points at
            // this", and the useful part of the message is what that something is — otherwise a
            // caller is told only that their delete conflicted, with no way to act on it.
            //
            // A foreign key can fire in two directions, and only one of them reaches here. The
            // insert direction — naming a parent that does not exist — is caught in Java first:
            // every service resolves its parent with findById().orElseThrow(NotFoundException),
            // so a bad departmentId is a 404 before any row is written. What actually reaches
            // this map is the delete direction, so that is the direction each message describes.
            // fk_employee_department was previously worded for the insert direction and produced
            // a 409 reading "that department does not exist" when closing a department that still
            // had staff — a refusal that contradicted itself.
            Map.entry("fk_employee_department",
                    "that department still has employees; transfer or remove them first"),
            Map.entry("fk_warehouse_stock_part",
                    "that part is still stocked in a warehouse; clear the stock first"),
            Map.entry("fk_order_item_part",
                    "that part appears on an existing order and cannot be removed"),
            Map.entry("fk_car_fitment_part", "that part still has car fitments recorded"),
            Map.entry("fk_warehouse_stock_warehouse",
                    "that warehouse still holds stock; clear it first"),
            Map.entry("fk_customer_order_customer",
                    "that customer has orders and cannot be removed"),
            Map.entry("fk_customer_order_branch",
                    "that branch has orders taken at it and cannot be removed"),
            Map.entry("fk_customer_order_warehouse",
                    "that warehouse has filled orders and cannot be removed"),
            Map.entry("fk_customer_order_employee", "that employee handled an existing order"),
            Map.entry("fk_order_item_order", "that order still has lines"),
            Map.entry("fk_department_manager", "that employee manages a department"),
            Map.entry("fk_branch_department", "that branch record is still attached to a department"),
            Map.entry("fk_warehouse_department",
                    "that warehouse record is still attached to a department"),
            Map.entry("fk_app_user_employee", "that employee has a login account"));

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail onNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), "not-found");
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail onInvalidRequest(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage(), "invalid-request");
    }

    /**
     * A domain object refused an operation — fulfilling an already-cancelled order, promoting
     * somebody who works elsewhere.
     *
     * <p>These arrive as plain {@code IllegalStateException} / {@code IllegalArgumentException}
     * from entity methods. Without this they would fall through to the catch-all and be reported
     * as 500s, which would be a lie: the request was understood and deliberately refused.
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ProblemDetail onDomainRefusal(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Not allowed", e.getMessage(), "not-allowed");
    }

    /**
     * 409 rather than 400: the request is not malformed, the warehouse simply cannot cover it.
     * The same request may well succeed once stock arrives.
     *
     * <p>Every shortage is listed, so a caller can fix the order in one pass instead of
     * discovering the next short part on each retry.
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail onInsufficientStock(InsufficientStockException e) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT, "Insufficient stock", e.getMessage(), "insufficient-stock");
        problem.setProperty("shortages", e.getShortages().stream()
                .map(s -> Map.of(
                        "partId", s.partId(),
                        "sku", s.sku(),
                        "requested", s.requested(),
                        "available", s.available(),
                        "shortBy", s.shortBy()))
                .toList());
        return problem;
    }

    /** Bean Validation failures, reported per field so the caller can fix all of them at once. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors()
                .forEach(g -> errors.putIfAbsent(g.getObjectName(), g.getDefaultMessage()));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "the request body is not acceptable; see errors", "validation-failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    /** A path variable or query parameter of the wrong type — {@code /api/parts/abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid parameter",
                "'" + e.getValue() + "' is not a valid value for " + e.getName(),
                "invalid-parameter");
    }

    /**
     * A database constraint refused the write.
     *
     * <p>Reaching here is not necessarily a bug: services check what they sensibly can, but the
     * database is the last word, and some rules are only decidable there. The constraint name is
     * translated where possible, and the underlying SQL message is deliberately not echoed —
     * it exposes column and table names to no benefit.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onConstraintViolation(DataIntegrityViolationException e) {
        String constraint = constraintNameOf(e);
        String detail = CONSTRAINT_MESSAGES.get(constraint);

        if (detail == null) {
            log.warn("unmapped constraint violation: {}", constraint, e);
            detail = "the request conflicts with the current state of the data";
        }

        ProblemDetail problem = problem(
                HttpStatus.CONFLICT, "Constraint violation", detail, "constraint-violation");
        if (constraint != null) {
            problem.setProperty("constraint", constraint);
        }
        return problem;
    }

    /**
     * Digs the constraint name out of the exception chain.
     *
     * <p>Spring wraps Hibernate wraps the driver, so the name is never on the exception thrown
     * at this layer. Hibernate extracts it for SQLState class 23, which is what the triggers in
     * V3 raise (they set {@code ERRCODE = 'integrity_constraint_violation'} alongside
     * {@code CONSTRAINT}), so a trigger and a plain CHECK both arrive here identifiable.
     *
     * <p>The PostgreSQL driver is deliberately not consulted directly: it is a runtime-scope
     * dependency, and reaching for {@code PSQLException} here would drag a specific database
     * onto the compile classpath of the web layer to learn something Hibernate already knows.
     */
    private String constraintNameOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException hib
                    && hib.getConstraintName() != null) {
                return hib.getConstraintName();
            }
            String message = t.getMessage();
            if (message != null) {
                for (String name : CONSTRAINT_MESSAGES.keySet()) {
                    if (message.contains(name)) {
                        return name;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A URL that matches no route.
     *
     * <p>Handled explicitly because the catch-all below would otherwise claim it. Spring raises
     * {@code NoResourceFoundException} for an unmapped path — it already means 404 — but an
     * {@code @ExceptionHandler(Exception.class)} outranks the framework's own handling, so
     * without this every mistyped URL came back as a 500 and was logged as a bug in the server.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail onNoRoute(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found",
                "no endpoint at " + e.getResourcePath(), "not-found");
    }

    /** A path exists but does not accept this verb — POST to a read-only endpoint. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail onWrongMethod(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
                e.getMethod() + " is not supported here; try "
                        + String.join(", ", e.getSupportedMethods() == null
                                ? new String[]{"another verb"} : e.getSupportedMethods()),
                "method-not-allowed");
    }

    /** A malformed or unreadable request body — broken JSON, a string where a number belongs. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "the request body could not be read as JSON", "malformed-request");
    }

    /**
     * Anything genuinely unforeseen.
     *
     * <p>Logged in full, reported as nothing. An exception message can carry a query, a file path
     * or part of a row, and none of that belongs in a response to a caller who has just tripped
     * over a bug.
     *
     * <p>Declared last and kept deliberately narrow in what reaches it: a catch-all that also
     * swallows the framework's own well-formed responses turns every client mistake into an
     * apparent server fault, and buries real bugs among them in the log.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "something went wrong handling this request", "internal-error");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE + type));
        return problem;
    }

    /** Exposed for tests and for controllers that need the same wording. */
    static List<String> mappedConstraints() {
        return CONSTRAINT_MESSAGES.keySet().stream().sorted().toList();
    }
}
