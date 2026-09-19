package kkdugi.app.menu.models;

import java.util.List;

import kkdugi.core.models.Tree;

/**
 * {@code GET /api/v1.0/menu} 응답 전용 모델. 세션에 저장된
 * {@link kkdugi.core.security.models.SessionMenu}(flat list, RBAC 체크용
 * {@code program}/{@code authority} 포함)를 그대로 내려주지 않고, 화면
 * 내비게이션에 필요한 필드만 골라 트리 모양으로 변환한 결과를 담는다.
 */
public class Menu implements Tree<Menu> {

    private final String id;
    private final String parentId;
    private final String title;
    private final String remarks;
    private final String icon;
    private final int sort;
    private List<Menu> children;
    private boolean openable;

    public Menu(String id, String parentId, String title, String remarks, String icon, int sort, boolean openable) {
        this.id = id;
        this.parentId = parentId;
        this.title = title;
        this.remarks = remarks;
        this.icon = icon;
        this.sort = sort;
        this.openable = openable;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getParentId() {
        return parentId;
    }

    public String getTitle() {
        return title;
    }

    public String getRemarks() {
        return remarks;
    }

    public String getIcon() {
        return icon;
    }

    @Override
    public int getSort() {
        return sort;
    }

    public List<Menu> getChildren() {
        return children;
    }

    @Override
    public void setChildren(List<Menu> children) {
        this.children = children;
    }

    public boolean isOpenable() {
        return openable;
    }

    public void setOpenable(boolean openable) {
        this.openable = openable;
    }
}
