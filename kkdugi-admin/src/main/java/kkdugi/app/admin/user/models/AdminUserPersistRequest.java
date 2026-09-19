package kkdugi.app.admin.user.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * regist/save 요청 본문. {@code id}·{@code lastLoginAt} 같은 서버 관리 필드는 실려 와도 무시한다
 * ({@code ignoreUnknown}) — 상세 응답을 그대로 save 요청으로 돌려보내도 된다. save에서는 본문 id가 아니라
 * 경로 id를 쓰고, {@code username}은 생략하거나 기존 값과 같아야 한다.
 *
 * <p>Jackson 3는 Lombok 전체 필드 생성자 + final 필드 클래스를 컴파일러 {@code -parameters}에 의존해
 * 역직렬화한다({@code AdminAuthorityPersistRequest}와 동일).
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserPersistRequest {

    private final String username;
    private final String name;
    private final String remarks;
    private final String image;
    private final String email;
    private final String status;
}
