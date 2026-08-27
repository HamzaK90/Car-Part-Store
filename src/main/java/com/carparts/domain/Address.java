package com.carparts.domain;

import com.carparts.support.Text;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * A postal address, held inline by whatever owns it rather than in a table of its own.
 *
 * <p>Departments, employees and suppliers all carry {@code city} and {@code street} columns
 * with the same names, so one embeddable serves all three without column overrides. An
 * address has no identity of its own — two employees living at the same street are not
 * sharing an address row, they each have their own copy — which is exactly what
 * {@code @Embeddable} models and a separate table would misrepresent.
 */
@Embeddable
public class Address {

    @Column(name = "city", length = 50)
    private String city;

    @Column(name = "street", length = 100)
    private String street;

    protected Address() {
        // for JPA
    }

    /**
     * Blank parts are stored as null, so "no city" has one representation rather than two — a
     * caller who sends {@code ""} and one who omits the field produce the same row.
     */
    public Address(String city, String street) {
        this.city = Text.blankToNull(city);
        this.street = Text.blankToNull(street);
    }

    /**
     * This address with only the parts named replaced.
     *
     * <p>An address is one embedded value, not two columns that move independently: a PATCH
     * naming only the city must carry the existing street through rather than writing null over
     * it. Doing that at each call site meant the same four-way null dance written out per
     * service. {@code current} may be null, for something that has never had an address.
     */
    public static Address merged(Address current, String city, String street) {
        return new Address(
                city != null ? city : (current == null ? null : current.getCity()),
                street != null ? street : (current == null ? null : current.getStreet()));
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    /** Value equality: an address is defined entirely by what it says, not by who holds it. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Address other)) {
            return false;
        }
        return Objects.equals(city, other.city) && Objects.equals(street, other.street);
    }

    @Override
    public int hashCode() {
        return Objects.hash(city, street);
    }

    @Override
    public String toString() {
        return street + ", " + city;
    }
}
