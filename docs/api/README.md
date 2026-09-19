# 구현된 API 문서

[api-define-admin.md](../archive/api-define-admin.md)(2026-09-18 삭제, 원문은
`docs/archive/`로 이동)는 프로젝트 오너가 정의한 **전체 계약(목표 스펙)**이었다.
이 폴더(`docs/api/`)는 그중 **실제로 구현되어 동작하는 API**만을 대상으로,
실제 코드(컨트롤러/모델/매퍼)를 기준으로 작성한 문서이며, 이제 이 폴더가
현재 API의 유일한 살아있는 근거다. 아직 구현되지 않은 권한/사용자 스펙은
위 아카이브 문서에만 남아 있고, 착수 시점에 오너에게 재확인이 필요하다
(아카이브 문서 상단 참고).

기능 도메인이 늘어날수록 한 파일에 몰아넣기보다 도메인별로 쪼개는 편이
낫다고 판단해, 파일을 다음과 같이 나눴다.

## 목차

| 파일 | 범위 |
|---|---|
| [auth.md](auth.md) | 로그인/로그아웃 (JWT 발급, 세션 생성) |
| [common-code.md](common-code.md) | 공통코드 조회/저장 (계층형 코드 트리) |
| [code.md](code.md) | 공통코드 조회(사용자용) — 하위 코드 목록, 언어별 이름 |
| [i18n-message.md](i18n-message.md) | 다국어 메시지 조회/저장 |
| [menu.md](menu.md) | 메뉴 관리 — 전체 트리 조회/저장(SYS_ADMIN 전용, 계층형) |
| [session.md](session.md) | 로그인한 사용자의 메뉴 트리 조회, 메뉴 단위 화면(Pragma) 조각 서빙 |

## 공통 사항

- **Base URL**: 도메인 API는 모두 `/api/v1.0/admin` 하위에 있다. 예외 셋 —
  [auth.md](auth.md)의 로그인/로그아웃(`/api/v1.0/auth/login`,
  `/api/v1.0/auth/logout`)과 [session.md](session.md)의 내 메뉴 트리 조회
  (`/api/v1.0/session/menu`)는 "admin 리소스"(공통코드/메시지/메뉴 CRUD)가
  아니라 로그인한 사용자 본인을 다루는 요청이라 이 접두사 밖에 있고,
  [session.md](session.md)의 Pragma 화면 조각 엔드포인트(`/pragma/{menuId}`)도
  예외다(`/api/v1.0` 프리픽스조차 없음) — 이유는 해당 문서에 설명.
  프런트엔드 `static/js/api/http.mjs`의 경로 화이트리스트도
  `/api/v1.0/admin/`·`/api/v1.0/auth/`·`/api/v1.0/session/`·`/pragma/` 네
  접두사만 허용한다.
- **인증**: 로그인([auth.md](auth.md))이 발급해 쿠키로 내려준 JWT를
  `Authorization: Bearer <token>` 헤더(우선) 또는 같은 토큰 쿠키로 보낸다
  (`BearerTokenAuthenticationFilter`가 둘 다 지원). 프런트는 쿠키에서 토큰을
  꺼내 헤더로 붙인다.
  현재 `SecurityConfigurer`의 인가 규칙은 전부 `permitAll`이다 — 사용자/권한
  관리 API([archive/api-define-admin.md](../archive/api-define-admin.md) 4~5절)가 아직 구현되지 않아 실제로 무엇을
  막아야 하는지가 정해지지 않았기 때문이며, 인증 자체(로그인/토큰 검증)는
  이미 동작한다. 즉 지금은 "토큰이 있으면 그 사용자로 인식"만 하고,
  "이 API는 이 권한이 있어야 접근 가능" 같은 차단은 아직 없다(메뉴 관리
  API도 마찬가지로 아직 `permitAll`이다 — RBAC 체크 자체가 권한 시스템에
  달려 있어서, 메뉴 CRUD가 먼저 구현됐다고 따로 잠글 방법이 없다).
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
  응답에도 포함되지 않는다. `MessageContent`/`CodeContent` 등 API 전용
  모델은 `Page<T>`의 `T extends BaseModel` 제약 때문에 이제 `BaseModel`을
  상속하지만, `@JsonIgnoreProperties`로 이 감사 필드들과 `rownum`을
  명시적으로 숨긴다(`totalSize`는 `BaseModel` 자체에 `@JsonIgnore`가 있다).
