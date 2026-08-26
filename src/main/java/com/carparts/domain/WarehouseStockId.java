package com.carparts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Identity of a stock row: which part, in which warehouse. */
@Embeddable
public class WarehouseStockId implements Serializable {

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "part_id")
    private Long partId;

    protected WarehouseStockId() {
        // for JPA
    }

    public WarehouseStockId(Long warehouseId, Long partId) {
        this.warehouseId = warehouseId;
        this.partId = partId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public Long getPartId() {
        return partId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WarehouseStockId other)) {
            return false;
        }
        return Objects.equals(warehouseId, other.warehouseId) && Objects.equals(partId, other.partId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(warehouseId, partId);
    }

    @Override
    public String toString() {
        return "part " + partId + " @ warehouse " + warehouseId;
    }
}
