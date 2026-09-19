package kkdugi.app.admin.user.models;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kkdugi.core.models.BaseModel;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 사용자 목록/상세/등록/저장 응답 콘텐츠. {@link kkdugi.core.models.Page}가 {@code T extends BaseModel}을
 * 요구해서 BaseModel을 상속하고 감사 필드는 숨긴다. 비밀번호·CI/DI·설정 데이터는 아예 필드가 없다.
 * {@code status}/{@code passwordStatus}는 코드 문자열이고 일시는 {@code yyyy-MM-dd HH:mm:ss}다.
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties({ "rownum", "createdAt", "creatorId", "updatedAt", "updaterId" })
public class AdminUser extends BaseModel {

    private static final String DATE_TIME = "yyyy-MM-dd HH:mm:ss";

    private final String id;
    private final String username;
    private final String name;
    private final String remarks;
    private final String image;
    private final String email;
    private final String status;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME)
    private final LocalDateTime lastLoginAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME)
    private final LocalDateTime lastChangePasswordAt;

    private final String passwordStatus;
}
