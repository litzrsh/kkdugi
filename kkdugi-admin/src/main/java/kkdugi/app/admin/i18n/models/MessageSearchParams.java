package kkdugi.app.admin.i18n.models;

public record MessageSearchParams(
        String code,
        String message,
        Integer page,
        Integer pageSize
) {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 200;

    public int resolvedPage() {
        return page == null || page < 1 ? DEFAULT_PAGE : page;
    }

    public int resolvedPageSize() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    public int offset() {
        return (resolvedPage() - 1) * resolvedPageSize();
    }
}
