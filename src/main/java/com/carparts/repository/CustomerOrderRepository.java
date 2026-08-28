package com.carparts.repository;

import com.carparts.domain.CustomerOrder;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    // No findByCustomerId. GET /api/orders?customerId= answers it through
    // ReportingRepository.orderSummaries, paged and in two queries; the sub-resource that used
    // this was removed in step 6 because it returned every order a customer had ever placed,
    // unpaged, at about six queries per order.

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
            JOIN FETCH o.branch
            JOIN FETCH o.warehouse
            LEFT JOIN FETCH o.items i
            LEFT JOIN FETCH i.part
            WHERE o.id = :orderId
            """)
    Optional<CustomerOrder> findByIdWithItems(@Param("orderId") Long orderId);

    // No search()/searchFiltered() here. Orders are listed by
    // ReportingRepository.orderSummaries, which reads v_order_total and joins only for the
    // names a listing shows — a JPA page over the entities cost about six queries per row.
    // This pair was the earlier attempt and never had a caller once that landed.
}
