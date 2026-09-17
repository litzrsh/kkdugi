package kkdugi.app.admin.i18n.models;

import java.util.List;

public record MessagePersistRequest(
        List<MessageContent> insert,
        List<MessageContent> update,
        List<MessageContent> delete
) {

    public List<MessageContent> insertOrEmpty() {
        return insert != null ? insert : List.of();
    }

    public List<MessageContent> updateOrEmpty() {
        return update != null ? update : List.of();
    }

    public List<MessageContent> deleteOrEmpty() {
        return delete != null ? delete : List.of();
    }
}
