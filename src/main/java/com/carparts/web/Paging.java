package com.carparts.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The one place a page request is built.
 *
 * <p>Every listing in the API is paged and capped, and the clamp was written out identically in
 * seven controllers. A cap only protects the server if <em>every</em> copy has it, and the one
 * endpoint that shipped without one returned every stock row a warehouse held.
 */
public final class Paging {

    /**
     * The largest page anyone may ask for.
     *
     * <p>An uncapped {@code size} is an invitation to request an entire table in one call, and
     * the server would try.
     */
    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    /**
     * Clamps a page request and closes its ordering.
     *
     * <p>{@code uniqueTiebreaker} is a required argument rather than something a caller may
     * remember, because forgetting it is a silent data-loss bug. {@code LIMIT/OFFSET} across a
     * tie has no defined order between pages, so a tied row can be returned on two consecutive
     * pages while another is never returned at all — and the reported total still counts both.
     * That was measured on the low-stock report: ten rows paged two at a time yielded nine
     * distinct. Sorting by a name, a price or a date invites exactly that, because none of them
     * is unique.
     *
     * <p>A negative page becomes the first, and a size outside 1..{@value #MAX_SIZE} is pulled
     * into range rather than rejected — a caller asking for too much gets the most they may
     * have, which is friendlier than a 400 and no less bounded.
     *
     * @param sort what the caller actually wants to order by
     * @param uniqueTiebreaker a property unique within the result, appended last
     */
    public static PageRequest of(int page, int size, Sort sort, String uniqueTiebreaker) {
        return PageRequest.of(number(page), size(size), sort.and(Sort.by(uniqueTiebreaker)));
    }

    /** The page number a request settles on, for a listing that assembles its own {@code Page}. */
    public static int number(int page) {
        return Math.max(page, 0);
    }

    /** The page size a request settles on, for a listing that assembles its own {@code Page}. */
    public static int size(int size) {
        return Math.clamp(size, 1, MAX_SIZE);
    }
}
