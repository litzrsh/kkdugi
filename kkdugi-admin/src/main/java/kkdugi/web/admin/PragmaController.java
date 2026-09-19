package kkdugi.web.admin;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateInputException;

import kkdugi.core.enums.Rbac;
import kkdugi.core.security.annotation.RequireAuthority;
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
 * <p>SecurityChecker가 인증, X-Menu-Id, 세션 메뉴 소속과 경로 일치를 먼저
 * 검사한다. 미인증은 401, 메뉴 접근 거부는 403이다. 아래 컨트롤러는 프로그램
 * 경로 형식과 템플릿 존재를 검사하며 잘못되었거나 미구현된 템플릿은 404로 응답한다.</p>
 *
 * <p>{@code program}은 {@code templates/pragma/} 기준 상대 경로이고 폴더 구분자
 * {@code /}로 하위 폴더를 가리킬 수 있다 — 예를 들어 {@code admin/code}는
 * {@code templates/pragma/admin/code.vue}다. 이 값은 메뉴 API로 관리자가 저장하는
 * 값이라, 그대로 리졸버에 넘기면 {@code ../}로 {@code templates/pragma/} 밖의
 * {@code .vue}까지 읽을 수 있다. 그래서 세그먼트가 영문/숫자/{@code _}/{@code -}로만
 * 이루어진 경우({@link #PROGRAM_PATTERN})만 처리하고, 그 외(점, 역슬래시, 빈 세그먼트,
 * 절대 경로 등)는 파일이 없는 것과 똑같이 404로 응답한다.</p>
 *
 * <p>메뉴에 매핑된 {@code program} 파일이 아직 {@code templates/pragma/}에
 * 없는 경우({@link TemplateInputException})도 404로 처리한다 — 화면
 * 산출물은 이 저장소가 만드는 대상이 아니라서(CLAUDE.md), 파일이 없는
 * 상태 자체는 정상적인 과도기다.</p>
 */
@RestController
public class PragmaController {

    private static final Pattern PROGRAM_PATTERN = Pattern.compile("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*");

    private final TemplateEngine pragmaTemplateEngine;

    public PragmaController(@Qualifier("pragmaTemplateEngine") TemplateEngine pragmaTemplateEngine) {
        this.pragmaTemplateEngine = pragmaTemplateEngine;
    }

    @RequireAuthority
    @GetMapping(value = "/pragma/{menuId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> pragma(@PathVariable String menuId, Locale locale) {
        SessionMenu menu = SessionUtils.getMenu(menuId);
        if (menu == null || !StringUtils.hasText(menu.getProgram())
                || !PROGRAM_PATTERN.matcher(menu.getProgram()).matches()) {
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
