# ADR-0019: 배치 기능은 라이브러리 분리를 전제로 한 단일 기능 패키지로 둔다

- 상태: 채택 (2026-09-20)
- 관련: [ADR-0016](0016-app-and-admin-feature-split.md), [ADR-0017](0017-menu-context-security-aspect.md), 설계 [S1 spec](../superpowers/specs/2026-09-20-batch-s1-runner-registration-design.md), 구현 계획 [S1 plan](../superpowers/plans/2026-09-20-batch-s1-runner-registration.md), 계약 [docs/batch](../batch/README.md)

## 배경

배치는 나중에 별도 라이브러리로 분리한다. 배치의 관리자 API와 Runner API는 같은 테이블·도메인 로직(Run, 배정)을 공유하고, Runner API는 사용자 JWT가 아닌 runner 전용 토큰으로 인증한다. ADR-0016의 `app.<기능>`/`app.admin.<기능>` 분리와 `core.security`의 `/api/**` 전체 사용자 인증 체인이 그대로는 맞지 않는다.

## 결정

1. **단일 기능 패키지 `kkdugi.app.batch`** (`enums`/`exceptions`/`config`/`models`/`mapper`/`service`). 관리자용과 Runner용을 나누면 서로 import할 수 없어 공유가 막히므로 ADR-0016의 분리를 배치에는 적용하지 않는다. `core`에만 의존하고, `core`와 다른 feature는 배치를 import하지 않는다.
2. **컨트롤러는 기존 규칙대로 `kkdugi.api` 아래에 둔다**: 관리자용 `kkdugi.api.admin.AdminBatchRunnerController`(`/api/v1.0/admin/batch/runners`), Runner용 `kkdugi.api.BatchAgentController`(`/api/v1.0/batch-agent`), 공통 부모 `kkdugi.api.BatchApiSupport`.
3. **Runner API는 배치가 소유한 `SecurityFilterChain`**(`BatchAgentSecurityConfig`, `@Order(1)`, `securityMatcher("/api/v1.0/batch-agent/**")`)으로 인증한다. `core.security`는 수정하지 않는다. 인증 필터는 빈으로 등록하지 않는다(Spring Boot가 Filter 빈을 전역 서블릿 필터로도 등록하기 때문).
4. **배치 enum은 `app.batch.enums`** 에 둔다(CLAUDE.md의 "코드 기반 enum은 `core.enums`" 규칙의 예외). MyBatis `default-enum-type-handler`는 `CodeEnums` 구현체면 패키지와 무관하게 동작한다.
5. **오류는 단일 `BatchException(status, code)`** + `BatchErrors` 상수. 컨트롤러 부모의 `@ExceptionHandler`가 `{code, message}`로 바꾼다. `RestfulExceptionAdvice`는 수정하지 않는다.
6. **메시지 키 `batch.*`** 는 `messages*.properties`에 추가하고, `BatchMessagesTest`가 모든 `BatchErrors` 코드의 메시지 존재를 검사한다.
7. **migration은 기존 `db/migration`의 V13부터** 조각별로 추가한다. Flyway 버전은 위치와 무관하게 전역 유일이므로 분리 시 별도 위치로 옮길 수 있다.
8. **감사 actor는 실제 사용자 ID**(`SessionUtils.getUser().getId()`)다. 다른 admin 서비스의 고정값 `"SYSTEM"`은 답습하지 않는다.
9. **멱등 키(`Idempotency-Key`)와 `kkdugi_batch_api_request`는 S1에 포함**한다(관리자 생성·PUT·DELETE 계약 준수, S2 설치 보고 PUT의 선행 조건).
10. **runner 상태를 바꾸는 모든 명령은 runner 행 `FOR UPDATE` 아래**에서 실행하고 credential 유효성을 다시 확인한다. 잠금 순서는 멱등 advisory lock → runner 행 → credential 행이다.
11. **만료·유효성 판정에는 `clock_timestamp()`** 를 쓴다. `now()`는 트랜잭션 시작 시각이라 행 잠금에서 기다리는 동안 이미 만료된 토큰을 유효로 볼 수 있다. 감사 컬럼(`reg_dtm`/`upd_dtm`)만 트랜잭션 시각을 쓴다.
12. **요청 본문 1 MiB 한도**는 배치가 소유한 서블릿 필터(`BatchBodyLimitFilter`)가 실제로 읽은 byte 수로 판정한다. `FilterRegistrationBean`으로 Spring Security 체인 뒤에 `/api/v1.0/batch-agent/*`, `/api/v1.0/admin/batch/*`에만 등록한다(필터 자체는 전역 빈이 아니다). 처리 순서는 인증 → 본문 한도 → 프로토콜 버전/메뉴 권한이다.
13. **요청 DTO의 문자열·정수 필드는 JSON 토큰 타입을 엄격히 검사**한다(`BatchStrictString`/`BatchStrictInteger`, 필드 단위 `@JsonDeserialize`). Jackson 기본 변환(`1.9`→1, `"1"`→1, `1`→`"1"`)을 배치 요청에서만 막고 전역 설정은 바꾸지 않는다.

## 분리 시 이동 목록

`kkdugi.app.batch.**`, `kkdugi.api.admin.AdminBatchRunnerController`, `kkdugi.api.{BatchAgentController,BatchApiSupport}`, `mapper/postgres/app/batch/*.xml`, `db/migration`의 배치 migration(V13~), `messages*.properties`의 `batch.*` 키, 테스트(`kkdugi/app/batch`, `kkdugi/api/*Batch*`, `kkdugi/support/BatchTestData`). 메뉴 seed(`admin/batch/runner` 화면 메뉴)는 호스트 앱 데이터라 남는다.

## 결과

- 배치 소유 코드가 `Batch*` 이름과 `app.batch` 패키지로 식별되어 분리 대상이 명확하다.
- CLAUDE.md의 enum 위치 규칙과 다른 예외가 생겼다(이 ADR과 CLAUDE.md에 기록).
- 배치 API는 기존 admin API와 달리 REST 동사(GET/POST/PUT/DELETE)와 `If-Match`/`Idempotency-Key`를 쓴다. 계약(`docs/batch/admin-api.md`)이 그렇게 정의돼 있다.
