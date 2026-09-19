package kkdugi.app.admin.i18n.models;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminMessageParams extends BaseParams {

    private String code;
    private String message;

    public AdminMessageParams() {
    }

    public AdminMessageParams(String code, String message, int page, int pageSize) {
        this.code = code;
        this.message = message;
        setPage(page);
        setPageSize(pageSize);
    }
}
