# 세션 메뉴 & 화면(Pragma) 조각

`api-define-admin.md`(2026-09-18 삭제, 원문은 아카이브로 이동)에는 없는
API다. 그 문서의 [3절 메뉴
관리](../archive/api-define-admin.md#3-메뉴-관리)는 메뉴 자체를 CRUD하는 SYS_ADMIN
전용 관리 API이고, 여기서 다루는 건 **로그인한 사용자 본인**이 볼 수 있는
메뉴를 내려받아 화면을 구성하는 별개의 흐름이다.

## 동작 개요

1. 로그인하면 `KkdugiUserDetailsService`가 사용자의 세션에 볼 수 있는 메뉴
   전체를 `SessionMenu`(flat list)로 실어둔다. `SYS_ADMIN` 역할은 메뉴 권한
   비트마스크와 무관하게 전체 메뉴를 받는다(`findAllMenus`, authority
   65535 고정). 그 외 사용자는 본인이 가진 역할들의 메뉴별 권한 비트를
   Postgres `BIT_OR`로 합산한 목록만 받는다(`findMenusByUsername`,
   `HAVING BIT_OR(...) > 0` — 권한 비트가 하나도 없는 메뉴는 세션에
   담기지 않는다).
2. 화면은 [`GET /api/v1.0/menu`](#1-내-메뉴-트리-조회---get-apiv10menu)로
   내비게이션 트리를 받는다.
3. 사용자가 메뉴를 클릭하면 [`GET
   /pragma/{menuId}`](#2-메뉴-화면-조각-조회---get-pragmamenuid)로 그 메뉴의
   화면(Vue SFC 조각)을 텍스트로 받아 `vue3-sfc-loader`가 브라우저에서
   컴파일한다.

## 1. 내 메뉴 트리 조회 - GET /api/v1.0/menu

**사용자용 API라 `/api/v1.0/menu`에 있다**(관리자용 메뉴 CRUD는 `/api/v1.0/admin/menu`,
[menu.md](menu.md)). 2026-09-19 이전에는 `/api/v1.0/session/menu`였으며 옛 경로 별칭은 없다.

구현: [`MenuController`](../../kkdugi-admin/src/main/java/kkdugi/api/MenuController.java)

세션의 `SessionMenu`(flat list, `program`/`authority` 필드 포함)를 그대로
내려주지 않고, 화면 내비게이션에 필요한 필드만 골라
[`Menu`](../../kkdugi-admin/src/main/java/kkdugi/app/menu/models/Menu.java)으로
옮겨 담은 뒤 트리 모양으로 변환해 응답한다 — **응답에는 `id`, `parentId`,
`title`, `remarks`, `icon`, `sort`, `children`, `openable` 외의 필드가 없다**
(`program`/`authority`는 의도적으로 제외 — 화면 내비게이션에는 필요 없고,
전자는 뒤에 나올 Pragma 조각 조회에서만, 후자는 그 조각을 렌더링할 때
서버 내부에서만 쓰인다).

### 언어 변경 및 메뉴 재조회

- `GET /api/v1.0/menu?lang=en_US`처럼 요청 언어를 지정한다. 생략 시 Spring locale 쿠키, 쿠키가 없으면 `ko_KR`를 사용한다.
- 메뉴 ID·계층·정렬·프로그램 유무·권한 범위는 로그인 세션을 기준으로 유지한다. 매 요청마다 세션에 있는 메뉴 ID에 한해 DB의 해당 언어 메뉴명과 설명을 조회한다.
- 해당 언어 번역 행이 없으면 세션의 메뉴명·설명으로 대체한다. 번역 누락으로 부모나 자식을 제거하지 않는다. 권한과 세션 스냅샷은 수정하지 않는다.
- 화면 언어 변경 시 편집 내용 확인 후 현재 메뉴 해시를 유지해 셸을 새 언어로 다시 요청하고, 새 셸에서 위 API를 선택 언어와 `X-Menu-Id: __shell__`로 다시 호출한다. Spring 메시지와 Pragma도 같은 언어를 사용한다.

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
|200|정상 — 인증된 세션과 X-Menu-Id 필요. 최초 조회는 __shell__, 페이지 요청은 실제 메뉴 ID + READ|
|401|인증 없음/세션 만료|
|403|메뉴 컨텍스트 누락 또는 허용되지 않은 메뉴·권한|

## 2. 메뉴 화면 조각 조회 - GET /pragma/{menuId}

구현: [`PragmaController`](../../kkdugi-admin/src/main/java/kkdugi/web/admin/PragmaController.java)

**주의: `/api/v1.0/admin` 접두사 밖에 있다** (auth.md/이 문서의 내 메뉴 트리 조회와
같은 이유 — 사실 이 엔드포인트는 `/api/v1.0` 프리픽스조차 없다, 아래 참고).
응답도
JSON이 아니라 `Content-Type: text/html`의 순수 텍스트다 — 화면의
`vue3-sfc-loader`가 `fetch(url).then(r => r.text())`로 원문을 그대로 받아
클라이언트에서 컴파일하는 구조이기 때문이다.

`menuId`로 세션의 `SessionMenu`를 찾아, 그 메뉴에 등록된 `program` 필드
이름으로 `src/main/resources/templates/pragma/{program}.vue` 파일을 찾아
Thymeleaf(`SpringTemplateEngine`)로 렌더링한 뒤 그대로 응답 바디에 쓴다.
`program`은 `/`로 하위 폴더를 가리킬 수 있다 — 기본 메뉴의 `admin/code`는
`templates/pragma/admin/code.vue`다. 각 세그먼트는 영문/숫자/`_`/`-`만
허용하고(정규식 `[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*`), 점(`..` 포함)·역슬래시·
절대 경로·빈 세그먼트가 들어 있으면 파일을 찾지 않고 404다 — `program`은 메뉴
API로 저장되는 값이라 검증 없이 넘기면 `templates/pragma/` 밖의 `.vue`를 읽을 수
있기 때문이다.
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
|401|인증 없음/세션 만료|
|403|X-Menu-Id 누락·형식 오류, 경로와 헤더 불일치, 세션 메뉴에 없음, 그룹/프로그램 없음, 유효한 RBAC 비트 없음|
|404|프로그램 경로 형식이 잘못되었거나 해당 Vue 템플릿이 없음|

SecurityChecker가 컨트롤러 실행 전에 메뉴 접근을 검증한다. 알 수 없는 ID와 다른 사용자의 메뉴 ID는 모두 403으로 처리한다. 화면 렌더링 시 개별 READ 비트에 따른 마크업 분기는 유지한다.

### 현재 Pragma 화면 구현

사용자의 프런트엔드 구현 요청에 따라 실제 화면은 kkdugi-admin의 templates/pragma 아래에서 관리한다. 자세한 구조는 [디자인·프런트엔드 문서](../design/README.md)를 참조한다.

기본 메뉴(`V10__insert_default_menu.sql`)의 program 코드는 `home`,
`admin/code`, `admin/message`, `admin/menu`, `admin/authority`, `admin/user`다.
이 중 `admin/code`, `admin/message`, `admin/menu`, `admin/authority`는 `templates/pragma/admin/`에
파일이 있고, `home`, `admin/user`는 아직 없어 404로 응답한다.
프런트의 시스템 메뉴 삭제 보호(`isProtected`)는 이 program 코드를 기준으로 한다
(`static/js/domain/batch.mjs`의 `systemPrograms`) — 기본 메뉴의 program을 바꾸면 함께 바꿔야 한다.

### 프런트 요청 헤더

Pragma는 Accept: application/json, text/html;q=0.9로 요청한다. JSON만 수락하면 produces=text/html과 맞지 않아 406이 발생한다. X-Requested-With: XMLHttpRequest로 비동기 요청임을 표시해 HTML 페이지용 인증 리다이렉트와 구분한다.

모든 Pragma 요청에 `X-Menu-Id: <URL과 동일한 메뉴 ID>`가 필요하다. API/Pragma fetch는 미인증 시 401 JSON, 인가 거부 시 403 JSON을 받는다. [ADR-0017](../adr/0017-menu-context-security-aspect.md) 참조.
