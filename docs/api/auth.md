# 인증 (로그인/로그아웃)

[api-define-admin.md](../archive/api-define-admin.md)(2026-09-18 삭제, 원문은 아카이브로 이동)에는 별도 절로 정의돼 있지 않다
— 세션/로그인 시스템은 그 문서가 다루는 5개 절(공통코드/메시지/메뉴/권한/
사용자) 이후의 별개 단계로 구현됐다.

구현 위치가 컨트롤러가 아니라
[`AuthenticationProcessingFilter`](../../kkdugi-admin/src/main/java/kkdugi/core/security/authentication/filter/AuthenticationProcessingFilter.java)라는
점이 다른 API와 다르다 — Spring Security의
`AbstractAuthenticationProcessingFilter`를 직접 확장해 로그인 URL만 가로챈다.

## 흐름 요약

1. 로그인 화면(`/login`)의 vanilla JavaScript가 `x-www-form-urlencoded` form 데이터를 fetch로 전송한다.
2. 성공하면 서버가 세션을 만들고 토큰(JWT)을 **쿠키**로 내려준 뒤 `/`로
   리다이렉트한다.
3. `/`의 프런트(JS)가 쿠키에서 토큰을 꺼내 이후 모든 API 호출에
   `Authorization: Bearer <token>` 헤더로 붙인다.
4. 서버는 헤더와 쿠키 **둘 다** 인증 수단으로 받는다(헤더가 있으면 헤더 우선).

## 1. 로그인 - POST /api/v1.0/auth/login

인증 불필요(로그인 자체이므로). `Content-Type: application/x-www-form-urlencoded`
필수 — JSON 바디는 받지 않는다.

|Form 필드|설명|
|---|---|
|`username`|로그인 ID|
|`password`|비밀번호|
|`force`|`true`면 이미 활성 세션이 있어도 기존 세션을 끊고 로그인(기본 `false`)|

성공은 **쿠키 설정 + 리다이렉트**, 실패는 **401 + JSON 오류**다. 로그인 요청은 일반 API의 401 자동 로그인 이동 처리를 사용하지 않는다.

|Response|설명|
|---|---|
|302 → `/`|성공 — 세션 생성, 토큰 쿠키 설정|
|401 + JSON|아이디/비밀번호 불일치 또는 잘못된 요청. 오류 코드는 `code`, 번역된 문구는 `message` 필드로 반환한다. form이 아니거나 username/password가 비어 있으면 `auth.err.malformed_request`다.|
|401 + JSON `{ "code": "session.err.duplicate", "message": "..." }`|이미 활성 세션이 있고 force가 false. 현재 로그인 화면에서 확인 팝업을 열고 확인 시 같은 입력을 `force=true`로 재전송한다. 취소·Esc는 재시도하지 않고 비밀번호를 지운다.|

성공 리다이렉트 대상에는 컨텍스트 경로가 붙는다. fetch가 리다이렉트를 따라 쿠키를 받은 뒤, 프런트는 성공 목적지가 같은 origin의 `<contextPath>/`인지 확인하고 그때만 화면을 이동한다. 비밀번호는 URL·localStorage·sessionStorage에 저장하지 않는다. 요청 실패와 강제 재시도 실패는 현재 화면에 표시하며 자동 반복하지 않는다.

화면 구현과 접근성: [로그인 확인 팝업](../design/plan/14-session-auth-confirm-and-docs.md).

### 토큰 쿠키

|속성|값|
|---|---|
|이름|`kkdugi.security.token-cookie-name` (`SecurityConfigurationProperties.tokenCookieName`, 기본 `KKDUGI_TOKEN`). 프런트는 `/` 렌더 시 `window.KKDUGI.tokenCookie`로 이 이름을 받는다|
|Path|`<contextPath>/`|
|HttpOnly|**아님** — 프런트 JS가 읽어 Bearer 헤더로 옮겨야 하므로|
|SameSite|`Lax`|
|Secure|요청이 HTTPS일 때|
|만료|없음(브라우저 세션 쿠키) — 실제 만료는 서버 세션이 결정|

토큰은 `sess_id`만 담은 서명된 JWT이며 `exp`가 없다. 유효 여부는 항상
`kkdugi_session` 행의 존재/만료로 판정한다.

## 2. 로그아웃 - POST /api/v1.0/auth/logout

토큰(헤더 또는 쿠키)으로 인증된 요청이어야 세션이 끊긴다. Spring Security
표준 `LogoutFilter` + `SessionLogoutHandler`로 처리되며, 별도 컨트롤러 없이
빈 바디로 204를 반환하고 토큰 쿠키를 지운다(`Max-Age=0`).

|`kkdugi.security.allow-multiple`|끊는 세션|
|---|---|
|`false`(기본)|**사용자 ID 기준으로 그 사용자의 모든 세션**|
|`true`|**세션 ID 기준으로 이 요청의 세션 하나**(다른 기기의 세션은 유지)|

|Response Status|설명|
|---|---|
|204|정상 — 인증되지 않은 요청이어도 204(끊을 세션이 없을 뿐 쿠키는 지운다)|

## 인증된 요청

로그인 이후의 API 호출은 아래 둘 중 하나로 인증한다
(`BearerTokenAuthenticationFilter`가 검증).

- `Authorization: Bearer <token>` 헤더 — 프런트 기본 방식. 헤더가 있으면 쿠키는
  보지 않는다.
- 토큰 쿠키 — 헤더가 없을 때만.

토큰이 없거나 서명이 틀렸거나 세션이 만료/삭제된 경우:

|Response Status|설명|
|---|---|
|401|보호된 엔드포인트에서 — 브라우저의 HTML 내비게이션 요청(`Accept: text/html`)은 `/login`으로 리다이렉트되고, 그 외(JSON API 호출)는 `{ "code": "...", "message": "..." }` 형식으로 응답|

## 슬라이딩 세션

인증에 성공한 요청마다 세션 만료 시각(`exp_dtm`)을 지금부터
`kkdugi.security.session-timeout`(기본 1시간) 뒤로 미룬다. 토큰이 쿠키로도
전달돼 정적 리소스 요청까지 인증 요청이 되므로, 마지막 갱신 후
`kkdugi.security.session-refresh-interval`(기본 1분)이 지나지 않았으면 DB
쓰기를 건너뛴다 — 그래서 실제 유휴 만료는 `session-timeout`보다 최대
`session-refresh-interval`만큼 짧을 수 있다.

## 세션 attribute (서버 내부 API)

REST 엔드포인트가 아니라 `SessionUtils`의 정적 메서드다.

- `SessionUtils.getAttribute(key)` — 세션 사용자(`SessionUser.attributes`)의 값. 없거나 익명이면 `null`.
- `SessionUtils.setAttribute(key, value)` — 값을 바꾸고 `kkdugi_session.user_dtl`(jsonb)에도
  반영한다(`null`이면 키 제거). 스냅샷 전체가 아니라 해당 키만 `jsonb` 연산으로
  갱신하므로 같은 세션에서 다른 키를 동시에 갱신해도 덮어쓰지 않는다. 토큰으로
  인증된 요청에서만 호출할 수 있다(그 외 `IllegalStateException`). 값은 JSON을 거치므로
  다른 요청에서 읽으면 숫자는 Integer/Long/Double, 객체는 Map, 배열은 List로 돌아온다.
