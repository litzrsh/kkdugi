package kkdugi.core.models;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Page<T> {

    private final int page;
    private final int pageSize;
    private final long totalItems;
    private final long totalPages;
    private final List<T> contents;

    public static <T> Page<T> of(List<T> contents, int page, int pageSize, long totalItems) {
        long totalPages = Math.max(1, (long) Math.ceil((double) totalItems / pageSize));
        return new Page<>(page, pageSize, totalItems, totalPages, contents);
    }
}
