package com.carparts.repository;

import com.carparts.domain.WarehouseStock;
import com.carparts.domain.WarehouseStockId;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseStockRepository extends JpaRepository<WarehouseStock, WarehouseStockId> {

    /**
     * What a warehouse holds, paged, with each row's part already loaded.
     *
     * <p>The fetch join is what keeps this one query. Every row reports its part's SKU, name and
     * reorder level, so without it each row costs a further select — measured at fourteen
     * statements for ten rows.
     *
     * <p>{@code lowOnly} narrows to what needs reordering. It is a boolean rather than two
     * methods because the predicate is the only difference, and comparing against the part's own
     * {@code reorderLevel} means the threshold stays per part.
     */
    @Query(value = """
            SELECT s FROM WarehouseStock s
            JOIN FETCH s.part p
            WHERE s.warehouse.id = :warehouseId
              AND (:lowOnly = FALSE OR s.quantity < p.reorderLevel)
            """,
           countQuery = """
            SELECT COUNT(s) FROM WarehouseStock s
            WHERE s.warehouse.id = :warehouseId
              AND (:lowOnly = FALSE OR s.quantity < s.part.reorderLevel)
            """)
    Page<WarehouseStock> findByWarehouse(@Param("warehouseId") Long warehouseId,
                                         @Param("lowOnly") boolean lowOnly,
                                         Pageable pageable);

    /**
     * Every warehouse holding a given part.
     *
     * <p>The fulfilment question — <em>where can I get this?</em> — which is otherwise
     * unanswerable without asking each warehouse in turn. Rows with nothing left are excluded:
     * a warehouse that holds zero is not somewhere to send a picker.
     */
    @Query("""
            SELECT s FROM WarehouseStock s
            JOIN FETCH s.warehouse w
            JOIN FETCH s.part
            WHERE s.part.id = :partId AND s.quantity > 0
            ORDER BY s.quantity DESC
            """)
    List<WarehouseStock> findByPartId(@Param("partId") Long partId);

    // No findByWarehouseIdAndPartId. Every caller that reads a single row goes on to change it,
    // and so must take the lock first — lockForUpdate is that read. An unlocked variant sitting
    // beside it is an invitation to use the wrong one.

    /**
     * Locks the stock rows an order needs, in one statement, for the duration of the
     * transaction.
     *
     * <p>{@code PESSIMISTIC_WRITE} becomes {@code SELECT … FOR UPDATE}. This is what makes two
     * simultaneous orders for the last unit safe: the second one waits here, then reads the
     * quantity the first one left behind rather than the one it started with.
     *
     * <p>Ordered by part id on purpose. Two transactions locking the same rows in different
     * orders can deadlock; taking them in a fixed order means one simply waits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM WarehouseStock s
            WHERE s.warehouse.id = :warehouseId AND s.part.id IN :partIds
            ORDER BY s.part.id
            """)
    List<WarehouseStock> lockForUpdate(@Param("warehouseId") Long warehouseId,
                                       @Param("partIds") List<Long> partIds);

    /**
     * Locks one part's rows across several warehouses, for a transfer between them.
     *
     * <p>Ordered by warehouse id for the same reason {@link #lockForUpdate} orders by part id:
     * two transfers running in opposite directions between the same pair would each hold the row
     * the other needs. A fixed order turns a deadlock into a wait.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM WarehouseStock s
            WHERE s.warehouse.id IN :warehouseIds AND s.part.id = :partId
            ORDER BY s.warehouse.id
            """)
    List<WarehouseStock> lockForTransfer(@Param("warehouseIds") List<Long> warehouseIds,
                                         @Param("partId") Long partId);
}
