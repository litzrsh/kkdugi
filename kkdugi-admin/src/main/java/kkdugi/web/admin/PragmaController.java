package kkdugi.web.admin;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateInputException;

import kkdugi.core.enums.Rbac;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.util.SessionUtils;

/**
 * 메뉴 ID 하나를 화면(Vue SFC 조각) 하나로 서빙한다 — 화면의
 * {@code vue3-sfc-loader}가 {@code fetch(url).then(r=>r.text())}로 원문을
 * 그대로 받아 클라이언트에서 컴파일하는 구조라, 이 컨트롤러는 JSON이 아니라
 * 렌더링된 SFC 텍스트를 그대로 응답 바디로 돌려준다({@code @RestController}가
 * 필요한 이유 — 뷰 이름 리졸빙이 아니라 문자열을 그대로 바디에 쓴다).
 * 셸 페이지 렌더링(layout/index)은 {@link IndexController}가 대신 맡는다.
 *
 * <p>{@code menuId}는 세션에 이미 로드된 사용자 본인의 메뉴 목록
 * ({@link SessionUtils#getMenu(String)})에서만 찾는다 — 목록 자체가 이미
 * RBAC로 걸러져 있으므로(권한이 0인 메뉴는 애초에 세션 메뉴 목록에 없다),
 * 여기서 찾지 못하는 경우는 "존재하지 않음"과 "권한 없음"을 구분하지 않고
 * 전부 404로 통일한다 — 로그인 아이디 존재 여부를 노출하지 않는
 * {@code KkdugiUserDetailsService.ERR_NOT_FOUND}와 같은 이유다.</p>
 *
 * <p>메뉴에 매핑된 {@code program} 파일이 아직 {@code templates/pragma/}에
 * 없는 경우({@link TemplateInputException})도 404로 처리한다 — 화면
 * 산출물은 이 저장소가 만드는 대상이 아니라서(CLAUDE.md), 파일이 없는
 * 상태 자체는 정상적인 과도기다.</p>
 */
@RestController
public class PragmaController {

    private final TemplateEngine pragmaTemplateEngine;

    public PragmaController(@Qualifier("pragmaTemplateEngine") TemplateEngine pragmaTemplateEngine) {
        this.pragmaTemplateEngine = pragmaTemplateEngine;
    }

    @GetMapping(value = "/pragma/{menuId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> pragma(@PathVariable String menuId, Locale locale) {
        SessionMenu menu = SessionUtils.getMenu(menuId);
        if (menu == null || !StringUtils.hasText(menu.getProgram())) {
            return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
        }

        Context context = new Context(locale);
        // Rbac.toMap()의 키는 kkdugi.core.enums.Rbac의 코드 값("10"/"20"/
        // "30"/"40")이다 — READ/WRTE/DELT/EXEC 같은 이름이 아니다.
        context.setVariable("authorities", Rbac.toMap(menu.getAuthority()));

        try {
            String rendered = pragmaTemplateEngine.process(menu.getProgram(), context);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rendered);
        } catch (TemplateInputException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
