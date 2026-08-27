package com.carparts.repository;

import com.carparts.domain.Supplier;
import com.carparts.support.Text;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    // No findByName or existsByName. Neither had a caller, and a pre-check for a duplicate name
    // is a race as well as a duplicate of uq_supplier_name — two requests can each find nothing
    // and then both insert. The constraint is the guarantee; search() covers the lookup.

    /**
     * Finds a supplier by name or phone number.
     *
     * <p>Searching by name is how a caller gets the id that {@code GET /api/parts?supplierId=}
     * wants, so the two compose: find the vendor here, then list what the shop buys from them.
     *
     * <p>An absent term becomes {@code %} rather than a null, for the reason set out on
     * {@code CustomerRepository.search}.
     */
    default Page<Supplier> search(String search, Pageable pageable) {
        return searchByPattern(Text.likePattern(search), pageable);
    }

    /** The filter, written once, so the rows and the count cannot disagree. */
    String FILTER =
            "WHERE (LOWER(s.name) LIKE :pattern OR LOWER(s.phoneNumber) LIKE :pattern)";

    @Query(value = "SELECT s FROM Supplier s " + FILTER,
           countQuery = "SELECT COUNT(s) FROM Supplier s " + FILTER)
    Page<Supplier> searchByPattern(@Param("pattern") String pattern, Pageable pageable);
}
