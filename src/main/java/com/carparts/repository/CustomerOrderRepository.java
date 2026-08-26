package com.carparts.repository;

import com.carparts.domain.CustomerOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    List<CustomerOrder> findByCustomerId(Long customerId);

    /**
     * An order with everything an invoice needs, in one query.
     *
     * <p>The joins are explicit because the associations are lazy: without them, rendering an
     * invoice would issue a fresh select per line. {@code DISTINCT} keeps the order from being
     * repeated once per item by the join.
     */
    @Query("""
            SELECT DISTINCT o FROM CustomerOrder o
            JOIN FETCH o.customer
            LEFT JOIN FETCH o.employee
            LEFT JOIN FETCH o.items i
            LEFT JOIN FETCH i.part
            WHERE o.id = :orderId
            """)
    Optional<CustomerOrder> findByIdWithItems(@Param("orderId") Long orderId);
}
