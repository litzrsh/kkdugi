package kkdugi.app.admin.code.models;

import java.util.List;

public record CodePersistRequest(
        List<CodeContent> insert,
        List<CodeContent> update,
        List<CodeContent> delete
) {

    public List<CodeContent> insertOrEmpty() {
        return insert != null ? insert : List.of();
    }

    public List<CodeContent> updateOrEmpty() {
        return update != null ? update : List.of();
    }

    public List<CodeContent> deleteOrEmpty() {
        return delete != null ? delete : List.of();
    }
}
