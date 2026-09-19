package kkdugi.core.models;

import java.util.ArrayList;
import java.util.List;

import kkdugi.core.util.CommonUtils;
import lombok.Getter;

@Getter
public class Page<T extends BaseModel> {

    private final int page;
    private final int pageSize;
    private final long totalItems;
    private final List<T> contents;

    public <P extends BaseParams> Page(List<T> contents, P params) {
        this.page = params.getPage();
        this.pageSize = params.getPageSize();
        if (CommonUtils.isEmpty(contents)) {
            this.totalItems = 0L;
            this.contents = new ArrayList<>();
        } else {
            this.totalItems = contents.get(0).getTotalSize();
            this.contents = contents;
        }
    }

    public long getTotalPages() {
        return (long) Math.max(1, Math.ceil((double) totalItems / pageSize));
    }

    public static <T extends BaseModel, P extends BaseParams> Page<T> of(List<T> contents, P params) {
        return new Page<>(contents, params);
    }
}
