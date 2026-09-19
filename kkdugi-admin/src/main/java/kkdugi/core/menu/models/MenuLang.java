package kkdugi.core.menu.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MenuLang extends BaseModel {

    private String menuId;
    private String langCode;
    private String label;
    private String remarks;

    public MenuLang() {
    }

    public MenuLang(String menuId, String langCode, String label, String remarks) {
        this.menuId = menuId;
        this.langCode = langCode;
        this.label = label;
        this.remarks = remarks;
    }
}
