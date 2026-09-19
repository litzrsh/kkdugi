package kkdugi.app.admin.authority.models;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 권한에 붙는 사용자 한 명과 적용 기간 — 요청/응답 공용. 날짜는 {@code yyyy-MM-dd}.
 * 역직렬화 방식은 {@link AdminAuthorityPersistRequest} 참고.
 */
@Getter
@AllArgsConstructor
public class AdminAuthorityUser {

    private final String id;
    private final LocalDate applyStartDate;
    private final LocalDate applyEndDate;
}
