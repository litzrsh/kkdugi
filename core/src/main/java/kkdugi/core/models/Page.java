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
            this.contents = new ArrayList<>();
            this.totalItems = 0L;
        } else {
            this.contents = contents;
            this.totalItems = contents.get(0).getTotalItems();
        }
    }

    public long getTotalPages() {
        return (long) Math.max(1, Math.ceil((double) this.totalItems / this.pageSize));
    }
}
