package com.carparts.repository;

import com.carparts.domain.CarFitment;
import com.carparts.domain.Part;
import java.math.BigDecimal;
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
    default Page<Part> search(String search, Long supplierId, BigDecimal minPrice,
                              BigDecimal maxPrice, String make, String model, Short year,
                              Pageable pageable) {
        String pattern = (search == null || search.isBlank())
                ? "%"
                : "%" + search.strip().toLowerCase() + "%";
        return searchByPattern(pattern, supplierId, minPrice, maxPrice,
                blankToNull(make), blankToNull(model), year, pageable);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip().toLowerCase();
    }

    /**
     * The catalogue search.
     *
     * <p>{@code JOIN FETCH p.supplier} is not decoration. Every result carries its supplier's
     * name, and without the fetch each distinct supplier on the page costs an extra query —
     * measured at twelve statements for eight parts from eight suppliers, against four when they
     * shared one. Demo data with three suppliers hides that almost completely.
     *
     * <p>The {@code make}/{@code model}/{@code year} predicate answers the question this business
     * is actually asked — <em>what fits my 2017 Civic</em> — using {@code EXISTS} rather than a
     * join so a part matching two of its fitments still appears once.
     */
    @Query(value = """
            SELECT p FROM Part p
            JOIN FETCH p.supplier s
            WHERE (LOWER(p.name) LIKE :pattern OR LOWER(p.sku) LIKE :pattern)
              AND (:supplierId IS NULL OR s.id = :supplierId)
              AND (:minPrice IS NULL OR p.price >= :minPrice)
              AND (:maxPrice IS NULL OR p.price <= :maxPrice)
              AND (:make IS NULL AND :model IS NULL AND :year IS NULL
                   OR EXISTS (SELECT 1 FROM CarFitment f
                              WHERE f.part = p
                                AND (:make  IS NULL OR LOWER(f.id.make)  = :make)
                                AND (:model IS NULL OR LOWER(f.id.model) = :model)
                                AND (:year  IS NULL OR (:year BETWEEN f.id.yearFrom AND f.yearTo))))
            """,
            countQuery = """
            SELECT COUNT(p) FROM Part p
            WHERE (LOWER(p.name) LIKE :pattern OR LOWER(p.sku) LIKE :pattern)
              AND (:supplierId IS NULL OR p.supplier.id = :supplierId)
              AND (:minPrice IS NULL OR p.price >= :minPrice)
              AND (:maxPrice IS NULL OR p.price <= :maxPrice)
              AND (:make IS NULL AND :model IS NULL AND :year IS NULL
                   OR EXISTS (SELECT 1 FROM CarFitment f
                              WHERE f.part = p
                                AND (:make  IS NULL OR LOWER(f.id.make)  = :make)
                                AND (:model IS NULL OR LOWER(f.id.model) = :model)
                                AND (:year  IS NULL OR (:year BETWEEN f.id.yearFrom AND f.yearTo))))
            """)
    Page<Part> searchByPattern(@Param("pattern") String pattern,
                               @Param("supplierId") Long supplierId,
                               @Param("minPrice") BigDecimal minPrice,
                               @Param("maxPrice") BigDecimal maxPrice,
                               @Param("make") String make,
                               @Param("model") String model,
                               @Param("year") Short year,
                               Pageable pageable);

    /** One part with its supplier already loaded, for a detail response. */
    @Query("SELECT p FROM Part p JOIN FETCH p.supplier WHERE p.id = :id")
    Optional<Part> findWithSupplier(@Param("id") Long id);

    /**
     * The cars a part fits.
     *
     * <p>Fitments are a collection on {@link Part}, but loading them through this query avoids
     * dragging the whole part graph back for an endpoint that only needs the list.
     */
    @Query("SELECT f FROM CarFitment f WHERE f.part.id = :partId ORDER BY f.id.make, f.id.model")
    List<CarFitment> findFitments(@Param("partId") Long partId);
}
