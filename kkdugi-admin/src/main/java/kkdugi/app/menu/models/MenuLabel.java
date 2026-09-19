package kkdugi.app.menu.models;

import kkdugi.core.models.BaseModel;
import lombok.Getter;
import lombok.Setter;

/** 사용자 내비게이션용 번역. 관리자 메뉴 모델과 별도로 관리한다. */
@Getter
@Setter
public class MenuLabel extends BaseModel {
    private String menuId;
    private String title;
    private String remarks;
}
