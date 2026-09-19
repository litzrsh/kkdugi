# 권한 관리 API

- 구현: `kkdugi.api.admin.AdminAuthorityController` → `AdminAuthorityService` → `AdminAuthorityMapper`
- 설계 근거: [authority-system-design.md](../authority-system-design.md), [ADR-0018](../adr/0018-authority-management-system.md)
- 원본 스펙(아카이브, 이 문서와 다르면 이 문서가 맞다): [api-define-admin.md](../archive/api-define-admin.md) 4절

## 공통

- Base URL: `/api/v1.0/admin/authority`
- **접근 제어**: 모든 요청은 로그인(Bearer 토큰)과 요청을 발생시킨 화면의 메뉴 ID(`X-Menu-Id` 헤더)가 필요하고, `SecurityChecker` Aspect가 컨트롤러의 `@RequireAuthority`/`@HasRole`을 검사한다([요청 컨텍스트](request-context.md), [ADR-0017](../adr/0017-menu-context-security-aspect.md)). 이 API는 **`SYS_ADMIN` 역할과 프로그램 `admin/authority` 메뉴의 RBAC를 모두** 요구한다 — 조회(목록·상세·후보 사용자·메뉴 트리)는 READ, 등록·저장은 WRTE, 삭제는 DELT. 메뉴 RBAC만으로 열어두면 사용자에게 `SYS_ADMIN`을 부여할 수 있는 권한 상승 경로가 되므로 메뉴 관리 API와 같이 역할을 함께 요구한다. 미인증은 401, 인가 거부는 403이다.
- **권한 변경은 이미 로그인된 세션에 즉시 반영되지 않는다.** 다음 로그인부터 적용된다.
- **RBAC 맵 키**는 `Rbac` 숫자 코드다: `"10"` 조회, `"20"` 등록, `"30"` 삭제, `"40"` 실행. (원본 스펙의 `READ`/`WRITE` 표기는 쓰지 않는다.)
- **`users`는 항상 객체** `{ "id", "name", "image", "applyStartDate", "applyEndDate" }`, 날짜는 `yyyy-MM-dd`. 요청에서 날짜를 생략하면 시작=오늘, 종료=`9999-12-31`. `name`(사용자 이름)과 `image`(프로필 이미지, 없으면 `null`)는 **응답 전용**이다 — 서버가 사용자 테이블에서 채워 내려주고, 요청(regist/save)에 실려 와도 무시한다(상세 응답을 그대로 save 요청으로 돌려보내도 된다).
- **에러 본문**: `{ "code": "authority.err.…", "message": "…" }` (`ExceptionMessage`, 컨트롤러 로컬 `@ExceptionHandler`).
- **`SYS_ADMIN`은 유형과 무관하게 예약된 role 코드**다. 세션/메뉴 우회 판단(`KkdugiUserDetailsService`, `SessionUtils`)이 role 문자열만 보고 유형은 보지 않기 때문에, ROLE이 아닌 유형에 `SYS_ADMIN`이 생기면 우회 권한이 생긴다. 그래서 role이 `SYS_ADMIN`인 권한(기본 시드 `ROLE`/`SYS_ADMIN`)은 삭제·role/type 변경·비활성화(`use: "N"`)가 안 되고 이름/설명만 바꿀 수 있으며, 어떤 권한이든 ROLE이 아닌 유형의 `SYS_ADMIN`으로 만들거나 바꿀 수 없다.

| 상태 | code | 의미 |
|---|---|---|
| 401 | (공통 인증 오류) | 미인증 — 접근 제어 참고 |
| 403 | (공통 인가 오류) | `SYS_ADMIN` 역할이 없거나, 메뉴 RBAC 비트(READ/WRTE/DELT)가 부족하거나, `X-Menu-Id` 메뉴가 `admin/authority` 프로그램이 아님 |
| 400 | `authority.err.malformed_request` | 필수값(role/type/name) 누락, 길이 초과(role 60자, name 200자, remarks 1000자), 알 수 없는 `type`/`use` 값, `users`/`menus` 항목 오류(빈 id, 중복 id, 적용기간 역전, `authorities` 없음, 알 수 없는 RBAC 키 또는 `null` 값) |
| 400 | `authority.err.user_not_found` | `users`에 존재하지 않는 사용자 id |
| 400 | `authority.err.menu_not_found` | `menus`에 존재하지 않는 메뉴 id |
| 404 | `authority.err.not_found` | 대상 권한 없음 |
| 409 | `authority.err.duplicate` | 같은 `type` 안에서 `role`이 이미 있음. `(ROLE, SYS_ADMIN)`으로 만들거나 바꾸려는 시도도 이미 시드가 있으므로 여기에 해당한다 |
| 409 | `authority.err.immutable` | role이 `SYS_ADMIN`인 권한의 삭제, role/type 변경, 비활성화(`use: "N"`) 시도. 또는 ROLE이 아닌 유형(`PLAN`)의 `SYS_ADMIN`을 등록/저장으로 만들거나 바꾸려는 시도 |

> **파싱 실패는 400이 아니라 500이다.** 이 컨트롤러는 JSON 본문 자체가 깨졌거나 `applyStartDate`/`applyEndDate`가 `yyyy-MM-dd`로 해석되지 않는 경우를 따로 매핑하지 않는다. 이런 요청은 프로젝트 공통 `RestfulExceptionAdvice`의 catch-all로 떨어져 500(`err.default`)을 돌려준다(기존 전역 동작이며, 위 400 목록은 값 검증에 한한다).

## 1. 권한 목록 — `POST /api/v1.0/admin/authority`

```javascript
Request
{ "role"?: "부분 일치", "type"?: "ROLE | PLAN", "name"?: "부분 일치", "page": 1, "pageSize": 200 }

Response  // kkdugi.core.models.Page — users는 포함되지 않는다
{
  "page": 1, "pageSize": 200, "totalItems": 1, "totalPages": 1,
  "contents": [
    { "id": "A2026091908110001", "role": "SYS_ADMIN", "type": "ROLE",
      "name": "시스템 관리자", "remarks": null, "use": "Y" }
  ]
}
```

`role`/`name`은 부분 일치, `type`은 정확히 일치(알 수 없는 값이면 400이 아니라 빈 결과). `page`/`pageSize`는 생략 가능하며 기본 1 / 200, `pageSize` 최대 200. 정렬은 `role`, `id` 순.

## 2. 권한 상세 — `GET /api/v1.0/admin/authority/{id}`

```javascript
Response
{
  "id": "A2026091908110001", "role": "SYS_ADMIN", "type": "ROLE",
  "name": "시스템 관리자", "remarks": null, "use": "Y",
  "users": [ { "id": "U2026091508020001", "name": "관리자", "image": null, "applyStartDate": "2026-09-19", "applyEndDate": "9999-12-31" } ]
}
```

`users`는 사용자 id 순. 없으면 404.

## 3. 권한 등록 — `POST /api/v1.0/admin/authority/regist`

```javascript
Request  // id는 보내지 않는다(서버 채번, 접두사 A). users/menus는 생략 가능
{
  "role": "OPERATOR", "type": "ROLE", "name": "운영자", "remarks": "…", "use": "Y",
  "users": [ { "id": "U2026091508020001", "applyStartDate": "2026-10-01", "applyEndDate": "2026-12-31" } ],
  "menus": [ { "id": "M2026080102030001", "authorities": { "10": true, "20": false, "30": false, "40": false } } ]
}

Response  // 상세(2)와 같은 형태 (200)
```

`use` 생략 시 `Y`. `type`이 `ROLE`/`PLAN`이 아니면 400. 같은 `type` 안에 같은 `role`이 있으면 409(`authority.err.duplicate`). role이 `SYS_ADMIN`이고 `type`이 `PLAN`이면 409(`authority.err.immutable`).

## 4. 권한 저장 — `POST /api/v1.0/admin/authority/{id}`

요청/응답 형태는 등록과 같다(본문의 `id`는 무시하고 경로의 id를 쓴다). 없는 id는 404. 서버는 id를 조회하기 **전에** 요청 본문부터 검증하므로, 없는 id에 잘못된 본문을 보내면 404가 아니라 400이다. **저장은 전체 교체**다.

- `users`/`menus` 필드가 **없으면(null)** 그 매핑은 건드리지 않는다.
- **빈 배열이면** 그 매핑을 전부 비운다.
- 목록이 있으면 그 목록으로 교체한다(목록에 없는 기존 행은 삭제, 나머지는 upsert).
- `use`를 생략하면 기존 값을 유지한다.
- `menus`에서 네 RBAC가 모두 `false`인 항목은 저장하지 않는다(행이 없는 것이 접근권 없음). 따라서 모두 `false`인 항목만 보내면 그 권한의 메뉴 매핑이 비워진다.
- role 또는 type을 바꾸면 새 `(type, role)`이 다른 권한과 겹치는지 검사한다(겹치면 409 `authority.err.duplicate`).
- **role이 `SYS_ADMIN`인 권한은 이름/설명(과 `users`/`menus` 매핑)만 바꿀 수 있다.** role·type 변경이나 `use: "N"`은 409(`authority.err.immutable`). 다른 권한을 `(ROLE, SYS_ADMIN)`으로 이름 바꾸려는 시도는 이미 시드가 있으므로 409(`authority.err.duplicate`), 다른 권한을 ROLE이 아닌 유형의 `SYS_ADMIN`으로 바꾸려는 시도는 409(`authority.err.immutable`)다.
- **경고 — `SYS_ADMIN`의 `users`.** `SYS_ADMIN` 권한에 `"users": []`(또는 현재 시스템 관리자가 빠진 목록)를 보내는 것도 허용되며, 빠진 사용자의 `SYS_ADMIN` 부여가 제거된다. 그 사용자들은 **다음 로그인부터** 전체 메뉴 우회를 잃는다(이미 로그인된 세션은 영향 없음). 모든 시스템 관리자가 비게 되면 DB를 직접 고치는 것 외에 복구할 방법이 없다. 클라이언트는 이 권한에 빈/부분 `users`를 절대 보내지 말 것 — "바꿀 수는 있지만 비울 수는 없다" 가드는 오너 결정 대기 중인 후속 과제다([설계 문서](../authority-system-design.md#열린-결정--후속-작업)).

## 5. 권한 삭제 — `POST /api/v1.0/admin/authority/{id}/delete`

권한과 그 사용자 매핑·메뉴 매핑을 한 트랜잭션으로 삭제한다(사용자/메뉴 자체는 삭제되지 않는다). 이미 없는 id는 200으로 아무것도 하지 않는다. role이 `SYS_ADMIN`인 권한은 409(`authority.err.immutable`). 응답 본문은 없다.

> 원본 스펙의 경로는 `authroity` 오타였다. 구현은 `authority`다.

## 6. 후보 사용자 조회 — `POST /api/v1.0/admin/authority/{id}/user`

권한에 붙일 수 있는 사용자 — 상태가 정상(`20`)이고 이 권한에 아직 매핑되지 않은 사용자 — 를 조회한다.

```javascript
Request  // 본문 자체를 생략해도 된다
{ "query"?: "로그인 ID / 이름 / 이메일에 대한 대소문자 무시 부분 일치" }

Response
[ { "id": "U2026091509120001", "username": "admin", "name": "관리자", "image": null } ]
```

이름 순으로 최대 200명까지 돌려준다(초과분은 `query`로 좁힌다). 권한이 없으면 404.

## 7. 권한 기준 메뉴 트리 — `POST /api/v1.0/admin/authority/{id}/menu`

모든 메뉴를 트리(`children` 중첩)로 돌려주고, 각 노드에 이 권한이 갖는 RBAC를 싣는다. 부여가 없는 메뉴는 전부 `false`. 요청 본문은 없다.

```javascript
Response
[
  {
    "id": "M2026091517570001", "parentId": null,
    "locale": { "ko_KR": { "label": "시스템 관리", "remarks": null } },
    "icon": "…", "program": "admin/menu", "use": "Y",
    "path": "/M2026091517570001", "level": 0, "sort": 1,
    "authorities": { "10": true, "20": false, "30": false, "40": false },
    "children": [ … ]
  }
]
```

자식이 없는 리프 노드의 `children`은 `null`이다(메뉴 관리 조회와 같음). 권한이 없으면 404.

## 메뉴 삭제와의 관계

메뉴 관리([menu.md](menu.md))에서 메뉴를 삭제하면 그 메뉴(와 하위 메뉴)에 부여된 이 API의 메뉴 권한 매핑(`kkdugi_auth_menu`)도 함께 삭제된다. 권한 자체는 그대로 남는다.

## 알려진 한계

- `kkdugi_user_auth`를 쓰는 경로가 두 곳이다 — 권한 저장(이 문서)과 사용자별 권한 저장([user.md](user.md) 6번). `app.admin.<기능>`끼리 import하지 않는 규칙(ADR-0016) 때문에 SQL은 각자 갖고, 두 경로가 같은 검증 규칙(적용기간 기본값·역전, 대상 존재)을 쓰도록 유지한다.
- 깨진 JSON 본문/해석 불가한 적용기간 문자열은 400이 아니라 500이다(위 "공통"의 노트).
- 기존 공통코드/메시지 목록 API(`AdminCodeParams`, `AdminMessageParams`)는 `page`/`pageSize`를 생략하면 500이 난다(Jackson 3가 다중 인자 편의 생성자를 속성 생성자로 자동 감지해 `int`에 `null`을 넣으려 하기 때문). 권한 목록(`AdminAuthorityParams`)은 기본 생성자에 `@JsonCreator`를 붙여 이 문제가 없다 — 나머지 둘은 이 작업 범위 밖이라 그대로 둔 후속 과제다. 자세한 내용은 [ADR-0018](../adr/0018-authority-management-system.md#결과).
