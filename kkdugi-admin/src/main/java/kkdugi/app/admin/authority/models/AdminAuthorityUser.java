package kkdugi.app.admin.authority.models;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 권한에 붙는 사용자 한 명과 적용 기간 — 요청/응답 공용. 날짜는 {@code yyyy-MM-dd}.
 * {@code name}/{@code image}(프로필 이미지)는 응답 전용이다: 서버가 사용자 테이블에서 채워 내려주고,
 * 요청(regist/save)에 실려 와도 무시한다(상세 응답을 그대로 save 요청으로 돌려보내도 동작한다).
 * 역직렬화 방식은 {@link AdminAuthorityPersistRequest} 참고.
 */
@Getter
@AllArgsConstructor
public class AdminAuthorityUser {

    private final String id;
    private final String name;
    private final String image;
    private final LocalDate applyStartDate;
    private final LocalDate applyEndDate;
}
