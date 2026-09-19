package kkdugi.app.admin.menu.models;

import java.util.List;
import java.util.Map;

import kkdugi.core.models.Tree;

/**
 * 메뉴 관리 화면의 조회/저장 API 공통 콘텐츠 모델. 조회 응답은 전체
 * 트리(부모가 없으면 root, {@link #getChildren()}로 중첩)이고, 저장 요청은
 * {@code insert}/{@code update}/{@code delete} 각각 flat 배열이라
 * {@code children}은 요청에서 쓰이지 않는다. {@link Tree}를 구현해
 * {@code TreeUtils.convert(...)}로 트리 변환한다(공통코드처럼
 * {@code Page<T>}로 감싸는 페이징 목록이 아니다 — 메뉴는 한 번에 전체
 * 트리를 내려준다).
 */
public class MenuContent implements Tree<MenuContent> {

    private final String id;
    private final String parentId;
    private final Map<String, MenuLocale> locale;
    private final String icon;
    private final String program;
    private final String use;
    private final String close;
    private final String path;
    private final Integer level;
    private final int sort;
    private List<MenuContent> children;

    public MenuContent(String id, String parentId, Map<String, MenuLocale> locale, String icon,
            String program, String use, String close, String path, Integer level, Integer sort) {
        this.id = id;
        this.parentId = parentId;
        this.locale = locale;
        this.icon = icon;
        this.program = program;
        this.use = use;
        this.close = close;
        this.path = path;
        this.level = level;
        this.sort = sort == null ? 0 : sort;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getParentId() {
        return parentId;
    }

    public Map<String, MenuLocale> getLocale() {
        return locale;
    }

    public String getIcon() {
        return icon;
    }

    public String getProgram() {
        return program;
    }

    public String getUse() {
        return use;
    }

    public String getClose() {
        return close;
    }

    public String getPath() {
        return path;
    }

    public Integer getLevel() {
        return level;
    }

    @Override
    public int getSort() {
        return sort;
    }

    public List<MenuContent> getChildren() {
        return children;
    }

    @Override
    public void setChildren(List<MenuContent> children) {
        this.children = children;
    }
}
