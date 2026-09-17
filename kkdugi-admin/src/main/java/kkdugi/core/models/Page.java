package kkdugi.core.models;

import java.util.List;

public record Page<T>(
        int page,
        int pageSize,
        long totalItems,
        long totalPages,
        List<T> contents
) {

    public static <T> Page<T> of(List<T> contents, int page, int pageSize, long totalItems) {
        long totalPages = Math.max(1, (long) Math.ceil((double) totalItems / pageSize));
        return new Page<>(page, pageSize, totalItems, totalPages, contents);
    }
}
