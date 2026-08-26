package com.carparts.repository;

import com.carparts.domain.WarehouseStock;
import com.carparts.domain.WarehouseStockId;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseStockRepository extends JpaRepository<WarehouseStock, WarehouseStockId> {

    List<WarehouseStock> findByWarehouseId(Long warehouseId);

    Optional<WarehouseStock> findByWarehouseIdAndPartId(Long warehouseId, Long partId);

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
}
