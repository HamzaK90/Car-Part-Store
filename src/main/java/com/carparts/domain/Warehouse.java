package com.carparts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * A department that stores parts.
 *
 * <p>The {@code type} column on this table is not mapped. It exists so the composite foreign
 * key {@code (department_id, type)} can point at {@code department}'s unique key, which is what
 * stops a branch from also being a warehouse. Its DEFAULT and CHECK keep it correct without
 * Java touching it.
 */
@Entity
@Table(name = "warehouse")
@PrimaryKeyJoinColumn(name = "department_id")
public class Warehouse extends Department {

    @Column(name = "free_area_sqm", nullable = false, precision = 10, scale = 2)
    private BigDecimal freeAreaSqm;

    // No stock collection here on purpose. Mapping one would mean warehouse.getStock() pulls
    // every part the warehouse holds — thousands of rows in a real one — with no filter, no
    // paging and no way to stop a serializer or a debugger triggering it by accident.
    //
    // The rows are still there and still reachable: WarehouseStockRepository.findByWarehouseId()
    // for a listing, and v_low_stock via ReportingRepository.lowStock() for the report. Both
    // can filter and page, which a mapped collection cannot.
    //
    // The other direction stays: WarehouseStock.warehouse is a single reference, which is cheap
    // and required by @MapsId.

    protected Warehouse() {
        // for JPA
    }

    public Warehouse(String name, Address address, BigDecimal freeAreaSqm) {
        super(DepartmentType.WAREHOUSE, name, address);
        this.freeAreaSqm = freeAreaSqm;
    }

    public BigDecimal getFreeAreaSqm() {
        return freeAreaSqm;
    }

    public void setFreeAreaSqm(BigDecimal freeAreaSqm) {
        this.freeAreaSqm = freeAreaSqm;
    }
}
