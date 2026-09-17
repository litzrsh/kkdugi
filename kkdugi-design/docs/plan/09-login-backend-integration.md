# 실제 로그인 연결 1차 · 2026-09-17

## 재확인한 변경

`kkdugi-admin`에 PragmaController(`/admin`), CookieLocaleResolver/LocaleChangeInterceptor, adminUiConfig, 관리자 static/template과 UI MessageSource가 추가돼 있다. 로그인 필터는 기존 JSON `{username,password,force}` 계약이다. 필수 비밀번호 변경 API/challenge와 API 경로별 인가는 아직 없다.

## 실제 적용

- GET `/login` → LoginController → `admin/login.html`. Vue 없는 HTML/CSS/vanilla JS module.
- JSON POST `/api/v1.0/admin/auth/login` 연결.
- 409 `session.err.duplicate`이면 같은 HTML 안에서 중복 확인 영역을 표시한다. 동의하면 입력을 메모리에서 재사용해 force=true로 재요청한다. 취소하면 비밀번호를 지우고 폼으로 복귀한다. 별도 페이지/스토리지에 비밀번호를 전달하지 않는다.
- 성공 토큰은 `sessionStorage`의 `kkdugi.admin.token`에 보관하고 `/admin`으로 이동한다. 토큰 저장이 불가능하면 생성된 세션을 로그아웃 API로 되돌리는 요청을 시도하고 오류를 표시한다.
- 관리자 API adapter가 매 요청에 Bearer 헤더를 넣는다. 401이면 토큰을 지우고 `/login`으로 이동한다.
- `/admin` shell은 기존 서버 정책대로 공개 HTML이다. 토큰 없는 브라우저는 JS에서 `/login`으로 이동한다. **이 화면 이동은 서버의 접근 제어를 대체하지 않는다. 현재 anyRequest().permitAll()은 그대로이며 운영 인증 보호 완료를 의미하지 않는다.**
- 상단 로그아웃 버튼은 변경사항 확인 후 서버 logout API를 호출하고 토큰을 비운다.
- 로그인 메시지 번들을 기존 MessageSource basename에 추가했다. 실제 LocaleResolver 및 contextPath를 따른다.

## 파일

- `web/admin/LoginController.java`
- `templates/admin/login.html`
- `static/auth/login.css`, `login-live.mjs`, `session.mjs`
- `static/admin/api.mjs`, `bootstrap.mjs`, `App.vue`
- `core/i18n/config/I18nMessageSourceConfig.java`
- `messages/login-ui_*.properties`, `admin-ui_*.properties`
- `src/test/java/kkdugi/web/admin/LoginControllerTest.java`
- `src/test/js/auth-api.test.mjs`

## 검증

- `mvn test`: 76 tests, 0 failures, 0 errors, 0 skipped. 새 로그인 렌더링/영문/contextPath 3개 포함.
- `node --test src/test/js/auth-api.test.mjs`: 3개 통과. Bearer 전달, 401 토큰 폐기/contextPath 복귀, 제거된 토큰 미전송.
- 실제 Spring `127.0.0.1:8081/login` 브라우저에서 렌더링, 자격 증명 오류 응답 안내를 확인.
- 정상/중복/강제 교체/로그아웃 API는 기존 서버 통합 테스트로 검증. 실제 사용자 계정으로 브라우저 로그인 성공을 확인한 것은 아니다.

## 남은 서버 작업

1. NEWP/EXPR 사용자의 로그인 완료를 막고 비밀번호 변경 후 재개하는 계약. 이번 연결에서 임의 endpoint나 비밀번호 변경 성공을 만들어내지 않았다. 디자인의 login-password 화면은 서버 계약 대기다.
2. API 보호 경로, 비정상 사용자 상태 검증, 세션 동시성 보완.
3. challenge 기반 재개로 계약을 확장하면 메모리의 기존 비밀번호 재전송 흐름을 교체한다.

실행 명령: `mvn spring-boot:run`. 검수 실행은 기존 서비스 충돌을 피하도록 `--server.port=8081 --server.address=127.0.0.1`을 사용했다. 브랜치 전환/커밋은 하지 않았다.
