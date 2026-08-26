package com.carparts.repository;

import com.carparts.domain.CarFitment;
import com.carparts.domain.Part;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartRepository extends JpaRepository<Part, Long> {

    Optional<Part> findBySku(String sku);

    Page<Part> findBySupplierId(Long supplierId, Pageable pageable);

    /**
     * The catalogue search behind {@code GET /api/parts?search=}. Both filters are optional.
     *
     * <p>An absent search term becomes {@code %}, which matches everything, rather than being
     * passed to the query as null. That is not cosmetic: PostgreSQL type-checks the whole
     * predicate even where it would short-circuit, and a null string parameter arrives with no
     * inferable type, so {@code LOWER(?)} fails with <em>function lower(bytea) does not
     * exist</em>. Turning the term into a pattern here keeps the parameter unambiguously a
     * string and the query free of casts.
     *
     * <p>A null {@code supplierId} needs no such treatment — it is only ever compared to a
     * bigint column, which the driver can type on its own.
     */
    default Page<Part> search(String search, Long supplierId, Pageable pageable) {
        String pattern = (search == null || search.isBlank())
                ? "%"
                : "%" + search.strip().toLowerCase() + "%";
        return searchByPattern(pattern, supplierId, pageable);
    }

    @Query("""
            SELECT p FROM Part p
            WHERE (LOWER(p.name) LIKE :pattern OR LOWER(p.sku) LIKE :pattern)
              AND (:supplierId IS NULL OR p.supplier.id = :supplierId)
            """)
    Page<Part> searchByPattern(@Param("pattern") String pattern,
                               @Param("supplierId") Long supplierId,
                               Pageable pageable);

    /**
     * The cars a part fits.
     *
     * <p>Fitments are a collection on {@link Part}, but loading them through this query avoids
     * dragging the whole part graph back for an endpoint that only needs the list.
     */
    @Query("SELECT f FROM CarFitment f WHERE f.part.id = :partId ORDER BY f.id.make, f.id.model")
    List<CarFitment> findFitments(@Param("partId") Long partId);
}
