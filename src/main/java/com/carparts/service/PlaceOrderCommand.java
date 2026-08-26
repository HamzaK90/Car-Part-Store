package com.carparts.service;

import java.util.List;

/**
 * What the caller is asking for when placing an order.
 *
 * <p>Notice what is absent: the handling employee. That is not an oversight — it comes from the
 * authenticated session, never from the request body, so a salesperson cannot record an order as
 * handled by a colleague. Leaving it out of this record is what makes that structural rather
 * than a rule somebody has to remember. See {@link OrderService#placeOrder}.
 *
 * @param customerId  who is buying
 * @param branchId    the sales location taking the order; must be a branch
 * @param warehouseId the warehouse whose stock fills it; must be a warehouse
 * @param lines       what they want, at least one
 */
public record PlaceOrderCommand(
        Long customerId,
        Long branchId,
        Long warehouseId,
        List<Line> lines) {

    /**
     * One requested part and how many of it.
     *
     * <p>The same part may appear more than once; {@link OrderService} adds the quantities
     * together before checking stock. Treating them separately would check 3 against stock and
     * then 3 again, and happily sell 6 units of a part that had 4.
     */
    public record Line(Long partId, int quantity) {}
}
