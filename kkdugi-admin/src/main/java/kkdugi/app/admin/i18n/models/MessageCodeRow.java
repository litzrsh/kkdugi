package kkdugi.app.admin.i18n.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code searchDistinctCodes} 쿼리 한 행 — distinct {@code msg_cd} 하나와
 * (윈도우 함수로 같은 쿼리에서 얻은) 전체 개수({@link #getTotalSize()})를
 * 담는다. 쿼리 레벨 페이징을 위한 것으로, 도메인 모델로 취급하지 않는다.
 */
@Getter
@Setter
public class MessageCodeRow extends BaseModel {

    private String code;

    public MessageCodeRow() {
    }
}
