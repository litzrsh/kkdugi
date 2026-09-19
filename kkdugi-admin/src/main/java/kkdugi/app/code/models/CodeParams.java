package kkdugi.app.code.models;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

/** {@code path}가 있으면 그 경로 코드의 하위, 없고 {@code parentId}가 있으면 그 코드의 하위, 둘 다 없으면 최상위. */
@Getter
@Setter
public class CodeParams extends BaseParams {

    private String parentId;
    private String path;

    public CodeParams() {
    }

    public CodeParams(String parentId, String path, int page, int pageSize) {
        this.parentId = parentId;
        this.path = path;
        setPage(page);
        setPageSize(pageSize);
    }
}
