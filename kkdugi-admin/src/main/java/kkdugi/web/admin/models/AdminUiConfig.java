package kkdugi.web.admin.models;

import java.util.List;
import java.util.Map;

/**
 * {@code admin/index.html}이 {@code window.KKDUGI}로 병합하는 부트스트랩 설정
 * (kkdugi-design README "Spring 프로젝트로 연결" 참고). {@code mode}/
 * {@code locale}/{@code basePath}는 템플릿이 자체적으로 {@code #locale}/
 * {@code @{/}}에서 채우므로 여기서 중복하지 않는다. CSRF는 이 프로젝트가
 * 전면 disable했으므로 필드 자체를 두지 않는다.
 */
public record AdminUiConfig(List<LanguageOption> languages, List<StatusOption> statuses,
        Map<String, String> messages) {
}
