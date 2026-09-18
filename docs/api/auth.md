# 인증 (로그인/로그아웃)

[api-define-admin.md](../api-define-admin.md)에는 별도 절로 정의돼 있지 않다
— 세션/로그인 시스템은 그 문서가 다루는 5개 절(공통코드/메시지/메뉴/권한/
사용자) 이후의 별개 단계로 구현됐다.

구현 위치가 컨트롤러가 아니라
[`AuthenticationProcessingFilter`](../../kkdugi-admin/src/main/java/kkdugi/core/security/authentication/filter/AuthenticationProcessingFilter.java)라는
점이 다른 API와 다르다 — Spring Security의
`AbstractAuthenticationProcessingFilter`를 직접 확장해 로그인 URL만 가로챈다.

## 1. 로그인 - POST /api/v1.0/admin/auth/login

인증 불필요(로그인 자체이므로).

```javascript
Request
{
  "username": "login id",
  "password": "password",
  "force": false
}
```

- `force`: 이미 활성 세션이 있으면 기본적으로 409로 거부된다. 사용자가
  "기존 세션을 종료하고 로그인할지" 확인한 뒤 동의하면 `true`로 같은 요청을
  재전송한다.

```javascript
Response
{
  "token": "eyJ...",
  "userId": "U2026091508110001",
  "username": "admin",
  "name": "관리자",
  "authorities": ["ROLE_SYS_ADMIN", ...]
}
```

|Response Status|설명|
|---|---|
|200|정상 — 세션 생성, JWT 발급|
|401|아이디/비밀번호 불일치(`auth.err.bad_credentials`), 또는 요청 바디가 JSON이 아니거나 username/password가 비어있음(`auth.err.malformed_request`)|
|409|이미 활성 세션이 있고 `force`가 false(`session.err.duplicate`)|

에러 응답 형식은 다른 API의 `RestfulExceptionAdvice` 경로를 타지 않고, 필터가
직접 `{ "code": "...", "message": "..." }` 형태로 쓴다 — 형태 자체는
[README](README.md#공통-사항)의 공통 에러 형식과 동일하다.

## 2. 로그아웃 - POST /api/v1.0/admin/auth/logout

`Authorization: Bearer <token>` 필요. Spring Security 표준 `LogoutFilter` +
`SessionLogoutHandler`로 처리되며, 별도 컨트롤러 없이 세션을 만료시키고 빈
바디로 204를 반환한다.

|Response Status|설명|
|---|---|
|204|정상 — 세션 만료 처리|

## 인증된 요청

로그인 이후의 모든 API 호출은 `Authorization: Bearer <token>` 헤더가 필요하다
(`BearerTokenAuthenticationFilter`가 검증). 토큰이 없거나 유효하지 않은 경우:

|Response Status|설명|
|---|---|
|401|토큰 없음/만료/위조 등 — 브라우저의 HTML 내비게이션 요청(`Accept: text/html`)은 `/login`으로 리다이렉트되고, 그 외(JSON API 호출)는 `{ "code": "...", "message": "..." }` 형식으로 응답|
