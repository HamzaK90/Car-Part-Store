package com.carparts.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An order placed by a customer.
 *
 * <p>Two departments are involved and they mean different things: {@code branch} is the sales
 * location that took the order, {@code warehouse} is the one whose stock fills it. Each points
 * at its own subtype, so neither can be confused for the other.
 *
 * <p>{@code Order} is a reserved word in SQL, hence the class and table name.
 */
@Entity
@Table(name = "customer_order")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /**
     * The salesperson who handled this order, or null if none was recorded.
     *
     * <p>{@code ct_order_employee_at_branch} requires that whoever is named works at
     * {@code branch}. It is deliberately not re-checked when they later transfer elsewhere: the
     * order records who handled it at the time, and a transfer does not make that untrue.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate = LocalDate.now();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private OrderStatus status = OrderStatus.PLACED;

    /**
     * The lines. Cascading and orphan removal make this a real aggregate: the lines belong to
     * the order and have no life without it, which is also what {@code ON DELETE CASCADE} says
     * in the schema.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected CustomerOrder() {
        // for JPA
    }

    public CustomerOrder(Customer customer, Branch branch, Warehouse warehouse) {
        this.customer = customer;
        this.branch = branch;
        this.warehouse = warehouse;
    }

    /**
     * What the order is worth, summed from the prices captured at sale.
     *
     * <p>Derived, never stored — repricing a part cannot move it. For a report over many orders
     * read {@code v_order_total} rather than loading each order's lines.
     */
    public BigDecimal total() {
        return items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** How many individual units are on the order, across every line. */
    public int unitCount() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }

    /**
     * Adds a line, capturing the part's price as it stands now.
     *
     * <p>Ordering a part already on the order increases that line rather than adding a second
     * one. {@code order_item}'s primary key is {@code (order_id, part_id)}, so two rows for the
     * same part are impossible — without this the duplicate would only fail at flush, far from
     * the code that caused it.
     */
    public OrderItem addLine(Part part, int quantity) {
        OrderItem existing = lineFor(part);
        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + quantity);
            return existing;
        }
        OrderItem item = new OrderItem(this, part, quantity);
        items.add(item);
        return item;
    }

    /** The line for this part, or null if it is not on the order. */
    public OrderItem lineFor(Part part) {
        return items.stream()
                .filter(i -> i.getPart().equals(part))
                .findFirst()
                .orElse(null);
    }

    /**
     * Marks the order filled.
     *
     * <p>Only a PLACED order can be fulfilled. Nothing in the schema enforces the sequence of
     * statuses — {@code order_status} constrains the values, not the moves between them — so
     * this is the only place the rule can live.
     *
     * @throws IllegalStateException if the order has already been fulfilled or cancelled
     */
    public void fulfil() {
        requirePlaced("fulfil");
        this.status = OrderStatus.FULFILLED;
    }

    /**
     * Cancels the order.
     *
     * <p>Only a PLACED order can be cancelled. A fulfilled order has already left the building;
     * reversing it is a return, which this schema does not model, and quietly flipping the
     * status would take it out of revenue without any record of why.
     *
     * @throws IllegalStateException if the order has already been fulfilled or cancelled
     */
    public void cancel() {
        requirePlaced("cancel");
        this.status = OrderStatus.CANCELLED;
    }

    private void requirePlaced(String action) {
        if (status != OrderStatus.PLACED) {
            throw new IllegalStateException(
                    "cannot " + action + " order " + id + ": it is already " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public Branch getBranch() {
        return branch;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDate orderDate) {
        this.orderDate = orderDate;
    }

    public OrderStatus getStatus() {
        return status;
    }

    // No setStatus. Status changes go through fulfil() and cancel(), which enforce that only a
    // PLACED order can move. A public setter would let any caller step around that, leaving the
    // guards as decoration.

    public List<OrderItem> getItems() {
        return items;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CustomerOrder other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return CustomerOrder.class.hashCode();
    }

    @Override
    public String toString() {
        return "CustomerOrder{id=" + id + ", status=" + status + "}";
    }
}
