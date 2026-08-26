package com.carparts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Identity of an order line: which order, which part.
 *
 * <p>A part can therefore appear at most once per order. Two lines of the same part belong as
 * one line with a larger quantity, not as two rows somebody has to remember to add up.
 */
@Embeddable
public class OrderItemId implements Serializable {

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "part_id")
    private Long partId;

    protected OrderItemId() {
        // for JPA
    }

    public OrderItemId(Long orderId, Long partId) {
        this.orderId = orderId;
        this.partId = partId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getPartId() {
        return partId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderItemId other)) {
            return false;
        }
        return Objects.equals(orderId, other.orderId) && Objects.equals(partId, other.partId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, partId);
    }

    @Override
    public String toString() {
        return "order " + orderId + " / part " + partId;
    }
}
