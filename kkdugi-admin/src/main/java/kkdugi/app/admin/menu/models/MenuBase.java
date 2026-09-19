package kkdugi.app.admin.menu.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MenuBase extends BaseModel {

    private String id;
    private String parentId;
    private String icon;
    private String program;
    private Integer level;
    private String path;
    private Integer sort;
    private String use;
    private String close;

    public MenuBase() {
    }

    public MenuBase(String id, String parentId, String icon, String program,
            Integer level, String path, Integer sort, String use, String close) {
        this.id = id;
        this.parentId = parentId;
        this.icon = icon;
        this.program = program;
        this.level = level;
        this.path = path;
        this.sort = sort;
        this.use = use;
        this.close = close;
    }
}
