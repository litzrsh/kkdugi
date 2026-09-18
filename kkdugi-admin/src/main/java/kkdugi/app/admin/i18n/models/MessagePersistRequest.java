package kkdugi.app.admin.i18n.models;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MessagePersistRequest {

    private final List<MessageContent> insert;
    private final List<MessageContent> update;
    private final List<MessageContent> delete;

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
