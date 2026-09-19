package kkdugi.app.admin.menu.models;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MenuPersistRequest {

    private final List<MenuContent> insert;
    private final List<MenuContent> update;
    private final List<MenuContent> delete;

    public List<MenuContent> insertOrEmpty() {
        return insert != null ? insert : List.of();
    }

    public List<MenuContent> updateOrEmpty() {
        return update != null ? update : List.of();
    }

    public List<MenuContent> deleteOrEmpty() {
        return delete != null ? delete : List.of();
    }
}
