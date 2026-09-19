# ADR-0015: 메뉴 관리 시스템 구현 — 전체 트리 응답, parentId 불변, 중복 충돌 없음

> **패키지 구조 갱신 (2026-09-19):** 이 문서가 서술하는 `core.<기능>`/`app.admin.<기능>` 배치는 [ADR-0016](0016-app-and-admin-feature-split.md)으로 사용자용(`app.<기능>`)과 관리자용(`app.admin.<기능>`)을 분리하는 구조로 바뀌었다. 아래 내용은 당시 결정의 기록이다.

- 상태: Accepted
- 날짜: 2026-09-18

## 컨텍스트

`docs/archive/api-define-admin.md`(원래 `docs/api-define-admin.md`, 이번에
삭제되어 아카이브로 옮김) 3절(메뉴 관리)과 ERD의 `kkdugi_menu_base`/
`kkdugi_menu_lang`을 기반으로 메뉴 관리 API를 구현했다
([ADR-0012](0012-common-code-system.md)의 공통코드 구현과 패키지 규약·
`SerialConfig`/`SerialUtils` 재사용을 그대로 따른다). 스펙과 ERD가
알고리즘 수준까지 명시하지 않은 지점, 그리고 스펙 자체의 누락을 아래처럼
확정하고 기록한다.

## 결정

### 1. 조회 응답은 `Page<T>`가 아니라 전체 트리

공통코드는 부모 하나의 직접 자식만 페이징 조회하지만, 메뉴는 관리 화면에서
다루는 개수가 많지 않다고 보고 스펙 그대로 **전체 트리를 한 번에** 내려준다
(`GET /api/v1.0/admin/menu`, 요청 본문 없음). 그래서 `MenuContent`는
`kkdugi.core.models.Page`의 `T extends BaseModel` 제약을 받지 않고, 대신
`kkdugi.core.models.Tree<MenuContent>`를 구현해 `TreeUtils.convert(...)`로
트리 모양으로 변환한다 — `kkdugi.api.admin.session.MenuTreeItem`(로그인한
사용자 본인의 세션 메뉴 트리)이 이미 쓰던 것과 같은 유틸리티다. `MenuBase`/
`MenuLang`은 여전히 `BaseModel`을 상속하지만(DB 행 모델이니 감사 필드
규칙은 그대로), `MenuContent`는 페이징에 얽히지 않으므로 `BaseModel`을
상속할 필요가 없다.

### 2. `insert`/`update`/`delete` 항목에 `parentId`를 포함한다 (스펙 누락 보완)

아카이브 문서 3.2절의 요청 예시에는 `parentId` 필드가 아예 없다. 하지만
`parentId` 없이는 어느 메뉴 밑에 넣을지 표현할 방법이 없으므로, 공통코드의
`CodeContent.parentId`와 동일하게 `MenuContent`에 `parentId`를 추가했다.
스펙의 다른 어떤 필드도 이 문제를 해소하지 못한다는 점에서, 이건 스펙의
실수로 판단하고 채워 넣은 것이다.

### 3. `parentId`는 등록 후 수정할 수 없다

공통코드의 `code`/`parentId` 불변 규칙([ADR-0012](0012-common-code-system.md)
3번)과 같은 이유다 — 옮기려면 그 노드와 모든 하위 노드의 `menu_path`/
`menu_lvl`을 재계산해야 하는데, 그 캐스케이드 재계산은 아직 구현하지
않았다. 바꾸고 싶으면 삭제 후 재등록한다(삭제는 하위까지 캐스케이드된다).

### 4. `path`는 `menu_id` 세그먼트로 만든다 (공통코드는 `code_val` 세그먼트)

공통코드는 사람이 읽는 `code_val`을 이어붙여 `code_path`를 만들지만,
`kkdugi_menu_base`에는 그런 "값" 컬럼 자체가 없다(형제 사이에서 유일해야
하는 텍스트 필드가 없음 — 유니크 제약도 PK인 `menu_id`뿐). 그래서
`menu_path`는 `menu_id`를 세그먼트로 이어붙인다(`/M2026091517460001/
M2026091517460002`), 아카이브 문서의 예시 경로(`"/M2026091517570001/..."`)와
같은 모양이다.

### 5. 409 중복 충돌이 없다

공통코드의 409(`code.err.duplicate`)는 `(code_parent_id, code_val)` 유니크
제약 위반에서 온다. 메뉴에는 그런 값 컬럼/제약이 없으므로(4번 참고) 같은
상황이 구조적으로 발생할 수 없다 — `insert`에서 나올 수 있는 409는 "상위
메뉴를 찾을 수 없음"(`menu.err.not_found`)뿐이다.

### 6. `close`(탭 UI를 닫을 수 있는지 여부)를 노출한다 (스펙에 없는 필드)

`kkdugi_menu_base.close_yn` 컬럼은 스펙 작성 이후 추가됐다(Pragma 탭 UI
설계 과정에서 필요해진 것으로 보인다 — `kkdugi-design` 쪽 작업). 관리
API가 이 컬럼을 다루지 못하면 애초에 관리할 방법이 없어지므로, `use`와
같은 자리에 `close`로 노출했다. 세션 메뉴 트리(`SessionMenu`/
`MenuTreeItem`)는 아직 이 필드를 안 쓴다 — 그건 이 ADR의 범위가 아니다.

### 7. 예외 처리는 공통코드/메시지와 동일한 패턴

[ADR-0012](0012-common-code-system.md#addendum-2026-09-18-code-값-형식-검증-추가-에러-응답을-단일-메시지로-단순화)/
[ADR-0011](0011-api-define-admin-contract-and-record-models.md#addendum-2026-09-18-에러-응답을-단일-메시지로-단순화-위-137번-미해결-이슈-확정)의
"에러 응답을 단일 메시지로 단순화" 결정을 처음부터 적용했다 —
`MenuValidationException`/`MenuConflictException`은 메시지 코드 문자열
하나만 들고, 컨트롤러의 로컬 `@ExceptionHandler`가 `ExceptionMessage`
(`{code, message}`)로 응답한다. 구체적 맥락(`id`/`parentId` 등)은
`log.warn(...)`으로만 남긴다. 메시지 코드: `menu.err.malformed_request`,
`menu.err.locale_required`, `menu.err.not_found`, `menu.err.immutable`.

## 결과

- 패키지: `core.menu.{models,mapper}`, `app.admin.menu.{models,exceptions,service}`,
  `api.admin.menu`(평평).
- 스키마는 새로 만들지 않았다 — `V9__create_menu.sql`(`kkdugi_menu_base`/
  `kkdugi_menu_lang`/`kkdugi_auth_menu`)이 이미 있었다. `kkdugi_auth_menu`는
  이번 범위가 아니다(권한 관리 화면에서 다룬다).
- 엔드포인트: `GET /api/v1.0/admin/menu`(전체 트리 조회),
  `POST /api/v1.0/admin/menu/persist`(insert/update/delete). 자세한
  응답/에러 형태는 [docs/api/menu.md](../api/menu.md) 참고.

## 미해결 이슈

- `parentId` 변경(트리 재구성)은 구현하지 않았다 — 3번 참고. 공통코드와
  같은 미해결 이슈([ADR-0012](0012-common-code-system.md) "미해결 이슈")다.
- 권한/사용자 2개 화면은 아직 미구현. `SerialUtils`/`SerialConfig`와
  패키지 규약을 그대로 재사용해 이어서 구현한다.
- `kkdugi_auth_menu`(권한별 메뉴 RBAC 비트마스크)는 권한 관리 화면에서
  다룰 예정이며, 이번 메뉴 관리 API는 건드리지 않는다.
