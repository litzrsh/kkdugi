package kkdugi.app.admin.authority.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import kkdugi.core.models.BaseModel;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 권한 목록/상세 응답 콘텐츠. {@link kkdugi.core.models.Page}가
 * {@code T extends BaseModel}을 요구해서 BaseModel을 상속하고, 응답에
 * 노출하지 않을 감사 필드는 {@link JsonIgnoreProperties}로 숨긴다.
 * {@code users}는 상세/등록/저장 응답에만 채워지고 목록에서는 {@code null}이라
 * JSON에서 빠진다.
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties({ "rownum", "createdAt", "creatorId", "updatedAt", "updaterId" })
public class AdminAuthority extends BaseModel {

    private final String id;
    private final String role;
    private final String type;
    private final String name;
    private final String remarks;
    private final String use;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final List<AdminAuthorityUser> users;
}
