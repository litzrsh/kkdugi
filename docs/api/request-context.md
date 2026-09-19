# API 요청 메뉴 컨텍스트

2026-09-19 — 프런트엔드 전달 및 서버 SecurityChecker Aspect 인가 적용 완료.

## 헤더 규약

로그인·로그아웃을 제외한 모든 API 요청은 `X-Menu-Id` 헤더로 **요청을 발생시킨 화면의 실제 메뉴 ID**를 보낸다. 프로그램 코드(`admin/code`), 화면 코드(`admcode`), 수정 대상 행의 ID가 아니다. 조회, 검색, 페이징, 공통코드 조회, 배치 저장, 팝업에서 발생하는 요청 모두 같은 규약을 따른다.

```http
POST /api/v1.0/admin/code/persist
Authorization: Bearer <token>
X-Requested-With: XMLHttpRequest
X-Menu-Id: M000000001
Content-Type: application/json
```

- 메뉴 ID는 DB의 60자 제한에 맞춰 최대 60자의 출력 가능한 ASCII 문자열을 원문으로 전송한다. 빈 값, 앞뒤 공백, 제어문자, 비 ASCII 값은 전송 전에 거부한다. 현행 서버 발급 메뉴 ID는 ASCII다. 별도 URL 디코딩은 하지 않는다.
- JSON 본문·쿼리·기존 인증 및 CSRF 규약은 변경하지 않는다.
- `/api/v1.0/auth/login`, `/api/v1.0/auth/logout`에는 헤더를 붙이지 않는다. 로그인은 기존 vanilla JS form 요청을 유지한다. 다른 `/auth/` API가 추가되더라도 자동으로 예외가 되지 않는다.
- `/pragma/{menuId}`도 헤더를 보낸다. URL의 디코딩된 메뉴 ID와 헤더가 일치해야 한다. 이전에 열려 있던 화면 ID를 보내지 않는다.
- CSS, JS, Vue 정적 파일 요청은 API 트랜잭션이 아니므로 적용 대상이 아니다.

## 메뉴 선택 전의 공통 화면

최초 메뉴 트리 조회는 아직 실제 메뉴가 없으므로 `GET /api/v1.0/menu`에 한해 예약 값 `X-Menu-Id: __shell__`을 보낸다. 재조회도 shell에서 수행하면 같은 값을 사용한다. 이미 열린 페이지가 메뉴 트리를 요청하면 그 페이지의 실제 메뉴 ID를 보낸다.

`__shell__`은 DB 메뉴 ID나 공통 관리자 권한이 아니다. 프런트는 이 값을 다른 엔드포인트 또는 HTTP 메서드에 사용할 수 없게 차단한다. 서버 Aspect도 같은 조건을 확인하고 유효한 로그인 세션의 메뉴 트리 조회만 허용한다. 메뉴 검사 전반을 우회하는 값으로 처리해서는 안 된다.

## 프런트 사용법

`PageHost.vue`는 메뉴가 바뀔 때 새로 생성되며 해당 메뉴에 고정된 API 객체를 하위 컴포넌트에 제공한다.

```js
const {api}=inject('kkdugi');
await api.list('code',{page:1,pageSize:20});
await api.codes('/SYSTEM/STATUS');
await api.persist('code',changes);
```

페이지 밖에서 화면 요청을 만드는 경우 메뉴 ID를 명시한다.

```js
const pageApi=services.api.forMenu(menu.id);
await pageApi.persist('menu',changes);
// 저수준 호출도 명시적 컨텍스트가 필요하다.
await services.api.request('/api/v1.0/admin/menu','GET',undefined,{menuId:menu.id});
```

메뉴 ID는 요청 시점의 전역 활성 메뉴에서 읽지 않는다. 페이지 생성 시 고정하여 이전 페이지의 저장 및 팝업 콜백이 화면 전환 후에도 원래 메뉴로 전송되도록 한다. `forMenu()`로 고정한 ID는 개별 호출의 옵션으로 덮어쓸 수 없다. Pragma는 항상 요청 대상 ID를 사용한다. 일반 요청에서 ID가 빠지면 네트워크 전송 전에 오류를 낸다.

## SecurityChecker 연계 기준

SecurityChecker Aspect에서 `request.getHeader("X-Menu-Id")`에 해당하는 원문 헤더를 읽고 중복도 검사한다. 이 헤더는 클라이언트가 보낸 컨텍스트이므로 그 자체가 접근 권한의 증거는 아니다. 서버는 다음을 검증한다.

1. 유효한 로그인 세션 및 헤더 형식·필수 여부.
2. 실제 메뉴가 존재하고 현재 사용자에게 허용되었는지.
3. 해당 메뉴의 프로그램과 요청 API가 허용된 관계인지. 다른 메뉴 ID로 접근 권한을 빌릴 수 없어야 한다.
4. `@RequireAuthority`의 읽기·쓰기·삭제·실행 및 `@HasRole` 조건을 서버 기준으로 충족하는지.
5. Pragma 경로 ID와 헤더 일치, shell 예약 값의 정확한 엔드포인트·메서드 제한.

위 검증은 현재 구현된 API 컨트롤러에 적용되어 있다. 인증 실패는 401, 인가 실패는 403이다. annotation 사용법과 배치 작업별 권한, 프록시 적용 경계는 [ADR-0017](../adr/0017-menu-context-security-aspect.md)을 참조한다.
