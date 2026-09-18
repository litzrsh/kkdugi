package kkdugi.app.admin.code.models;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CodePersistRequest {

    private final List<CodeContent> insert;
    private final List<CodeContent> update;
    private final List<CodeContent> delete;

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
