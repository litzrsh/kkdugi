# 메뉴 관리

`api-define-admin.md`(2026-09-18 삭제, 원문은
[archive/api-define-admin.md](../archive/api-define-admin.md#3-메뉴-관리) 3절 참고)에
대응하는 실제 구현. 스펙과 다른 점만 아래에 표시했다 — 나머지는 스펙과
동일하게 동작한다.

구현: [`MenuAdminController`](../../kkdugi-admin/src/main/java/kkdugi/api/admin/menu/MenuAdminController.java) /
[`MenuAdminService`](../../kkdugi-admin/src/main/java/kkdugi/app/admin/menu/service/MenuAdminService.java)

## 1. 메뉴 조회 - GET /api/v1.0/admin/menu

요청 본문 없음. 검색/페이징 파라미터가 없다 — 공통코드(부모 자식 단위
페이징)와 달리 메뉴는 전체 트리를 한 번에 내려준다(관리 화면에서 다루는
메뉴 개수가 많지 않다고 보고, 페이징 대신 `kkdugi.core.models.Tree` +
`TreeUtils.convert(...)`로 트리 전체를 구성한다 — 그래서 이 엔드포인트는
`Page<T>`를 쓰지 않는다).

```javascript
Response
[
  {
    "id": "M2026091517460001",
    "parentId": null,
    "locale": { "ko_KR": { "label": "..", "remarks": ".." }, "en_US": { "label": "..", "remarks": ".." } },
    "icon": "...",
    "program": "adcode",
    "use": "Y",
    "close": "Y",
    "path": "/M2026091517460001",
    "level": 0,
    "sort": 1,
    "children": [ { /* 같은 모양, 재귀 */ } ]
  }
]
```

- `parentId`는 스펙 예시에는 없지만 트리 구성에 필요해 노출했다 — root면
  `null`.
- `close`는 스펙에 없는 필드다. `kkdugi_menu_base.close_yn`(탭 UI에서
  닫을 수 있는지 여부) 컬럼이 스펙 작성 이후 추가됐고, 관리 API가 이
  컬럼을 다루지 못하면 관리할 방법이 아예 없어지므로 노출했다.
- `children`이 없는 리프 노드는 `null`이다(`TreeUtils.setChildren`이
  자식이 없으면 `null`을 설정 — 빈 배열이 아니다).

|Response Status|설명|
|---|---|
|200|정상|
|401|토큰 없음/만료|

`403`은 스펙에 정의돼 있지만, [README](README.md#공통-사항)에서 설명한 대로
권한 체크 자체가 아직 붙어있지 않아 현재는 발생하지 않는다.

## 2. 메뉴 저장 - POST /api/v1.0/admin/menu/persist

**삭제 시 하위 메뉴도 모두 삭제**된다 (스펙과 동일).

```javascript
Request
{
  "insert": [ { /* MenuContent, id는 서버가 채번하므로 비워서 보낸다 */ } ],
  "update": [ { /* MenuContent */ } ],
  "delete": [ { /* MenuContent, id만 사용됨 */ } ]
}
```

응답 바디 없음(`void`). `insert`/`update`/`delete`는 각각 null이면 빈
목록으로 취급된다(`MenuPersistRequest.insertOrEmpty()` 등).

- `parentId`는 스펙의 요청 예시에는 없지만 공통코드처럼 계층 구조를 만드는
  데 필수라서 `insert` 항목에 포함해야 한다. 한 번 등록하면 `parentId`는
  수정할 수 없다(공통코드의 `code`/`parentId` 불변 규칙과 같은 이유 — 바꾸려면
  하위 전체의 `path`/`level`을 재계산해야 하는데 아직 구현하지 않았다).
  바꾸고 싶으면 삭제 후 재등록한다.
- 공통코드와 달리 **409 중복 충돌이 없다** — 메뉴에는 `code_val` 같은,
  형제 사이에서 유일해야 하는 값 컬럼이 없다(`kkdugi_menu_base`에 PK인
  `menu_id` 외의 유니크 제약이 없음). 그래서 `insert` 시 발생할 수 있는
  409는 "상위 메뉴를 찾을 수 없음"뿐이다.

|Response Status|설명|
|---|---|
|200|정상|
|400|Validation failure — 아래 형식|
|409|상위 메뉴를 찾을 수 없음(insert)/대상 메뉴를 찾을 수 없음(update·delete)/`parentId` 변경 시도(update)|

### 400 / 409 에러 응답 형식

[common-code.md](common-code.md#400--409-에러-응답-형식)와 완전히 같은
패턴(`ExceptionMessage` — `{code, message}`, 컨트롤러 로컬
`@ExceptionHandler`)이다:

```javascript
{
  "code": "menu.err.not_found",
  "message": "대상 메뉴를 찾을 수 없습니다"
}
```

|code|상황|상태|
|---|---|---|
|`menu.err.malformed_request`|insert에 id가 채워져 있거나 update/delete에 id가 없음|400|
|`menu.err.locale_required`|`locale`이 비어 있거나 어떤 언어의 `label`이 비어 있음|400|
|`menu.err.not_found`|상위 메뉴(insert) 또는 대상 메뉴(update/delete)를 찾을 수 없음|409|
|`menu.err.immutable`|update에서 `parentId`를 바꾸려 함|409|
