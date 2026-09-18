# 구현된 API 문서

[api-define-admin.md](../api-define-admin.md)는 프로젝트 오너가 정의한 **전체
계약(목표 스펙)**이다. 이 폴더(`docs/api/`)는 그중 **실제로 구현되어 동작하는
API**만을 대상으로, 실제 코드(컨트롤러/모델/매퍼)를 기준으로 작성한 문서다. 두
문서가 다를 경우 이 폴더가 "현재 무엇이 동작하는가"의 근거이고,
`api-define-admin.md`는 "최종적으로 무엇을 만들 것인가"의 근거다.

기능 도메인이 늘어날수록 한 파일에 몰아넣기보다 도메인별로 쪼개는 편이
낫다고 판단해, 파일을 다음과 같이 나눴다.

## 목차

| 파일 | 범위 |
|---|---|
| [auth.md](auth.md) | 로그인/로그아웃 (JWT 발급, 세션 생성) |
| [common-code.md](common-code.md) | 공통코드 조회/저장 (계층형 코드 트리) |
| [i18n-message.md](i18n-message.md) | 다국어 메시지 조회/저장 |
| [session.md](session.md) | 로그인한 사용자의 메뉴 트리 조회, 메뉴 단위 화면(Pragma) 조각 서빙 |

## 공통 사항

- **Base URL**: 도메인 API는 모두 `/api/v1.0/admin` 하위에 있다. 단,
  [session.md](session.md)의 Pragma 화면 조각 엔드포인트(`/pragma/{menuId}`)만
  예외로 이 접두사 밖에 있다 — 이유는 해당 문서에 설명.
- **인증**: 로그인([auth.md](auth.md))으로 발급받은 JWT를
  `Authorization: Bearer <token>` 헤더로 보낸다(`BearerTokenAuthenticationFilter`).
  현재 `SecurityConfigurer`의 인가 규칙은 전부 `permitAll`이다 — 사용자/메뉴/권한
  관리 API(`api-define-admin.md` 3~5절)가 아직 구현되지 않아 실제로 무엇을
  막아야 하는지가 정해지지 않았기 때문이며, 인증 자체(로그인/토큰 검증)는
  이미 동작한다. 즉 지금은 "토큰이 있으면 그 사용자로 인식"만 하고,
  "이 API는 이 권한이 있어야 접근 가능" 같은 차단은 아직 없다.
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
  totalPages, contents }`.
- **감사 필드는 응답에 노출하지 않음**: DB 행 모델(`BaseModel` 상속)의
  `createdAt`/`creatorId`/`updatedAt`/`updaterId`는 지금까지 구현된 어떤 API
  응답에도 포함되지 않는다 — 각 도메인의 API 전용 모델(`MessageContent`,
  `CodeContent` 등)이 도메인 모델을 그대로 내려주지 않고 별도 필드만 골라
  담기 때문이다.
