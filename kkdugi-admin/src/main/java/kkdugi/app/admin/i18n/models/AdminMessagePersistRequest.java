package kkdugi.app.admin.i18n.models;

import java.util.List;
import kkdugi.core.security.models.AuthorityBatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminMessagePersistRequest implements AuthorityBatch {

    private final List<AdminMessage> insert;
    private final List<AdminMessage> update;
    private final List<AdminMessage> delete;

    public List<AdminMessage> insertOrEmpty() {
        return insert != null ? insert : List.of();
    }

    public List<AdminMessage> updateOrEmpty() {
        return update != null ? update : List.of();
    }

    public List<AdminMessage> deleteOrEmpty() {
        return delete != null ? delete : List.of();
    }
}
