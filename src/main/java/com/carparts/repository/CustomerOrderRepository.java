package com.carparts.repository;

import com.carparts.domain.CustomerOrder;
import com.carparts.domain.OrderStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
            JOIN FETCH o.branch
            JOIN FETCH o.warehouse
            LEFT JOIN FETCH o.items i
            LEFT JOIN FETCH i.part
            WHERE o.id = :orderId
            """)
    Optional<CustomerOrder> findByIdWithItems(@Param("orderId") Long orderId);

    /**
     * Orders narrowed by any combination of status, branch and date range.
     *
     * <p>Every filter is optional, and an absent one widens rather than being passed as null:
     * no status means every status, no date bound means an open-ended one. That is not a stylistic
     * choice. PostgreSQL type-checks the whole predicate even where it would short-circuit, and a
     * null enum parameter arrives with nothing to infer a type from — {@code :status IS NULL OR
     * o.status = :status} fails outright with <em>could not determine data type of parameter</em>.
     * The same trap as passing a null string to {@code LOWER()} in {@code PartRepository}.
     *
     * <p>{@code branchId} needs no such treatment: it is only ever compared to a bigint column,
     * which the driver can type on its own.
     *
     * <p>Paged rather than returning the lot. An order list only grows, and an endpoint that
     * returns all of it works on demo data and falls over in a year.
     */
    default Page<CustomerOrder> search(OrderStatus status, Long branchId,
                                       LocalDate from, LocalDate to, Pageable pageable) {
        return searchFiltered(
                status == null ? List.of(OrderStatus.values()) : List.of(status),
                branchId,
                from == null ? LocalDate.of(1900, 1, 1) : from,
                to == null ? LocalDate.of(9999, 12, 31) : to,
                pageable);
    }

    @Query("""
            SELECT o FROM CustomerOrder o
            WHERE o.status IN :statuses
              AND (:branchId IS NULL OR o.branch.id = :branchId)
              AND o.orderDate BETWEEN :from AND :to
            """)
    Page<CustomerOrder> searchFiltered(@Param("statuses") List<OrderStatus> statuses,
                                       @Param("branchId") Long branchId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to,
                                       Pageable pageable);
}
