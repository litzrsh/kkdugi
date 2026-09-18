package kkdugi.core.models;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class BaseParams {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 200;

    private int page;
    private int pageSize;

    @JsonIgnore
    public int resolvedPage() {
        return page < 1 ? DEFAULT_PAGE : page;
    }

    @JsonIgnore
    public int resolvedPageSize() {
        return pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    @JsonIgnore
    public int getOffset() {
        return (resolvedPage() - 1) * resolvedPageSize();
    }

    @JsonIgnore
    public int getLimit() {
        return resolvedPageSize();
    }
}
