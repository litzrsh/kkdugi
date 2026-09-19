package kkdugi.app.admin.code.models;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminCodePersistRequest {

    private final List<AdminCode> insert;
    private final List<AdminCode> update;
    private final List<AdminCode> delete;

    public List<AdminCode> insertOrEmpty() {
        return insert != null ? insert : List.of();
    }

    public List<AdminCode> updateOrEmpty() {
        return update != null ? update : List.of();
    }

    public List<AdminCode> deleteOrEmpty() {
        return delete != null ? delete : List.of();
    }
}
