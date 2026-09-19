package kkdugi.app.code.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/**
 * 사용자에게 보이는 공통코드 한 건 — 요청 언어로 해석된 이름/설명을 담는다.
 * {@link kkdugi.core.models.Page}가 {@code T extends BaseModel}을 요구해서
 * (쿼리 레벨 페이징) BaseModel을 상속하고, 상속 필드는 응답에서 숨긴다.
 * 관리자용 {@code kkdugi.app.admin.code}의 모델과는 의도적으로 공유하지 않는다.
 */
@Getter
@Setter
@JsonIgnoreProperties({ "rownum", "createdAt", "creatorId", "updatedAt", "updaterId" })
public class Code extends BaseModel {

    private String id;
    private String parentId;
    private String code;
    private String name;
    private String remarks;
    private String extra1;
    private String extra2;
    private String extra3;
    private String extra4;
    private String extra5;
    private String path;
    private Integer level;
    private Integer sort;

    public Code() {
    }
}
