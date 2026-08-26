package com.carparts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** One line of an order: a part, how many, and what they cost. */
@Entity
@Table(name = "order_item")
public class OrderItem {

    @EmbeddedId
    private OrderItemId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("orderId")
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("partId")
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /**
     * What the part cost when this order was placed, copied from {@code part.price} at that
     * moment and never read back from the catalogue afterwards.
     *
     * <p>This is why repricing a part cannot change the total of an invoice that already
     * exists. There is no setter: a line's price is a fact about the past.
     */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    protected OrderItem() {
        // for JPA
    }

    /** Captures the part's current price as this line's unit price. */
    public OrderItem(CustomerOrder order, Part part, int quantity) {
        this(order, part, quantity, part.getPrice());
    }

    public OrderItem(CustomerOrder order, Part part, int quantity, BigDecimal unitPrice) {
        this.order = order;
        this.part = part;
        this.id = new OrderItemId(order.getId(), part.getId());
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    /** What this line is worth: quantity times the price captured at sale. */
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public OrderItemId getId() {
        return id;
    }

    public CustomerOrder getOrder() {
        return order;
    }

    public Part getPart() {
        return part;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderItem other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return OrderItem.class.hashCode();
    }

    @Override
    public String toString() {
        return "OrderItem{" + id + ", quantity=" + quantity + ", unitPrice=" + unitPrice + "}";
    }
}
