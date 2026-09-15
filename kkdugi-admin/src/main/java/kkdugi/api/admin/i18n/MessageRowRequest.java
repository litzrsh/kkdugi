package kkdugi.api.admin.i18n;

import kkdugi.app.admin.i18n.CrudType;

public record MessageRowRequest(
        CrudType crudType,
        String msgCd,
        String langCd,
        String msgVal
) {
}
