# 다국어 메시지 관리

`api-define-admin.md`(2026-09-18 삭제, 원문은
[archive/api-define-admin.md](../archive/api-define-admin.md#2-메시지-관리) 2절 참고)에
대응하는 실제 구현. 스펙과 거의 동일하며, 에러 응답의 구체적 형태만 아래에
추가했다.

구현: [`AdminMessageController`](../../kkdugi-admin/src/main/java/kkdugi/api/admin/AdminMessageController.java) /
[`AdminMessageService`](../../kkdugi-admin/src/main/java/kkdugi/app/admin/i18n/service/AdminMessageService.java)

## 1. 메시지 조회 - POST /api/v1.0/admin/i18n

```javascript
Request
{
  "code"?: "message code",
  "message"?: "message value",
  "page": 1,
  "pageSize": 200
}
```

```javascript
Response
{
  "page": 1,
  "pageSize": 200,
  "totalItems": 100,
  "totalPages": 1,
  "contents": [
    { "code": "system.msg.test", "locale": { "ko_KR": "값", "en_US": "value" } }
  ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|401|토큰 없음/만료|

`403`은 스펙에 정의돼 있지만, [README](README.md#공통-사항)에서 설명한 대로
권한 체크가 아직 없어 현재는 발생하지 않는다.

## 2. 메시지 저장 - POST /api/v1.0/admin/i18n/persist

```javascript
Request
{
  "insert": [ { "code": "system.msg.test1", "locale": { "ko_KR": "값" } } ],
  "update": [ { "code": "system.msg.test2", "locale": { "ko_KR": "값" } } ],
  "delete": [ { "code": "system.msg.test3", "locale": {} } ]
}
```

응답 바디 없음(`void`). `insert`/`update`/`delete`는 각각 null이면 빈 목록으로
취급된다.

|Response Status|설명|
|---|---|
|200|정상|
|400|Validation failure — 아래 형식|
|409|`code` 값 중복 (insert 전용)|

### 400 / 409 에러 응답 형식

`AdminMessageController`가 `AdminMessageValidationException`/
`AdminMessageConflictException`을 직접 잡아 아래 형태로 응답한다 — 세션/인증
쪽 `RestfulExceptionAdvice`가 쓰는 것과 같은 바디 타입(`ExceptionMessage`)
이지만, 전역 advice를 타지 않고 컨트롤러가 로컬로 처리한다
([common-code.md](common-code.md#400--409-에러-응답-형식)와 동일한 패턴):

```javascript
{
  "code": "message.err.duplicate",
  "message": "중복된 메시지는 저장할 수 없습니다"
}
```

어떤 `code`/`lang`이 문제였는지에 대한 구체적인 내용은 응답에 싣지 않고
서버 로그에만 남긴다. 가능한 `code` 값과 뜻:

|code|상황|상태|
|---|---|---|
|`message.err.invalid_format`|메시지 `code` 값이 `MessageCode` 형식(`a.b.c` 3단 세그먼트)을 어김|400|
|`message.err.locale_required`|insert/update에서 `locale`이 비어 있거나 값이 빈 언어가 있음|400|
|`message.err.duplicate`|같은 `(code, lang)` 중복(insert 전용)|409|
|`message.err.not_found`|update/delete 대상 `code`(또는 update 대상 `lang`)를 찾을 수 없음|409|
