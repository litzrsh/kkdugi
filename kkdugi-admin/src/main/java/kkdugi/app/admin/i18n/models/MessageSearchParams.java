package kkdugi.app.admin.i18n.models;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageSearchParams extends BaseParams {

    private String code;
    private String message;

    public MessageSearchParams() {
    }

    public MessageSearchParams(String code, String message, int page, int pageSize) {
        this.code = code;
        this.message = message;
        setPage(page);
        setPageSize(pageSize);
    }
}
