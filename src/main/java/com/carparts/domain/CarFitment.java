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
 * One car a part fits.
 *
 * <p>A part fits many cars, so this is a table rather than a column. {@code @MapsId} ties the
 * {@code part} association to the {@code partId} already inside the key, so the foreign key and
 * the primary key share one column instead of being kept in step by hand.
 */
@Entity
@Table(name = "car_fitment")
public class CarFitment {

    @EmbeddedId
    private CarFitmentId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("partId")
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    /** Guarded by {@code ck_car_fitment_year_range}: never earlier than {@code yearFrom}. */
    @Column(name = "year_to", nullable = false)
    private Short yearTo;

    protected CarFitment() {
        // for JPA
    }

    public CarFitment(Part part, String make, String model, Short yearFrom, Short yearTo) {
        this.part = part;
        this.id = new CarFitmentId(part.getId(), make, model, yearFrom);
        this.yearTo = yearTo;
    }

    public CarFitmentId getId() {
        return id;
    }

    public Part getPart() {
        return part;
    }

    public String getMake() {
        return id.getMake();
    }

    public String getModel() {
        return id.getModel();
    }

    public Short getYearFrom() {
        return id.getYearFrom();
    }

    public Short getYearTo() {
        return yearTo;
    }

    public void setYearTo(Short yearTo) {
        this.yearTo = yearTo;
    }

    /** True when this fitment covers the given model year. */
    public boolean covers(short year) {
        return year >= id.getYearFrom() && year <= yearTo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CarFitment other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return CarFitment.class.hashCode();
    }

    @Override
    public String toString() {
        return "CarFitment{" + id + "}";
    }
}
