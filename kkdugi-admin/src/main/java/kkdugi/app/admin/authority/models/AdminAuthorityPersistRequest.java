package kkdugi.app.admin.authority.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * regist/save 요청 본문. {@code users}/{@code menus}가 {@code null}이면 그
 * 매핑은 건드리지 않고, 빈 배열이면 전부 비운다. save의 본문 {@code id}는
 * 경로의 id가 우선이라 무시한다.
 *
 * <p>Jackson 3는 이 클래스(와 {@link AdminAuthorityUser}, {@link AdminAuthorityMenu})처럼 Lombok
 * 전체 필드 생성자 + final 필드 클래스를 암묵적 properties-creator 감지로 역직렬화한다. 이는
 * 컴파일러 {@code -parameters} 플래그에 의존한다({@code AdminMenuPersistRequest}와 동일).
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminAuthorityPersistRequest {

    private final String role;
    private final String type;
    private final String name;
    private final String remarks;
    private final String use;
    private final List<AdminAuthorityUser> users;
    private final List<AdminAuthorityMenu> menus;
}
