# 다국어 메시지 관리

`api-define-admin.md` [2절](../api-define-admin.md#2-메시지-관리)에 대응하는
실제 구현. 스펙과 거의 동일하며, 에러 응답의 구체적 형태만 아래에 추가했다.

구현: [`MessageAdminController`](../../kkdugi-admin/src/main/java/kkdugi/api/admin/i18n/MessageAdminController.java) /
[`MessageAdminService`](../../kkdugi-admin/src/main/java/kkdugi/app/admin/i18n/service/MessageAdminService.java)

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

```javascript
{
  "errors": [
    { "code": "system.msg.test1", "reason": "message.err.duplicate" }
  ]
}
```
