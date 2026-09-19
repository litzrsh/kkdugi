package kkdugi.web.admin.models;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * {@code layout/index.html}이 {@code window.KKDUGI}로 병합하는 부트스트랩 설정
 * (kkdugi-design README "Spring 프로젝트로 연결" 참고). {@code mode}/
 * {@code locale}/{@code basePath}는 템플릿이 자체적으로 {@code #locale}/
 * {@code @{/}}에서 채우므로 여기서 중복하지 않는다. CSRF는 이 프로젝트가
 * 전면 disable했으므로 필드 자체를 두지 않는다.
 */
@Getter
@AllArgsConstructor
public class AdminUiConfig {

    private final List<LanguageOption> languages;
    private final List<StatusOption> statuses;
    private final Map<String, String> messages;

    /** 로그인 시 발급된 토큰 쿠키 이름 — 프론트가 이 쿠키에서 토큰을 꺼내 Bearer 헤더로 보낸다. */
    private final String tokenCookie;
}
