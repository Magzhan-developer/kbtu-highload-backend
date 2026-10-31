package kz.kbtu.blog.application;

import java.util.List;
import java.util.function.Function;
import kz.kbtu.blog.domain.exception.InvalidInputException;

/** One page of results. {@code nextCursor} is null on the last page. */
public record CursorPage<T>(List<T> items, String nextCursor) {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    /** A limit of 0 means "not set": it is also the Protobuf default for an int32. */
    static int resolveLimit(int requested) {
        if (requested == 0) {
            return DEFAULT_LIMIT;
        }
        if (requested < 0 || requested > MAX_LIMIT) {
            throw new InvalidInputException("limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }

    /**
     * Builds a page from rows fetched with {@code limit + 1}. The extra row is never
     * returned: it only shows that another page exists.
     */
    static <E, T> CursorPage<T> fromRows(
            List<E> rows, int limit, Function<E, T> toView, Function<E, Cursor> toCursor) {
        boolean hasMore = rows.size() > limit;
        List<E> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<T> items = pageRows.stream().map(toView).toList();
        String nextCursor = hasMore ? toCursor.apply(pageRows.get(limit - 1)).encode() : null;
        return new CursorPage<>(items, nextCursor);
    }
}
