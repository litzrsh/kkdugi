package kkdugi.app.admin.i18n;

public record MessageRowCommand(
        CrudType crudType,
        String msgCd,
        String langCd,
        String msgVal
) {
}
