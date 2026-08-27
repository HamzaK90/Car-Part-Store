package com.carparts.repository;

import com.carparts.domain.Customer;
import com.carparts.support.Text;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // No findByPhoneNumber, existsByPhoneNumber or existsByEmail. None had a caller, and the
    // two exists- checks would have been the wrong tool anyway: they are a race, since two
    // requests can each find nothing and then both insert. uq_customer_phone and
    // uq_customer_email are the guarantee, and ApiExceptionHandler already turns either into a
    // sentence. search() covers looking somebody up.

    /**
     * Finds a customer by name, phone number or email.
     *
     * <p>Phone is the field a counter asks for, so it is matched as readily as the name. An
     * absent term becomes {@code %} rather than being passed as null: PostgreSQL types an
     * untyped null string parameter as {@code bytea} and {@code LOWER(?)} then fails with
     * <em>function lower(bytea) does not exist</em>, before any row is examined.
     */
    default Page<Customer> search(String search, Pageable pageable) {
        return searchByPattern(Text.likePattern(search), pageable);
    }

    /**
     * The filter, written once.
     *
     * <p>A paged query needs its own count query, and two copies of a predicate drift — a page
     * returning five rows while reporting eighteen, with nothing erroring. Shared for the same
     * reason as {@code PartRepository.FILTERS}.
     *
     * <p>{@code email} is nullable, so its comparison yields NULL rather than false for a
     * customer without one. That is harmless here: the name is NOT NULL and matches {@code %}
     * unconditionally, so an unfiltered listing still returns everybody.
     */
    String FILTER = """
            WHERE (LOWER(c.name) LIKE :pattern
                   OR LOWER(c.phoneNumber) LIKE :pattern
                   OR LOWER(c.email) LIKE :pattern)
            """;

    @Query(value = "SELECT c FROM Customer c " + FILTER,
           countQuery = "SELECT COUNT(c) FROM Customer c " + FILTER)
    Page<Customer> searchByPattern(@Param("pattern") String pattern, Pageable pageable);
}
