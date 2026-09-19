package kkdugi.app.admin.authority.models;

import java.util.List;
import java.util.Map;

import kkdugi.core.models.Tree;

import lombok.Getter;
import lombok.Setter;

/**
 * 권한 화면의 메뉴 트리 노드. 메뉴 관리({@code app.admin.menu})의 모델을
 * 재사용하지 않는다(app.admin.* 기능끼리는 서로 import하지 않는다). 이
 * 권한이 그 메뉴에 갖는 RBAC를 {@code authorities}({@code "10"}~{@code "40"}
 * → true/false)로 싣는다.
 */
@Getter
public class AdminAuthorityMenuNode implements Tree<AdminAuthorityMenuNode> {

    private final String id;
    private final String parentId;
    private final Map<String, AdminAuthorityMenuLocale> locale;
    private final String icon;
    private final String program;
    private final String use;
    private final String path;
    private final Integer level;
    private final int sort;
    private final Map<String, Boolean> authorities;

    @Setter
    private List<AdminAuthorityMenuNode> children;

    public AdminAuthorityMenuNode(String id, String parentId, Map<String, AdminAuthorityMenuLocale> locale,
            String icon, String program, String use, String path, Integer level, Integer sort,
            Map<String, Boolean> authorities) {
        this.id = id;
        this.parentId = parentId;
        this.locale = locale;
        this.icon = icon;
        this.program = program;
        this.use = use;
        this.path = path;
        this.level = level;
        this.sort = sort == null ? 0 : sort;
        this.authorities = authorities;
    }
}
