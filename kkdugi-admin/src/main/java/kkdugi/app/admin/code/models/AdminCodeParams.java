package kkdugi.app.admin.code.models;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminCodeParams extends BaseParams {

    private String parentId;
    private String path;
    private String code;
    private String name;
    private String use;

    public AdminCodeParams() {
    }

    public AdminCodeParams(String parentId, String path, String code, String name, String use,
            int page, int pageSize) {
        this.parentId = parentId;
        this.path = path;
        this.code = code;
        this.name = name;
        this.use = use;
        setPage(page);
        setPageSize(pageSize);
    }
}
