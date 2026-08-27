package com.carparts.web.dto;

import com.carparts.domain.DepartmentType;
import com.carparts.domain.ShiftType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the API accepts.
 *
 * <p>The annotations here reject a bad request before it reaches a service, so the caller gets
 * every problem at once instead of one per round trip. They are not the guarantee — the database
 * constraints are, and they hold whatever writes to it. These exist to make the common failure
 * a clear 400 rather than a 409 raised from three layers down.
 *
 * <p>Sizes match the columns. A name longer than the column would otherwise travel all the way
 * to PostgreSQL to be refused there.
 */
public final class Requests {

    private Requests() {
    }

    /**
     * Placing an order.
     *
     * <p>Notice there is no employee field. The handler comes from the authenticated session,
     * never the body, so a salesperson cannot record an order as handled by a colleague. Leaving
     * it out of this record is what makes that structural.
     */
    public record PlaceOrderRequest(
            @NotNull(message = "an order must name a customer")
            Long customerId,

            @NotNull(message = "an order must name the branch that took it")
            Long branchId,

            @NotNull(message = "an order must name the warehouse filling it")
            Long warehouseId,

            @NotEmpty(message = "an order must contain at least one line")
            @Valid
            List<OrderLineRequest> lines) {}

    /**
     * Amending an order's lines.
     *
     * <p>The complete desired set, not a delta. A part left out is removed; a quantity given is
     * what the line becomes. Sending an empty list is refused rather than treated as a
     * cancellation — those are different intentions and cancelling has its own endpoint.
     */
    public record AmendOrderRequest(
            @NotEmpty(message = "an order must keep at least one line; cancel it instead")
            @Valid
            List<OrderLineRequest> lines) {}

    public record OrderLineRequest(
            @NotNull(message = "every line must name a part")
            Long partId,

            @Positive(message = "quantity must be greater than zero")
            int quantity) {}

    public record CreateCustomerRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 20) String phoneNumber,
            @Email @Size(max = 255) String email) {}

    public record CreateSupplierRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 50) String city,
            @Size(max = 100) String street,
            @Size(max = 20) String phoneNumber) {}

    public record CreateEmployeeRequest(
            @NotBlank @Size(max = 100) String fullName,

            @NotNull
            @DecimalMin(value = "0.01", message = "salary must be greater than zero")
            BigDecimal salary,

            @Past(message = "a birthdate must be in the past")
            LocalDate birthdate,

            @Size(max = 50) String city,
            @Size(max = 100) String street,

            @NotNull(message = "an employee must be given a shift")
            ShiftType workShift,

            @NotNull(message = "an employee must belong to a department")
            Long departmentId,

            LocalDate hiredOn) {}

    /**
     * Creating a department.
     *
     * <p>{@code freeAreaSqm} applies only to a warehouse. A branch that supplies it, or a
     * warehouse that omits it, is rejected by the controller rather than silently ignored —
     * accepting a field that does nothing teaches callers the wrong shape.
     */
    public record CreateDepartmentRequest(
            @NotBlank @Size(max = 100) String name,

            @NotNull(message = "a department must be a WAREHOUSE or a BRANCH")
            DepartmentType type,

            @NotBlank @Size(max = 50) String city,
            @NotBlank @Size(max = 100) String street,

            @PositiveOrZero(message = "free area cannot be negative")
            BigDecimal freeAreaSqm) {}

    /**
     * Appointing or removing a manager.
     *
     * <p>A null {@code managerId} vacates the post, which is a legitimate state — a department
     * exists before anyone is hired into it, and a manager who leaves is not blocked. Only an
     * employee of that same department may be appointed, which the database also enforces.
     */
    public record SetManagerRequest(Long managerId) {}

    /**
     * Changing a part. Every field is optional; a null leaves that field as it was.
     *
     * <p>There is no {@code sku}. It is the identifier customers quote, suppliers match on and
     * invoices print — order lines already issued display it — so editing it silently rewrites
     * what those documents appear to say. A part with the wrong SKU is a new part.
     */
    public record UpdatePartRequest(
            @Size(max = 100) String name,
            @PositiveOrZero(message = "price cannot be negative") BigDecimal price,
            @DecimalMin(value = "0.01", message = "weight must be greater than zero") BigDecimal weightKg,
            String description,
            @Size(max = 100) String manufacturingPlace,
            @PositiveOrZero(message = "reorder level cannot be negative") Integer reorderLevel,
            Long supplierId) {}

    /**
     * Recording that a part fits a car.
     *
     * <p>{@code yearFrom} is part of the fitment's identity, so the same make, model and starting
     * year cannot be recorded twice. {@code yearTo} is the one detail that can be corrected
     * without making it a different fitment.
     */
    public record FitmentRequest(
            @NotBlank @Size(max = 50) String make,
            @NotBlank @Size(max = 50) String model,
            @NotNull(message = "a fitment must state the first model year it covers") Short yearFrom,
            @NotNull(message = "a fitment must state the last model year it covers") Short yearTo) {}

    /**
     * Correcting how long a fitment runs.
     *
     * <p>Only the last model year. The rest of a fitment is its primary key, so changing any of
     * it makes a different fitment.
     */
    public record CorrectFitmentRequest(
            @NotNull(message = "state the last model year the fitment covers") Short yearTo) {}

    /** Adding a part to the catalogue. */
    public record PartRequest(
            @NotBlank @Size(max = 32) String sku,
            @NotBlank @Size(max = 100) String name,

            @NotNull
            @PositiveOrZero(message = "price cannot be negative")
            BigDecimal price,

            @NotNull
            @DecimalMin(value = "0.01", message = "weight must be greater than zero")
            BigDecimal weightKg,

            String description,
            @Size(max = 100) String manufacturingPlace,

            /**
             * The level below which this part counts as low. Zero means never flag it, which is
             * why it defaults there rather than to something arbitrary.
             */
            @PositiveOrZero(message = "reorder level cannot be negative")
            int reorderLevel,

            @NotNull(message = "a part must come from a supplier")
            Long supplierId) {}

    /**
     * Receiving a delivery into a warehouse.
     *
     * <p>Adds to what is already there. To correct a count after a stock-take use
     * {@link StockCountRequest} instead — "we received 20 more" and "we counted 20" are
     * different statements and should not share an endpoint.
     */
    public record ReceiveStockRequest(
            @NotNull(message = "name the part being received")
            Long partId,

            @Positive(message = "a delivery must be of at least one unit")
            int quantity) {}

    /** Setting a stock level outright, after a physical count. */
    public record StockCountRequest(
            @PositiveOrZero(message = "a stock count cannot be negative")
            int quantity) {}
}
