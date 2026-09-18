# 세션 메뉴 & 화면(Pragma) 조각

`api-define-admin.md`에는 없는 API다. 그 문서의 [3절 메뉴
관리](../api-define-admin.md#3-메뉴-관리)는 메뉴 자체를 CRUD하는 SYS_ADMIN
전용 관리 API이고, 여기서 다루는 건 **로그인한 사용자 본인**이 볼 수 있는
메뉴를 내려받아 화면을 구성하는 별개의 흐름이다.

## 동작 개요

1. 로그인하면 `KkdugiUserDetailsService`가 사용자의 세션에 볼 수 있는 메뉴
   전체를 `SessionMenu`(flat list)로 실어둔다. `SYS_ADMIN` 역할은 메뉴 권한
   비트마스크와 무관하게 전체 메뉴를 받는다(`findAllMenus`, authority
   65535 고정). 그 외 사용자는 본인이 가진 역할들의 메뉴별 권한 비트를
   Postgres `BIT_OR`로 합산한 목록만 받는다(`findMenusByUsername`,
   `HAVING BIT_OR(...) > 0` — 읽기 권한조차 없는 메뉴는 애초에 세션에
   담기지 않는다).
2. 화면은 [`GET /api/v1.0/admin/session/menu`](#1-내-메뉴-트리-조회---get-apiv10adminsessionmenu)로
   내비게이션 트리를 받는다.
3. 사용자가 메뉴를 클릭하면 [`GET
   /pragma/{menuId}`](#2-메뉴-화면-조각-조회---get-pragmamenuid)로 그 메뉴의
   화면(Vue SFC 조각)을 텍스트로 받아 `vue3-sfc-loader`가 브라우저에서
   컴파일한다.

## 1. 내 메뉴 트리 조회 - GET /api/v1.0/admin/session/menu

구현: [`SessionMenuController`](../../kkdugi-admin/src/main/java/kkdugi/api/admin/session/SessionMenuController.java)

세션의 `SessionMenu`(flat list, `program`/`authority` 필드 포함)를 그대로
내려주지 않고, 화면 내비게이션에 필요한 필드만 골라
[`MenuTreeItem`](../../kkdugi-admin/src/main/java/kkdugi/api/admin/session/MenuTreeItem.java)으로
옮겨 담은 뒤 트리 모양으로 변환해 응답한다 — **응답에는 `id`, `parentId`,
`title`, `remarks`, `icon`, `sort`, `children` 외의 필드가 없다**
(`program`/`authority`는 의도적으로 제외 — 화면 내비게이션에는 필요 없고,
전자는 뒤에 나올 Pragma 조각 조회에서만, 후자는 그 조각을 렌더링할 때
서버 내부에서만 쓰인다).

```javascript
Response
[
  {
    "id": "M_1",
    "parentId": null,
    "title": "시스템 관리",
    "remarks": "...",
    "icon": "settings",
    "sort": 1,
    "children": [
      {
        "id": "M_2",
        "parentId": "M_1",
        "title": "공통코드 관리",
        "remarks": "...",
        "icon": "code",
        "sort": 1,
        "children": []
      }
    ]
  }
]
```

|Response Status|설명|
|---|---|
|200|정상 — 로그인하지 않은 요청도 200과 함께 빈 배열을 받는다(익명 사용자의 세션 메뉴 목록이 비어있기 때문)|

## 2. 메뉴 화면 조각 조회 - GET /pragma/{menuId}

구현: [`PragmaController`](../../kkdugi-admin/src/main/java/kkdugi/web/admin/PragmaController.java)

**주의: 다른 모든 API와 달리 `/api/v1.0/admin` 접두사 밖에 있다.** 응답도
JSON이 아니라 `Content-Type: text/html`의 순수 텍스트다 — 화면의
`vue3-sfc-loader`가 `fetch(url).then(r => r.text())`로 원문을 그대로 받아
클라이언트에서 컴파일하는 구조이기 때문이다.

`menuId`로 세션의 `SessionMenu`를 찾아, 그 메뉴에 등록된 `program` 필드
이름으로 `src/main/resources/templates/pragma/{program}.vue` 파일을 찾아
Thymeleaf(`SpringTemplateEngine`)로 렌더링한 뒤 그대로 응답 바디에 쓴다.
`.vue` 파일 안에 `th:if`/`th:text` 같은 Thymeleaf 속성을 그대로 써서, 서버가
내려주는 시점에 권한에 따라 조건부로 마크업을 걸러낼 수 있다.

렌더링 시 Thymeleaf 컨텍스트에 `authorities` 변수 하나를 채워 넣는다 —
`Rbac.toMap(menu.getAuthority())`의 결과이며 **키는 `READ`/`WRTE` 같은
이름이 아니라 `Rbac` enum의 숫자 코드 문자열**(`"10"`=읽기, `"20"`=쓰기,
`"30"`=삭제, `"40"`=실행)이다. `.vue` 파일에서는 이렇게 쓴다:

```html
<span th:if="${authorities['10']}">읽기 권한 있음</span>
<button th:if="${authorities['20']}">저장</button>
```

|Response Status|설명|
|---|---|
|200|정상 — 렌더링된 HTML/Vue 텍스트|
|404|다음 세 경우를 구분하지 않고 전부 404로 통일: (1) `menuId`가 세션 메뉴 목록에 없음(존재하지 않거나, 읽기 권한조차 없어 애초에 세션에 담기지 않은 메뉴) (2) 메뉴는 있지만 `program`이 비어있음(그룹/폴더 노드) (3) `program`은 있지만 `templates/pragma/{program}.vue` 파일이 아직 없음|

404를 하나로 통일한 이유: 세션 메뉴 목록 자체가 이미 RBAC로 필터링돼 있어
"존재하지 않음"과 "권한 없음"을 구분해 알려주는 게 의미가 없고(로그인
아이디 존재 여부를 노출하지 않는 것과 같은 원칙 — `KkdugiUserDetailsService`
참고), 화면 파일이 아직 없는 상태도 이 저장소 범위 밖의 정상적인 과도기라
같은 상태 코드로 처리한다.

### `templates/pragma/` 파일은 이 저장소가 만들지 않는다

`src/main/resources/templates/pragma/*.vue`는 실제 화면 산출물이며,
CLAUDE.md의 "backend-only, UI 콘텐츠를 명시적 요청 없이 추가하지 않는다"
범위 밖이다. 현재 이 디렉터리에는 아무 파일도 없다 — 렌더링 파이프라인
자체의 동작만 테스트 전용 픽스처
(`src/test/resources/templates/pragma/test_program.vue`)로 검증돼 있다.
