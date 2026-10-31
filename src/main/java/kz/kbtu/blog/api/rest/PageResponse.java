package kz.kbtu.blog.api.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.function.Function;
import kz.kbtu.blog.application.CursorPage;

/** {@code nextCursor} is left out of the JSON on the last page. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageResponse<T>(List<T> items, String nextCursor) {

    static <V, T> PageResponse<T> from(CursorPage<V> page, Function<V, T> toResponse) {
        return new PageResponse<>(page.items().stream().map(toResponse).toList(), page.nextCursor());
    }
}
