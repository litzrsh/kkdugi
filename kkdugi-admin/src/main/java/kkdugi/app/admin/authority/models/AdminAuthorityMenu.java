package kkdugi.app.admin.authority.models;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 요청의 메뉴 부여 항목 — {@code authorities}의 키는 {@code Rbac} 숫자 코드({@code "10"}~{@code "40"}).
 * 역직렬화 방식은 {@link AdminAuthorityPersistRequest} 참고.
 */
@Getter
@AllArgsConstructor
public class AdminAuthorityMenu {

    private final String id;
    private final Map<String, Boolean> authorities;
}
