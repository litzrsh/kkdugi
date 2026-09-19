package kkdugi.app.admin.menu.models;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminMenuPersistRequest {

    private final List<AdminMenu> insert;
    private final List<AdminMenu> update;
    private final List<AdminMenu> delete;

    public List<AdminMenu> insertOrEmpty() {
        return insert != null ? insert : List.of();
    }

    public List<AdminMenu> updateOrEmpty() {
        return update != null ? update : List.of();
    }

    public List<AdminMenu> deleteOrEmpty() {
        return delete != null ? delete : List.of();
    }
}
