package com.carparts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Identity of a {@link CarFitment}: the part, plus the car and the first model year it fits.
 *
 * <p>{@code yearTo} is not part of the key — it is the one thing about a fitment that can be
 * corrected without making it a different fitment.
 */
@Embeddable
public class CarFitmentId implements Serializable {

    @Column(name = "part_id")
    private Long partId;

    @Column(name = "make", length = 50)
    private String make;

    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "year_from")
    private Short yearFrom;

    protected CarFitmentId() {
        // for JPA
    }

    public CarFitmentId(Long partId, String make, String model, Short yearFrom) {
        this.partId = partId;
        this.make = make;
        this.model = model;
        this.yearFrom = yearFrom;
    }

    public Long getPartId() {
        return partId;
    }

    public String getMake() {
        return make;
    }

    public String getModel() {
        return model;
    }

    public Short getYearFrom() {
        return yearFrom;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CarFitmentId other)) {
            return false;
        }
        return Objects.equals(partId, other.partId)
                && Objects.equals(make, other.make)
                && Objects.equals(model, other.model)
                && Objects.equals(yearFrom, other.yearFrom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(partId, make, model, yearFrom);
    }

    @Override
    public String toString() {
        return make + " " + model + " from " + yearFrom + " (part " + partId + ")";
    }
}
