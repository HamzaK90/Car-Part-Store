package com.carparts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** An item in the catalogue. */
@Entity
@Table(name = "part")
public class Part {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "part_id")
    private Long id;

    @Column(name = "sku", nullable = false, unique = true, length = 32)
    private String sku;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * What the part costs <em>today</em>.
     *
     * <p>Deliberately not what any existing order was billed. {@link OrderItem} captures the
     * price at the time of sale, so changing this cannot move the total of an invoice that
     * already exists.
     */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "manufacturing_place", length = 100)
    private String manufacturingPlace;

    /**
     * The stock level below which this part counts as running low, per part rather than per
     * warehouse — a brake disc and a wiper blade run low at different counts.
     *
     * <p>Zero means "never flag this one": stock cannot go negative, so it can never fall
     * below zero and the part stays out of {@code v_low_stock}.
     */
    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @OneToMany(mappedBy = "part", fetch = FetchType.LAZY)
    private List<CarFitment> fitments = new ArrayList<>();

    protected Part() {
        // for JPA
    }

    public Part(String sku, String name, BigDecimal price, BigDecimal weightKg, Supplier supplier) {
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.weightKg = weightKg;
        this.supplier = supplier;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(BigDecimal weightKg) {
        this.weightKg = weightKg;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getManufacturingPlace() {
        return manufacturingPlace;
    }

    public void setManufacturingPlace(String manufacturingPlace) {
        this.manufacturingPlace = manufacturingPlace;
    }

    public int getReorderLevel() {
        return reorderLevel;
    }

    public void setReorderLevel(int reorderLevel) {
        this.reorderLevel = reorderLevel;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    public List<CarFitment> getFitments() {
        return fitments;
    }

    /**
     * Whether this part fits a given car.
     *
     * <p>Answers the question a customer actually asks — "does this fit my 2017 Civic" — which
     * no single fitment row can, since it takes checking every one of them for a year range
     * that covers it.
     */
    public boolean fitsCar(String make, String model, short year) {
        return fitments.stream()
                .anyMatch(f -> f.getMake().equalsIgnoreCase(make)
                        && f.getModel().equalsIgnoreCase(model)
                        && f.covers(year));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Part other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Part.class.hashCode();
    }

    @Override
    public String toString() {
        return "Part{id=" + id + ", sku='" + sku + "'}";
    }
}
