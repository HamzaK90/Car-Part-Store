package com.carparts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * How much of one part one warehouse holds.
 *
 * <p>The quantity is decremented inside the order transaction, after the row has been locked
 * with {@code SELECT … FOR UPDATE}. {@code ck_warehouse_stock_quantity} is the backstop that
 * makes overselling impossible even if that logic is wrong: the database refuses to store a
 * negative count at all.
 *
 * <p>The warehouse association points at {@link Warehouse}, not {@link Department}, so a branch
 * can never be given stock.
 */
@Entity
@Table(name = "warehouse_stock")
public class WarehouseStock {

    @EmbeddedId
    private WarehouseStockId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("warehouseId")
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("partId")
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected WarehouseStock() {
        // for JPA
    }

    public WarehouseStock(Warehouse warehouse, Part part, int quantity) {
        this.warehouse = warehouse;
        this.part = part;
        this.id = new WarehouseStockId(warehouse.getId(), part.getId());
        this.quantity = quantity;
    }

    /**
     * Whether this row can cover a demand of {@code amount}.
     *
     * <p>Asking before taking lets a service decide what to do — reject the whole order with a
     * clear 409 naming the short part — instead of discovering it mid-mutation. Only meaningful
     * once the row is locked; read it without {@code SELECT … FOR UPDATE} and the answer can be
     * stale by the time you act on it.
     */
    public boolean hasAtLeast(int amount) {
        return quantity >= amount;
    }

    /**
     * Takes {@code amount} units out of stock.
     *
     * @throws IllegalArgumentException if there are not that many to take. The check is here so
     *     the service can report a clean 409 rather than surfacing a constraint violation, but
     *     the constraint remains the thing that actually guarantees it.
     */
    public void decrease(int amount) {
        requirePositive(amount, "take");
        if (amount > quantity) {
            throw new IllegalArgumentException(
                    "cannot take " + amount + " of part " + id.getPartId() + "; only " + quantity + " in stock");
        }
        quantity -= amount;
    }

    public void increase(int amount) {
        // Guarded, though it looks like it needs no guard. Without this, increase(-5) quietly
        // takes five units off the shelf: ck_warehouse_stock_quantity only objects if the
        // result crosses zero, so a negative "delivery" against a full shelf leaves no trace
        // anywhere. The service layer refuses it on the way in, but a rule worth having is one
        // that holds however the object is reached.
        requirePositive(amount, "add");
        quantity += amount;
    }

    private static void requirePositive(int amount, String verb) {
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "cannot " + verb + " " + amount + " units; the amount must be positive");
        }
    }

    /** True when this row has fallen below its part's reorder level. */
    public boolean isLow() {
        return quantity < part.getReorderLevel();
    }

    public WarehouseStockId getId() {
        return id;
    }

    public Warehouse getWarehouse() {
        return warehouse;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WarehouseStock other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return WarehouseStock.class.hashCode();
    }

    @Override
    public String toString() {
        return "WarehouseStock{" + id + ", quantity=" + quantity + "}";
    }
}
