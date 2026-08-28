package com.carparts.repository;

import com.carparts.domain.CarFitment;
import com.carparts.domain.Part;
import com.carparts.support.Text;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartRepository extends JpaRepository<Part, Long> {

    // No findBySku or findBySupplierId. search() answers both — ?search= matches the SKU, and
    // ?supplierId= filters — and a second way to ask one question is a second thing to keep in
    // step. Neither had a caller.

    /**
     * The catalogue search.
     *
     * <p>An absent search term becomes {@code %} rather than being passed as null. PostgreSQL
     * type-checks the whole predicate even where it would short-circuit, and a null string
     * parameter arrives with no inferable type, so {@code LOWER(?)} fails with <em>function
     * lower(bytea) does not exist</em>.
     *
     * <p>The other filters are only ever compared to typed columns, so a null in those is fine.
     */
    default Page<Part> search(String search, Long supplierId, BigDecimal minPrice,
                              BigDecimal maxPrice, String make, String model, Short year,
                              Pageable pageable) {
        return searchByPattern(Text.likePattern(search), supplierId, minPrice, maxPrice,
                Text.lowerOrNull(make), Text.lowerOrNull(model), year, pageable);
    }

    /**
     * The filters, written once.
     *
     * <p>A paged query needs a matching count query, and Spring Data will not derive one for a
     * fetch join. Spelling the predicate out twice is how a page comes to return five rows while
     * reporting a total of eighteen — the two copies drift, nothing errors, and it reads as a
     * paging bug. Sharing the constant makes that impossible.
     */
    String FILTERS = """
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
            """;

    /**
     * Searches the catalogue, with the supplier already loaded.
     *
     * <p>{@code JOIN FETCH p.supplier} is not decoration. Every result carries its supplier's
     * name, and without the fetch each distinct supplier on the page costs an extra query —
     * measured at twelve statements for eight parts from eight suppliers, against two once
     * joined. Demo data with three suppliers hides that almost entirely.
     *
     * <p>Fetch-joining a <em>collection</em> alongside pagination would be a mistake: Hibernate
     * would have to page in memory. {@code supplier} is a to-one, so the page stays in SQL.
     *
     * <p>The {@code make}/{@code model}/{@code year} predicate answers the question this business
     * is actually asked — <em>what fits my 2017 Civic</em> — as an {@code EXISTS} rather than a
     * join, so a part matching two of its own fitments still appears once.
     */
    @Query(value = "SELECT p FROM Part p JOIN FETCH p.supplier s " + FILTERS,
           countQuery = "SELECT COUNT(p) FROM Part p " + FILTERS)
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
     * <p>Loaded through this query rather than the collection on {@link Part}, so an endpoint
     * that only needs the list does not drag the whole part graph back with it.
     */
    @Query("SELECT f FROM CarFitment f WHERE f.part.id = :partId ORDER BY f.id.make, f.id.model")
    List<CarFitment> findFitments(@Param("partId") Long partId);
}
