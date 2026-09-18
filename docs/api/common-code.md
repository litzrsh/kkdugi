# 공통코드 관리

`api-define-admin.md` [1절](../api-define-admin.md#1-공통코드관리)에 대응하는
실제 구현. 스펙과 다른 점만 아래에 표시했다 — 나머지는 스펙과 동일하게
동작한다.

구현: [`CodeAdminController`](../../kkdugi-admin/src/main/java/kkdugi/api/admin/code/CodeAdminController.java) /
[`CodeAdminService`](../../kkdugi-admin/src/main/java/kkdugi/app/admin/code/service/CodeAdminService.java)

## 1. 코드 조회 - POST /api/v1.0/admin/code

```javascript
Request
{
  "parentId"?: "parentId",
  "path"?: "code path",
  "code"?: "code value",
  "name"?: "code name",
  "use"?: "Y" | "N",
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
    {
      "id": "C2026091517460002",
      "parentId": "C2026091517460001",
      "code": "code",
      "locale": { "ko_KR": { "name": "..", "remarks": ".." }, "en_US": { "name": "..", "remarks": ".." } },
      "use": "Y",
      "extra1": "..", "extra2": "..", "extra3": "..", "extra4": "..", "extra5": "..",
      "path": "/SYS...",
      "level": 1,
      "sort": 0
    }
  ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|401|토큰 없음/만료|

`403`은 스펙에 정의돼 있지만, [README](README.md#공통-사항)에서 설명한 대로
권한 체크 자체가 아직 붙어있지 않아 현재는 발생하지 않는다.

## 2. 코드 저장 - POST /api/v1.0/admin/code/persist

**삭제 시 하위 코드도 모두 삭제**된다 (스펙과 동일).

```javascript
Request
{
  "insert": [ { /* CodeContent, id는 서버가 채번하므로 비워서 보낸다 */ } ],
  "update": [ { /* CodeContent */ } ],
  "delete": [ { /* CodeContent, id만 사용됨 */ } ]
}
```

응답 바디 없음(`void`).

|Response Status|설명|
|---|---|
|200|정상|
|400|Validation failure — 아래 형식|
|409|같은 부모 아래 `code` 값 중복 (insert 전용)|

`insert`/`update`/`delete`는 각각 null이면 빈 목록으로 취급된다
(`CodePersistRequest.insertOrEmpty()` 등).

### 400 / 409 에러 응답 형식

스펙에는 없는 구체적인 바디 형태 — `CodeAdminController`가
`CodeValidationException`/`CodeConflictException`을 직접 잡아 아래 형태로
응답한다(`RestfulExceptionAdvice`의 공통 형식과 다름):

```javascript
{
  "errors": [
    { "id": "C2026091517460002", "code": "code", "reason": "code.err.duplicate" }
  ]
}
```

`id`는 update/delete 대상 식별, `code`는 문제가 된 코드 값, `reason`은 메시지
코드다.
