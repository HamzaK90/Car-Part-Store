package com.carparts.web.dto;

import com.carparts.domain.CarFitment;
import com.carparts.domain.Customer;
import com.carparts.domain.CustomerOrder;
import com.carparts.domain.Department;
import com.carparts.domain.DepartmentType;
import com.carparts.domain.Employee;
import com.carparts.domain.OrderItem;
import com.carparts.domain.OrderStatus;
import com.carparts.domain.Part;
import com.carparts.domain.ShiftType;
import com.carparts.domain.Supplier;
import com.carparts.domain.UserRole;
import com.carparts.domain.Warehouse;
import com.carparts.domain.WarehouseStock;
import com.carparts.repository.ReportingRepository.UserIdentity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the API sends back.
 *
 * <p>Entities are never returned directly. Serialising one walks its lazy associations, so an
 * order would drag in its customer, that customer's orders, and onward — either failing outside
 * a session or quietly issuing dozens of queries to render one response. A record states exactly
 * what is being sent, and it stops the wire format changing every time a mapping does.
 *
 * <p>Gathered in one file because they are small, closely related, and easier to compare than
 * twenty near-identical ones.
 */
public final class Responses {

    private Responses() {
    }

    // ---------------------------------------------------------------- authentication

    /**
     * A successful login.
     *
     * <p>The identity fields are a convenience for the client — enough to greet somebody by name
     * and hide buttons they cannot use. They are <em>not</em> what the server trusts: every
     * authorisation decision reads the signed claims in the token, so a client editing this body
     * changes nothing it is allowed to do.
     *
     * <p>{@code employeeId} is null for an account that belongs to nobody on the payroll, and
     * {@code isManager} is derived by {@code v_user_identity} rather than stored on the account.
     */
    public record LoginResponse(
            String token,
            String tokenType,
            long expiresInSeconds,
            String username,
            UserRole role,
            Long employeeId,
            String fullName,
            Long departmentId,
            String departmentName,
            boolean isManager) {

        public static LoginResponse of(String token, long expiresInSeconds, UserIdentity who) {
            return new LoginResponse(
                    token, "Bearer", expiresInSeconds,
                    who.username(), who.role(), who.employeeId(), who.fullName(),
                    who.departmentId(), who.departmentName(), who.isManager());
        }
    }

    // ---------------------------------------------------------------- catalogue

    public record PartResponse(
            Long id,
            String sku,
            String name,
            BigDecimal price,
            BigDecimal weightKg,
            String description,
            String manufacturingPlace,
            int reorderLevel,
            Long supplierId,
            String supplierName) {

        public static PartResponse from(Part p) {
            return new PartResponse(
                    p.getId(), p.getSku(), p.getName(), p.getPrice(), p.getWeightKg(),
                    p.getDescription(), p.getManufacturingPlace(), p.getReorderLevel(),
                    p.getSupplier().getId(), p.getSupplier().getName());
        }
    }

    public record FitmentResponse(String make, String model, Short yearFrom, Short yearTo) {

        public static FitmentResponse from(CarFitment f) {
            return new FitmentResponse(f.getMake(), f.getModel(), f.getYearFrom(), f.getYearTo());
        }
    }

    // ---------------------------------------------------------------- orders

    public record OrderLineResponse(
            Long partId,
            String sku,
            String partName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {

        public static OrderLineResponse from(OrderItem i) {
            return new OrderLineResponse(
                    i.getPart().getId(), i.getPart().getSku(), i.getPart().getName(),
                    i.getQuantity(), i.getUnitPrice(), i.lineTotal());
        }
    }

    /**
     * A full order.
     *
     * <p>{@code unitPrice} on each line is the price captured at sale, not today's catalogue
     * price. The two agree until somebody reprices the part, and then they should not.
     */
    public record OrderResponse(
            Long id,
            Long customerId,
            String customerName,
            Long employeeId,
            String employeeName,
            Long branchId,
            String branchName,
            Long warehouseId,
            String warehouseName,
            LocalDate orderDate,
            OrderStatus status,
            List<OrderLineResponse> lines,
            int unitCount,
            BigDecimal total) {

        public static OrderResponse from(CustomerOrder o) {
            Employee handler = o.getEmployee();
            return new OrderResponse(
                    o.getId(),
                    o.getCustomer().getId(), o.getCustomer().getName(),
                    handler == null ? null : handler.getId(),
                    handler == null ? null : handler.getFullName(),
                    o.getBranch().getId(), o.getBranch().getName(),
                    o.getWarehouse().getId(), o.getWarehouse().getName(),
                    o.getOrderDate(), o.getStatus(),
                    o.getItems().stream().map(OrderLineResponse::from).toList(),
                    o.unitCount(), o.total());
        }
    }

    // ---------------------------------------------------------------- stock

    public record StockResponse(
            Long partId,
            String sku,
            String partName,
            int quantity,
            int reorderLevel,
            boolean low) {

        public static StockResponse from(WarehouseStock s) {
            Part part = s.getPart();
            return new StockResponse(
                    part.getId(), part.getSku(), part.getName(),
                    s.getQuantity(), part.getReorderLevel(), s.isLow());
        }
    }

    /**
     * Where a part can be found: one row per warehouse holding it.
     *
     * <p>Warehouses holding none are left out — somewhere with zero is not somewhere to send a
     * picker.
     */
    public record PartLocationResponse(
            Long warehouseId,
            String warehouseName,
            String city,
            int quantity,
            int reorderLevel,
            boolean low) {

        public static PartLocationResponse from(WarehouseStock s) {
            Warehouse w = s.getWarehouse();
            return new PartLocationResponse(
                    w.getId(), w.getName(),
                    w.getAddress() == null ? null : w.getAddress().getCity(),
                    s.getQuantity(), s.getPart().getReorderLevel(), s.isLow());
        }
    }

    /**
     * A completed transfer, showing both ends.
     *
     * <p>{@code StockResponse} names no warehouse, so returning one side of a move left the
     * caller holding a quantity with no way to tell whose it was. Both rows come back, each one
     * saying where it is.
     */
    public record TransferResponse(PartLocationResponse from, PartLocationResponse to) {

        public static TransferResponse of(WarehouseStock source, WarehouseStock destination) {
            return new TransferResponse(
                    PartLocationResponse.from(source), PartLocationResponse.from(destination));
        }
    }

    // ---------------------------------------------------------------- people and places

    public record CustomerResponse(Long id, String name, String phoneNumber, String email) {

        public static CustomerResponse from(Customer c) {
            return new CustomerResponse(c.getId(), c.getName(), c.getPhoneNumber(), c.getEmail());
        }
    }

    public record SupplierResponse(
            Long id, String name, String city, String street, String phoneNumber) {

        public static SupplierResponse from(Supplier s) {
            return new SupplierResponse(
                    s.getId(), s.getName(),
                    s.getAddress() == null ? null : s.getAddress().getCity(),
                    s.getAddress() == null ? null : s.getAddress().getStreet(),
                    s.getPhoneNumber());
        }
    }

    public record EmployeeResponse(
            Long id,
            String fullName,
            BigDecimal salary,
            LocalDate birthdate,
            String city,
            String street,
            ShiftType workShift,
            Long departmentId,
            String departmentName,
            LocalDate hiredOn,
            boolean isManager) {

        public static EmployeeResponse from(Employee e) {
            Department d = e.getDepartment();
            return new EmployeeResponse(
                    e.getId(), e.getFullName(), e.getSalary(), e.getBirthdate(),
                    e.getAddress() == null ? null : e.getAddress().getCity(),
                    e.getAddress() == null ? null : e.getAddress().getStreet(),
                    e.getWorkShift(), d.getId(), d.getName(), e.getHiredOn(), e.isManager());
        }
    }

    public record DepartmentResponse(
            Long id,
            String name,
            DepartmentType type,
            String city,
            String street,
            Long managerId,
            String managerName,
            BigDecimal freeAreaSqm) {

        public static DepartmentResponse from(Department d) {
            Employee manager = d.getManager();
            return new DepartmentResponse(
                    d.getId(), d.getName(), d.getType(),
                    d.getAddress() == null ? null : d.getAddress().getCity(),
                    d.getAddress() == null ? null : d.getAddress().getStreet(),
                    manager == null ? null : manager.getId(),
                    manager == null ? null : manager.getFullName(),
                    d instanceof Warehouse w ? w.getFreeAreaSqm() : null);
        }
    }

    /**
     * A department with no manager, and how many of its staff could take the post.
     *
     * <p>Returned after hiring somebody into a headless department, so the caller can offer the
     * promotion straight away rather than the vacancy going unnoticed.
     */
    public record ManagerVacancy(Long departmentId, String departmentName, long eligibleEmployees) {}

    /**
     * The result of hiring somebody.
     *
     * <p>{@code managerVacancy} is null in the ordinary case. When it is present, the department
     * this employee just joined has nobody in charge — accept by
     * {@code PATCH /api/departments/{id}} with a {@code managerId}.
     */
    public record NewEmployeeResponse(EmployeeResponse employee, ManagerVacancy managerVacancy) {}
}
