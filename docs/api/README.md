# 구현된 API 문서

[api-define-admin.md](../archive/api-define-admin.md)(2026-09-18 삭제, 원문은
`docs/archive/`로 이동)는 프로젝트 오너가 정의한 **전체 계약(목표 스펙)**이었다.
이 폴더(`docs/api/`)는 그중 **실제로 구현되어 동작하는 API**만을 대상으로,
실제 코드(컨트롤러/모델/매퍼)를 기준으로 작성한 문서이며, 이제 이 폴더가
현재 API의 유일한 살아있는 근거다. 아카이브 스펙의 절(공통코드/메시지/메뉴/권한/사용자)은
모두 이 폴더에 구현·문서화되어 있고, 아카이브 문서는 원문 기록으로만 남는다.

기능 도메인이 늘어날수록 한 파일에 몰아넣기보다 도메인별로 쪼개는 편이
낫다고 판단해, 파일을 다음과 같이 나눴다.

## 목차

| 파일 | 범위 |
|---|---|
| [request-context.md](request-context.md) | 모든 API의 요청 메뉴 ID 헤더와 shell 예외, 프런트 전달 및 서버 Aspect 인가 기준 |
| [auth.md](auth.md) | 로그인/로그아웃 (JWT 발급, 세션 생성) |
| [common-code.md](common-code.md) | 공통코드 조회/저장 (계층형 코드 트리) |
| [code.md](code.md) | 공통코드 조회(사용자용) — 정확한 경로 조회, 언어별 이름, 배열 응답 |
| [i18n-message.md](i18n-message.md) | 다국어 메시지 조회/저장 |
| [menu.md](menu.md) | 메뉴 관리 — 전체 트리 조회/저장(SYS_ADMIN 전용, 계층형) |
| [authority.md](authority.md) | 권한 관리 — 권한 CRUD, 메뉴 RBAC 매핑, 사용자 매핑, 후보 사용자 조회 |
| [user.md](user.md) | 사용자 관리 — 목록/상세/등록/저장, 사용자별 권한·권한 후보 조회, 비밀번호 초기화, 삭제, 상태 일괄 변경 |
| [session.md](session.md) | 로그인한 사용자의 메뉴 트리 조회, 메뉴 단위 화면(Pragma) 조각 서빙 |

## 공통 사항

- **Base URL**: 관리자용 도메인 API(공통코드/메시지/메뉴/권한/사용자 CRUD)는 `/api/v1.0/admin` 하위에 있고,
  사용자용 조회 API는 `/api/v1.0/menu`([session.md](session.md)의 내 메뉴 트리 조회),
  `/api/v1.0/code`([code.md](code.md))처럼 `/api/v1.0` 바로 아래에 있다. 예외 —
  [auth.md](auth.md)의 로그인/로그아웃(`/api/v1.0/auth/login`, `/api/v1.0/auth/logout`)과
  [session.md](session.md)의 Pragma 화면 조각 엔드포인트(`/pragma/{menuId}`, `/api/v1.0`
  프리픽스조차 없음 — 이유는 해당 문서에 설명).
  프런트엔드 `static/js/api/http.mjs`의 경로 화이트리스트는
  `/api/v1.0/admin/`·`/api/v1.0/auth/`·`/api/v1.0/menu`·`/api/v1.0/code`·`/pragma/`만 허용한다
  (사용자용 코드 조회는 codes(path), 관리용 목록은 list(resource, params)로 구분한다).
- **인증**: 로그인([auth.md](auth.md))이 발급해 쿠키로 내려준 JWT를
  `Authorization: Bearer <token>` 헤더(우선) 또는 같은 토큰 쿠키로 보낸다
  (`BearerTokenAuthenticationFilter`가 둘 다 지원). 프런트는 쿠키에서 토큰을
  꺼내 헤더로 붙인다.
  로그인·로그아웃 외의 API/Pragma는 인증이 필요하다. 컨트롤러의
  `@RequireAuthority` / `@HasRole`을 SecurityChecker Aspect가 검사한다.
  `X-Menu-Id`의 세션 메뉴 소속, 프로그램과 API 관계, 읽기/쓰기/삭제/실행
  비트를 서버에서 검증한다. 메뉴 관리 API는 SYS_ADMIN 역할도 필요하다.
  미인증은 401, 인가 거부는 403 JSON 응답이다.
  상세 규약은 [요청 컨텍스트](request-context.md)와 [ADR-0017](../adr/0017-menu-context-security-aspect.md)을 참조한다.
- **에러 응답 형식**: 컨트롤러가 직접 처리하지 않는 예외는
  `RestfulExceptionAdvice`(`@RestControllerAdvice`)가 받아 아래 형태로
  내려준다(각 도메인 문서의 커스텀 에러 응답은 이 컨트롤러가 명시적으로
  `@ExceptionHandler`로 처리하는 경우이며, 형태가 다르니 해당 문서 참고).

  ```javascript
  // RestfulException → 단일 객체
  { "code": "some.error.code", "message": "..." }

  // RestfulExceptions → 배열
  [ { "code": "some.error.code", "message": "..." }, ... ]

  // 그 외 처리되지 않은 예외 → 500
  { "code": "err.default", "message": "..." }
  ```
- **정렬/페이지네이션**: 목록 검색 파라미터는 `kkdugi.core.models.BaseParams`를
  상속해 `page`(기본 1), `pageSize`(기본 200, 최대 200)를 공통으로 갖는다.
  응답은 `kkdugi.core.models.Page<T>`: `{ page, pageSize, totalItems,
  totalPages, contents }`. 2026-09-18부터 쿼리 레벨 페이징으로 바뀌어
  `totalItems`는 별도 count 쿼리가 아니라 데이터 쿼리 자체의
  `COUNT(*) OVER()` 결과에서 나온다(자세한 내용은
  [공통 규약 5번](../conventions/common-base-model.md#5-목록-조회-응답--kkdugicoremodelspaget를-재사용한다) 참고).
- **감사 필드는 응답에 노출하지 않음**: DB 행 모델(`BaseModel` 상속)의
  `createdAt`/`creatorId`/`updatedAt`/`updaterId`는 지금까지 구현된 어떤 API
  응답에도 포함되지 않는다. `AdminMessage`/`AdminCode` 등 API 전용
  모델은 `Page<T>`의 `T extends BaseModel` 제약 때문에 이제 `BaseModel`을
  상속하지만, `@JsonIgnoreProperties`로 이 감사 필드들과 `rownum`을
  명시적으로 숨긴다(`totalSize`는 `BaseModel` 자체에 `@JsonIgnore`가 있다).
