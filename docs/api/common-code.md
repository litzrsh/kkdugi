# 공통코드 관리

`api-define-admin.md`(2026-09-18 삭제, 원문은
[archive/api-define-admin.md](../archive/api-define-admin.md#1-공통코드관리) 1절 참고)에
대응하는 실제 구현. 스펙과 다른 점만 아래에 표시했다 — 나머지는 스펙과
동일하게 동작한다.

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

### 코드 값(`code`) 형식

`insert` 시 `code`는 **영문 대문자 + 숫자**만 허용하고, 특수기호는 `_`만
쓸 수 있으며 `_`로 시작하거나 끝날 수 없다(`^[A-Z0-9]+(_[A-Z0-9]+)*$`).
위반하면 400(`code.err.invalid_format`).

### 400 / 409 에러 응답 형식

`CodeAdminController`가 `CodeValidationException`/`CodeConflictException`을
직접 잡아 아래 형태로 응답한다 — 세션/인증 쪽 `RestfulExceptionAdvice`가
쓰는 것과 같은 바디 타입(`ExceptionMessage`)이지만, 전역 advice를 타지
않고 컨트롤러가 로컬로 처리한다:

```javascript
{
  "code": "code.err.duplicate",
  "message": "중복된 코드는 저장할 수 없습니다"
}
```

어떤 항목(`id`/`code`)이 문제였는지, 왜 그런지에 대한 구체적인 내용은
응답에 싣지 않고 서버 로그에만 남긴다. 가능한 `code` 값과 뜻:

|code|상황|상태|
|---|---|---|
|`code.err.malformed_request`|insert에 id가 채워져 있거나 update/delete에 id가 없음|400|
|`code.err.invalid_format`|`code` 값이 위 형식 규칙을 어김(insert 전용)|400|
|`code.err.locale_required`|`locale`이 비어 있거나 어떤 언어의 `name`이 비어 있음|400|
|`code.err.duplicate`|같은 부모 아래 `code` 값 중복(insert 전용)|409|
|`code.err.not_found`|상위 코드(insert) 또는 대상 코드(update/delete)를 찾을 수 없음|409|
|`code.err.immutable`|update에서 `code` 값 또는 `parentId`를 바꾸려 함|409|
