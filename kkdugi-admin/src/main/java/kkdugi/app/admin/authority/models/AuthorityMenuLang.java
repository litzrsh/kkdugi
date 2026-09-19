package kkdugi.app.admin.authority.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_menu_lang} 한 행 — 권한 화면의 메뉴 트리 라벨용 읽기 전용 프로젝션. */
@Getter
@Setter
public class AuthorityMenuLang extends BaseModel {

    private String menuId;
    private String langCode;
    private String label;
    private String remarks;
}
