package kkdugi.app.admin.authority.models;

import lombok.Getter;
import lombok.Setter;

/**
 * 후보 사용자 조회 요청 본문 {@code {"query": "..."}}. 필드가 하나뿐인 all-args
 * 생성자는 Jackson이 속성 생성자인지 위임 생성자인지 모호해하므로 이 클래스만
 * 기본 생성자 + setter로 둔다.
 */
@Getter
@Setter
public class AdminAuthorityCandidateQuery {

    private String query;
}
