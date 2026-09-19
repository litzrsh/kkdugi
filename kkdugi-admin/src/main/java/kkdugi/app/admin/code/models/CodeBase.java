package kkdugi.app.admin.code.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CodeBase extends BaseModel {

    private String id;
    private String parentId;
    private String code;
    private String extra1;
    private String extra2;
    private String extra3;
    private String extra4;
    private String extra5;
    private Integer level;
    private String path;
    private Integer sort;
    private String use;

    public CodeBase() {
    }

    public CodeBase(String id, String parentId, String code,
            String extra1, String extra2, String extra3, String extra4, String extra5,
            Integer level, String path, Integer sort, String use) {
        this.id = id;
        this.parentId = parentId;
        this.code = code;
        this.extra1 = extra1;
        this.extra2 = extra2;
        this.extra3 = extra3;
        this.extra4 = extra4;
        this.extra5 = extra5;
        this.level = level;
        this.path = path;
        this.sort = sort;
        this.use = use;
    }
}
