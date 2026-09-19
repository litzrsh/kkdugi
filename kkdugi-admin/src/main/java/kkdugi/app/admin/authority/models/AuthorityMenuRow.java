package kkdugi.app.admin.authority.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** 메뉴 한 행 + 특정 권한이 그 메뉴에 갖는 RBAC 값(부여가 없으면 0). */
@Getter
@Setter
public class AuthorityMenuRow extends BaseModel {

    private String id;
    private String parentId;
    private String icon;
    private String program;
    private Integer level;
    private String path;
    private Integer sort;
    private String use;
    private Integer rbac;
}
