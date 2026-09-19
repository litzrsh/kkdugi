package kkdugi.app.admin.i18n.models;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kkdugi.core.models.BaseModel;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * {@link kkdugi.core.models.Page}가 {@code T extends BaseModel}을 요구해서
 * (쿼리 레벨 페이징 — 각 행에 실린 {@code totalSize} 윈도우 함수 값을 그대로
 * 씀) BaseModel을 상속한다. {@code rownum`/`createdAt`/`creatorId`/
 * `updatedAt`/`updaterId`는 이 API 응답에 노출 대상이 아니므로
 * {@link JsonIgnoreProperties}로 숨긴다({@code totalSize}는 BaseModel
 * 자체에 이미 {@code @JsonIgnore}가 붙어 있어 별도 처리가 필요 없다).
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties({ "rownum", "createdAt", "creatorId", "updatedAt", "updaterId" })
public class MessageContent extends BaseModel {

    private final String code;
    private final Map<String, String> locale;
}
