package com.carparts.service;

import java.util.List;

/**
 * The warehouse cannot cover the order. Becomes a 409 in step 6, with stock unchanged —
 * acceptance criterion 3.
 *
 * <p>Carries <em>every</em> shortage, not the first one found. Reporting one at a time makes the
 * caller fix it, resend, and discover the next: three round trips to learn what one response
 * could have said.
 */
public class InsufficientStockException extends RuntimeException {

    /**
     * One part the warehouse is short of.
     *
     * @param available what is actually on the shelf; zero when the warehouse carries no row for
     *     this part at all, which is the same thing from the caller's point of view
     */
    public record Shortage(Long partId, String sku, int requested, int available) {

        public int shortBy() {
            return requested - available;
        }
    }

    private final transient List<Shortage> shortages;

    public InsufficientStockException(Long warehouseId, List<Shortage> shortages) {
        super(describe(warehouseId, shortages));
        this.shortages = List.copyOf(shortages);
    }

    public List<Shortage> getShortages() {
        return shortages;
    }

    private static String describe(Long warehouseId, List<Shortage> shortages) {
        StringBuilder sb = new StringBuilder("warehouse ")
                .append(warehouseId)
                .append(" cannot cover this order: ");
        for (int i = 0; i < shortages.size(); i++) {
            Shortage s = shortages.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(s.sku())
              .append(" requested ").append(s.requested())
              .append(", available ").append(s.available());
        }
        return sb.toString();
    }
}
