# Claude 배치 admin 구현 계획 검토

- 검토일: 2026-09-20
- 대상: Claude의 현재 kkdugi 프로젝트 대화에 제시된 S1~S5 분할안 및 S1 상세 설계. 검토 시점에는 저장소에 별도 admin 계획/spec 파일이 없었다.
- 원본 대화 식별자: `b1d6f1bf-c474-4f89-aa25-64db60408451`. 이후 수정본은 이 검토와 구분한다.
- 범위: 계획 검토만 수행. Claude 작업 파일·구현·기존 API 계약은 변경하지 않았다. 테스트 실행 결과를 새로 주장하지 않는다.
- 결론: 단계 분할은 적절하다. 아래 세 항목을 계획에 반영한 뒤 구현을 진행하는 것이 좋다. 실제 R9는 admin 구현 후 수행한다.

## 1. [P1] api_request를 S3로 미루면 S1부터 관리자 API 계약을 위반한다

계획은 S1에서 Runner 생성·PUT·DELETE를 구현하면서 `api_request`와 멱등 키를 S3로 미룬다. 그러나 [admin-api.md:19](admin-api.md#1-공통-계약)는 이 명령들에도 key·생성 시각·72시간 응답 재현을 요구한다. 일회성 토큰 발급만 예외다.

예를 들어 PUT 성공 응답이 유실되면 같은 key와 If-Match를 재전송해도 최초 성공 응답을 받아야 한다. 멱등 기록 없이 version만 검사하면 이미 version이 증가해 412가 된다. 생성의 code 중복 제약과 삭제의 404 처리도 최초 성공 응답 재현을 대체하지 못한다.

**수정:** `kkdugi_batch_api_request`와 멱등 처리 서비스를 S1에 포함한다. 상태 변경·event·성공 응답 저장을 같은 트랜잭션에서 처리한다. 인증/현재 권한 검사 후 기존 성공 기록을 재현하고, 신규 요청에 version 조건을 적용한다. 같은 key/다른 본문 충돌, 동시 요청, 응답 유실, 오래된 신규 key 거절, rollback을 S1 테스트에 추가한다.

S2의 프로그램 설치 보고 PUT과 설치 승인도 key가 필수이므로 멱등 기반이 S3보다 먼저 필요하다. [테이블 계약](tables.md#211-kkdugi_batch_api_request--api-명령의-멱등-응답), [Runner endpoint 표](runner-api.md#3-엔드포인트-목록)

## 2. [P1] S1 API만으로 실제 runner의 정상 연결까지 검증할 수는 없다

계획의 S1은 registration/session/heartbeat만 제공하고, 실제 runner로 등록·세션·heartbeat 연결을 검증한다고 한다. 현재 runner는 세션 개설 후 복구 목록을 조회하고 프로그램 설치 보고를 마쳐야 정상 실행 루프에 진입한다.

- [agent.go:86](../../kkdugi-runner/internal/agent/agent.go#L86): session → restore → catalog refresh/publish 순서다.
- [recovery.go:87](../../kkdugi-runner/internal/agent/recovery.go#L87): restore에서 `GET /assignments?state=UNRESOLVED`를 호출한다. 복구 중 DEGRADED heartbeat도 보내므로 heartbeat 1건 수신만으로 정상 기동을 판정할 수 없다.
- 설치 프로그램이 있으면 `/programs/{programCode}`도 필요하다. 기존 journal에 미해결 작업이 있으면 detail/reconcile도 필요하다.

**수정:** S1 완료 기준을 실제 register CLI + 개별 session/heartbeat 계약 테스트로 한정하고, `run`의 지속 연결 검증은 필요한 endpoint가 갖춰진 단계로 옮긴다. 또는 S1부터 아직 배정이 존재할 수 없는 서버 상태에서 정확한 빈 unresolved 응답을 제공하는 등 선행 범위를 명시한다. 실제 데이터가 생긴 후에도 빈 배열을 고정 반환해서는 안 된다.

S3 수동 실행 검증은 최초 기동·정상 종료 경로로 한정하고, 재시작·장애 복구 수용은 S4까지 완료한 뒤 수행한다. 테스트 편의를 위해 runner의 복구 관문이나 TLS 검증을 끄는 것은 피한다. 로컬 HTTPS는 runner의 사설 CA 설정을 사용한다.

## 3. [P2] 메뉴 seed 제외와 별개로 API–메뉴 프로그램 연결은 S1에 필요하다

계획은 `@RequireAuthority`와 READ/WRTE/DELT를 사용하지만 배치 API에 연결할 `program` 값과 실제 테스트용 메뉴/권한 준비 절차는 정하지 않았다. 메뉴 seed는 호스트 앱 데이터라는 이유로 S1에서 제외했다.

현재 [SecurityChecker.java:46](../../kkdugi-admin/src/main/java/kkdugi/core/security/SecurityChecker.java#L46)는 사용자 세션의 실제 메뉴를 요구하며, `RequireAuthority.program`이 비어 있으면 다른 메뉴의 동일 권한 비트를 사용하는 요청을 막지 않는다. 반대로 배치 program을 지정하고 메뉴를 준비하지 않으면 실제 관리자 호출은 403이 된다. SYS_ADMIN에도 사용할 메뉴 컨텍스트가 필요하다.

**수정:** 배치 관리 API의 program 식별자와 annotation을 확정한다. 제품용 메뉴 seed는 별도 단계로 유지해도 되지만, S1 통합 테스트와 수동 검증에 사용할 메뉴·권한·세션 준비 절차는 포함한다. 허용 메뉴 성공뿐 아니라 다른 메뉴의 WRTE로 runner를 생성하는 요청의 403도 검사한다. [요청 컨텍스트 계약](../api/request-context.md#securitychecker-연계-기준)

## 상세 구현 전에 명시할 동시성 조건

이는 확인된 코드 결함이 아니라 계획의 추가 수용 조건이다.

- 등록/등록 토큰 재발급/폐기/세션 개설은 같은 runner 행에 대한 잠금 순서와 조건을 공유해야 한다. 토큰 소비 한 행의 CAS만으로 runner 상태 전이 전체를 직렬화했다고 간주하지 않는다.
- 예: 등록 중 폐기가 겹친 경우, 폐기 완료 후 진행 중이던 등록이 새 ACCESS 키를 만들고 ACTIVE로 되살리는 결과를 허용하지 않는다. 등록 대 폐기, 세션 대 폐기 경합 테스트를 추가한다.
- S3에서 Attempt를 도입할 때 capacity 축소·삭제뿐 아니라 REVOKED runner의 재등록 토큰 발급에도 미해결 실행 검사와 동시성 제어를 연결한다. 이미 폐기됐다는 사실은 실행 종료 증거가 아니다. [admin-api.md:69](admin-api.md#3-runner-등록설치-승인)

## 유지해도 좋은 설계

- S1~S5로 나누고 필요한 테이블을 단계별 migration으로 추가하는 방식.
- Runner 전용 SecurityFilterChain, 사용자 JWT/runner 토큰의 상호 접근 차단 테스트.
- 토큰 hash 저장·일회성 등록·DB 시각 기반 만료 검사·평문 로그 금지.
- session CAS/boot 재전송, heartbeat와 설정 version 분리, 상태 변경/event 원자성.
- 실제 사용자 actor 기록, `SerialUtils` 사용, PostgreSQL 실제 통합 테스트.
- 배치 라이브러리 분리를 위한 `app.batch` 공용 도메인과 로컬 enum은 기존 규칙의 예외임을 이미 명시한 설계 결정이다. 이를 단순 기술 오류로 판정하지 않되, 확정 내용을 ADR/저장소 지침에 남겨 후속 구현과 일치시킨다.

## 권장 단계 보정

| 단계 | 보정 |
| --- | --- |
| S1 | runner/credential/event + api_request, 관리자 멱등 명령, 인증/등록/session/heartbeat, 메뉴 권한 연결. 실제 register와 개별 API 검증 |
| S2 | Program/설치 보고/승인. S1 멱등 서비스 재사용 |
| S3 | Job/수동 Run/배정/시작/로그/완료와 unresolved/detail 조회. 최초 기동의 정상 실행 검증 |
| S4 | 취소/timeout/lease/reconcile/운영 복구, 재등록 차단 조건, 실제 재시작·장애 검증 |
| S5 | Schedule/재시도/보관 정리, 전체 [R9 수용 시나리오](runner-admin-acceptance.md) 수행 |
