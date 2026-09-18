package kkdugi.core.security.models;

public class SessionMenu {

    private String id;
    private String parentId;
    private String title;
    private String remarks;
    private String program;
    private String icon;
    private int sort;
    private int authority;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getProgram() {
        return program;
    }

    public void setProgram(String program) {
        this.program = program;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public int getSort() {
        return sort;
    }

    public void setSort(int sort) {
        this.sort = sort;
    }

    public int getAuthority() {
        return authority;
    }

    public void setAuthority(int authority) {
        this.authority = authority;
    }
}
