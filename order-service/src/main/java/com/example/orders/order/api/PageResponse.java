package com.example.orders.order.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Stable page envelope for every paginated endpoint — the same shape inventory-service returns,
 * so a client sees one pagination contract across the system.
 *
 * <p>Deliberately not Spring Data's own {@code Page} serialisation: that shape is an internal
 * detail, it changes between Spring versions, and it leaks {@code pageable}/{@code sort} objects
 * into a public contract.
 *
 * @param content       the items on this page
 * @param page          zero-based page index
 * @param size          page size that was applied (already clamped)
 * @param totalElements total number of matching items
 * @param totalPages    total number of pages
 * @param last          whether this is the last page
 * @param <T>           item type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    /**
     * @param page the page as returned by the repository
     * @param <T>  item type
     * @return the envelope
     */
    public static <T> PageResponse<T> of(Page<T> page) {
        return of(page, Function.identity());
    }

    /**
     * @param page   the page as returned by the repository
     * @param mapper entity to response mapping
     * @param <E>    entity type
     * @param <T>    response type
     * @return the envelope
     */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
