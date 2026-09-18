package kkdugi.web.admin;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import kkdugi.core.enums.UserStatus;
import kkdugi.web.admin.config.AdminUiProperties;
import kkdugi.web.admin.models.AdminUiConfig;
import kkdugi.web.admin.models.StatusOption;

/**
 * kkdugi-design의 admin 셸(admin/index.html)을 그대로 옮겨와 렌더링한다.
 * README "Spring 프로젝트로 연결" 계약대로 {@code adminUiConfig} 모델 하나만
 * 제공하고, 실제 데이터 조회/쓰기는 화면의 Vue가 기존 code/i18n/menu/
 * authority/user REST API를 직접 호출해서 처리한다 — 이 컨트롤러는 그 API들의
 * 대체가 아니다.
 *
 * <p>원래 이 클래스(구 {@code PragmaController})가 셸 렌더링과 메뉴 단위 Vue
 * 조각 서빙을 함께 맡았으나, 후자가 {@code @RestController}(문자열 리턴값이
 * 뷰 이름이 아니라 응답 바디가 됨)로 바뀌면서 이 클래스의 뷰 이름 리턴
 * 방식과 공존할 수 없어 분리했다. 조각 서빙은
 * {@link kkdugi.web.admin.PragmaController} 참고.</p>
 *
 * <p>이 경로는 permitAll로 공개돼 있다. 셸 자체에는 민감한 데이터가 없고,
 * 실제 보호는 화면이 호출하는 CRUD API(Bearer 헤더 필요)에서 이뤄진다 —
 * 일반 브라우저 페이지 이동에는 JS가 지정하는 Authorization 헤더가 자동으로
 * 붙지 않으므로, 셸 자체를 서버에서 인증 요구하도록 만들면 별도의 쿠키
 * 기반 인증 체계가 필요해진다(로그인 화면/challenge 흐름은 별도 범위).</p>
 */
@Controller
public class IndexController {

    private static final String ADMIN_UI_BASENAME = "messages/admin-ui_ko_KR.properties";
    private static final Set<String> MESSAGE_KEYS = loadMessageKeys();

    private final MessageSource messageSource;
    private final AdminUiProperties adminUiProperties;

    public IndexController(MessageSource messageSource, AdminUiProperties adminUiProperties) {
        this.messageSource = messageSource;
        this.adminUiProperties = adminUiProperties;
    }

    /**
     * 루트에는 별도 화면이 없다 — {@code /admin}으로 보내면
     * bootstrap.mjs가 토큰 유무를 보고 {@code /login}으로 다시 보낼지
     * 판단한다(클라이언트 쪽 결정, 서버 인가를 대체하지 않음). 매핑이
     * 없으면 Spring Boot의 기본 에러 핸들러가 그냥 JSON 404를 보여준다.
     */
    @GetMapping("/")
    public String root() {
        return "redirect:/admin";
    }

    @GetMapping("/admin")
    public String index(Model model, Locale locale) {
        model.addAttribute("adminUiConfig", buildConfig(locale));
        return "admin/index";
    }

    private AdminUiConfig buildConfig(Locale locale) {
        Map<String, String> messages = MESSAGE_KEYS.stream()
                .collect(Collectors.toMap(key -> key, key -> messageSource.getMessage(key, null, locale)));
        return new AdminUiConfig(adminUiProperties.getLanguages(), statusOptions(), messages);
    }

    private List<StatusOption> statusOptions() {
        return Arrays.stream(UserStatus.values())
                .map(status -> new StatusOption(status.getCode(), status.getLabel()))
                .toList();
    }

    /** admin.ui.* 키 목록을 하드코딩하지 않고, kkdugi-design이 정한 ko_KR
     * 번들 자체를 키 매니페스트로 삼는다 — 번들이 바뀌면 자동으로 따라간다. */
    private static Set<String> loadMessageKeys() {
        try {
            return PropertiesLoaderUtils.loadAllProperties(ADMIN_UI_BASENAME).stringPropertyNames();
        } catch (IOException e) {
            throw new IllegalStateException(ADMIN_UI_BASENAME + "를 읽지 못했습니다", e);
        }
    }
}
