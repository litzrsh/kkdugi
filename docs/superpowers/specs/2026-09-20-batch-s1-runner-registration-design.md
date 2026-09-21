# 배치 관리자 S1 — Runner 등록 계층 설계

- 작성일: 2026-09-20
- 상태: S1 구현 완료 (2026-09-21) — 계획: [S1 구현 계획](../plans/2026-09-20-batch-s1-runner-registration.md)
- 관련: [배치 설계 README](../../batch/README.md), [테이블](../../batch/tables.md), [관리자 API](../../batch/admin-api.md), [Runner API](../../batch/runner-api.md), [계획 검토](../../batch/admin-plan-review.md), [ADR-0016](../../adr/0016-app-and-admin-feature-split.md), [ADR-0017](../../adr/0017-menu-context-security-aspect.md)

## 1. 배경과 원칙

`kkdugi-admin`에 배치 관리자를 구현한다. 설계 문서(`docs/batch`)는 테이블 11개, 관리자 API, Runner API를 이미 정의하고 있고 Runner(Go)는 구현이 끝났다. 이 문서는 admin 구현 전체를 조각(S1~S5)으로 나누고 첫 조각 S1을 설계한다.

- 조각마다 설계 → 계획 → 구현 → 검증을 따로 돈다. 필요한 테이블만 조각별 Flyway migration으로 추가한다(프로젝트의 "작게 시작" 원칙).
- **배치는 나중에 라이브러리로 분리한다.** 배치 소유 코드는 한 단위로 식별되고, 외부 의존은 `core`뿐이며, `core`는 배치를 모른다.

## 2. 조각 분할

| 단계 | 범위 | 실제 runner 검증 |
| --- | --- | --- |
| **S1** | runner / credential / event / api_request 테이블, 멱등 명령 기반, 관리자 Runner API, Runner 인증·등록·세션·heartbeat, 메뉴 권한 연결 | `register` CLI 실제 등록, 세션·heartbeat는 개별 계약 테스트 |
| S2 | Program, 설치 보고·승인 (S1 멱등 기반 재사용) | 설치 승인 |
| S3 | Job, 수동 Run, 배정·시작·로그·완료, unresolved/detail 조회 | `run` 루프의 최초 기동·정상 종료 |
| S4 | 취소·timeout·lease·reconcile·운영 복구, 재등록 차단 조건 | 재시작·장애 복구 |
| S5 | Schedule, 재시도, 보관 정리, R9 전체 수용 시나리오 | [R9](../../batch/runner-admin-acceptance.md) 전체 |

**S1은 `run` 루프 검증을 완료 기준으로 삼지 않는다.** Runner는 세션 개설 후 `GET /assignments?state=UNRESOLVED`(복구)와 프로그램 설치 보고를 마쳐야 정상 루프에 들어가므로(`agent.go` bootstrap → `recovery.go` restore), S1의 세 endpoint만으로는 지속 연결을 검증할 수 없다. 실제 데이터가 생긴 뒤에도 빈 배열을 고정 반환하는 임시 구현은 만들지 않는다. runner의 복구 관문·TLS 검증은 끄지 않는다. 로컬 HTTPS는 runner의 `admin.ca_file` 사설 CA로 구성한다.

## 3. 라이브러리 경계와 패키지

```
kkdugi.app.batch                 배치 단일 기능 패키지 (관리자·runner 공용 도메인)
├─ models      BatchRunner(BaseModel), BatchRunnerParams(BaseParams), BatchRunnerCredential,
│              BatchEvent, 요청/결과 일반 클래스
├─ enums       RunnerStatus, CredentialType, EventTargetType, EventType, ActorType (CodeEnums 구현)
├─ mapper      BatchRunnerMapper, BatchCredentialMapper, BatchEventMapper, BatchApiRequestMapper
├─ service     BatchRunnerService, BatchEnrollmentService, BatchAgentService,
│              BatchTokenService, BatchIdempotencyService, BatchEventService
├─ exceptions  BatchException(status, code) + BatchErrors (code = batch.*)
└─ config      BatchAgentSecurityConfig, BatchProperties

kkdugi.api
├─ admin.AdminBatchRunnerController   /api/v1.0/admin/batch/runners …   (kkdugi.api.admin)
├─ BatchAgentController               /api/v1.0/batch-agent/…
└─ BatchApiSupport                    공통 예외 핸들러 부모

resources: mapper/postgres/app/batch/*.xml, db/migration/V13__create_batch_runner.sql
```

- `app.batch`는 `core`만 import한다. `core`와 다른 feature는 배치를 import하지 않는다. 분리 시 이동 대상: `app.batch`, `Batch*Controller`, mapper XML, migration.
- **ADR-0016의 `app.<기능>`/`app.admin.<기능>` 분리를 배치에는 적용하지 않는다.** 관리자 API와 Runner API가 같은 테이블·도메인 로직(Run, 배정)을 공유해서, 분리하면 서로 import할 수 없어 공유가 막힌다.
- **enum은 `app.batch.enums`에 둔다.** CLAUDE.md는 코드 기반 enum을 `kkdugi.core.enums`에 두도록 하지만, 배치 enum은 분리 시 함께 나가야 한다. MyBatis `default-enum-type-handler`는 `CodeEnums` 구현체면 패키지와 무관하게 동작한다.
- Runner API는 **배치가 소유한 별도 `SecurityFilterChain`**(`securityMatcher("/api/v1.0/batch-agent/**")`, 기존 체인보다 높은 `@Order`)으로 인증한다. `core.security`는 수정하지 않는다. 현재 core 체인은 `/api/**` 전체를 사용자 JWT 인증 대상으로 묶기 때문이다.
- 예외는 기존 admin 컨트롤러처럼 컨트롤러의 `@ExceptionHandler`로 `{code, message}`에 매핑한다(구현: 두 컨트롤러의 공통 부모 `BatchApiSupport`에 핸들러를 둔다). `RestfulExceptionAdvice`는 수정하지 않는다.
- migration은 우선 기존 `db/migration`에 V13으로 둔다. Flyway 버전은 전역 유일이므로 분리 시 별도 위치로 옮길 수 있다.
- **S1 산출물에 ADR-0019(배치 라이브러리 경계)를 쓰고 CLAUDE.md의 패키지 구조·enum 규칙에 예외를 반영한다.** 후속 구현이 이 결정과 어긋나지 않게 한다.

## 4. 테이블 (V13)

`tables.md`의 §2.1, §2.2, §2.10, §2.11 컬럼·제약을 따른다. 공통 감사 컬럼(`reg_dtm`, `reg_id`, `upd_dtm`, `upd_id`)은 `BaseModel` 매핑과 같다.

- `kkdugi_batch_runner`, `kkdugi_batch_runner_credential`, `kkdugi_batch_event`, `kkdugi_batch_api_request`.
- CHECK: `runner_stat IN (REGISTERING, ACTIVE, PAUSED, REVOKED)`, `credential_type IN (ENROLLMENT, ACCESS)`, `capacity_cnt` 1~200, `operation_hash` 64자 hex, `http_status` 200~299 등.
- 인덱스: `runner_cd` UQ, credential `(runner_id)`, event `(target_type, target_id, occurred_dtm)`, api_request UQ `(subject_type, subject_id, operation_hash, request_key)` + `(expires_dtm)`.
- ID는 `SerialConfig`/`SerialUtils.next`로 생성하고 접두사는 `BR`(runner), `BC`(credential), `BE`(event), `BQ`(api_request)다. 날짜 12자리 + 순번 4자리 + 접두사 2자리 = 18자로 20자 안에 들어간다.
- 업무 시각 `timestamptz(6)`은 UTC `Instant`로 매핑한다. MyBatis 매핑은 첫 mapper 테스트에서 검증한다. 만료·유효성 판정은 앱 시계가 아니라 **DB `clock_timestamp()`** 로 한다. `now()`는 트랜잭션 시작 시각이라 runner 행 잠금에서 기다리는 동안 이미 만료된 토큰을 유효로 볼 수 있다([계획 검토](../../batch/admin-implementation-plan-review.md) 4번). 감사 컬럼(`reg_dtm`/`upd_dtm`)만 트랜잭션 시각을 쓴다.

## 5. 멱등 명령 기반 (`BatchIdempotencyService`)

관리자 API의 생성·PUT·DELETE·폐기 명령은 [runner-api §2](../../batch/runner-api.md#2-멱등성과-타이밍)의 key·생성 시각·72시간 규칙을 사용자 주체 범위로 적용한다. **일회성 등록 토큰 발급만 예외**다. (`api_request`는 S2의 설치 보고 PUT에도 필요하므로 S1에 포함한다.)

한 트랜잭션 안에서 다음 순서로 처리한다.

1. 인증·현재 권한 검사(멱등 응답 반환 전에도 수행).
2. `Idempotency-Key`와 `X-Request-Created-At` 필수 검증.
3. `(subject_type, subject_id, operation_hash, request_key)`로 `pg_advisory_xact_lock`을 잡아 같은 key 경합을 직렬화한다.
4. 만료되지 않은 기록이 있으면: `request_hash`가 같으면 저장된 상태·본문·Location을 재현하고, 다르면 409 `batch.idempotency.conflict`.
5. 기록이 없으면: 생성 시각이 서버 시각과 5분 넘게 차이 나면 410 `batch.request.expired`. 아니면 명령을 실행하고 **상태 변경·event·응답 기록을 같은 트랜잭션에 저장**한다. 롤백되면 응답 기록도 남지 않는다.

`request_hash`는 본문(키 순서 무관, 숫자 정밀도 보존 정규화) + `If-Match` + 요청 생성 시각의 SHA-256이다. 응답 재현 기간은 72시간(`expires_dtm`)이며 만료 정리는 S5로 미룬다(만료된 기록은 없는 것으로 취급하고 5분 창 검사가 오래된 key를 막는다). subject 종류는 `USER`/`RUNNER` 컬럼을 그대로 두고, S1의 테스트는 `USER` 주체만 다룬다. `RUNNER` 주체는 S2에서 추가한다.

## 6. 관리자 API (`/api/v1.0/admin/batch/runners`)

- 권한: `@RequireAuthority(program = "admin/batch/runner")` — 조회 READ, 생성·수정·폐기·토큰 발급 WRTE, 삭제 DELT. 등록 토큰 발급과 폐기는 추가로 `@HasRole("SYS_ADMIN")`.
- 목록은 `Page<T>`(BaseModel + `COUNT(*) OVER()`), 필터 code/name/status, 정렬 생성 시각 내림차순 + ID 내림차순.

| 명령 | 규칙 |
| --- | --- |
| `POST /runners` | 201 + Location. `REGISTERING`, `capacity` 1~200, `config_ver`=1. 멱등 key 필수 |
| `PUT /runners/{id}` | `If-Match` 필수(누락 428, 불일치 412 `batch.version.conflict`). `code` 불변, 상태는 ACTIVE↔PAUSED만. `REGISTERING`/`REVOKED`에서 ACTIVE 전환은 409. 멱등 key 필수 |
| `DELETE /runners/{id}` | `REGISTERING`/`REVOKED`에서만 허용(그 외 409). credential 행도 같은 트랜잭션에서 삭제하고 event는 남긴다. S3 이후 attempt FK 위반은 409로 변환. 멱등 key 필수 |
| `POST /runners/{id}/enrollment` | SYS_ADMIN. `REGISTERING`/`REVOKED`에서만 발급. 미사용 ENROLLMENT 폐기 후 새 토큰(기본 10분). `REVOKED`는 `REGISTERING`으로 되돌리되 `session_ver` 유지. 응답 `no-store`, 멱등 대상 아님 |
| `POST /runners/{id}/revoke` | SYS_ADMIN, `{reason}`. 모든 미폐기 credential 폐기, `REVOKED`. 이미 `REVOKED`면 200. 멱등 key 필수 |

상세 응답은 `id, code, name, capacity, status, hostname, os, agentVersion, lastSeenAt, online, session, version`이며 bigint(`session`, `version`)는 십진 문자열이다. `online`은 `last_seen_dtm`이 `3 × heartbeatSeconds`(기본 30초) 이내인지로 계산한다. 감사 컬럼·event actor는 `SessionUtils.getUser().getId()`를 쓴다(다른 admin 서비스의 고정값 `"SYSTEM"`은 답습하지 않는다).

## 7. Runner API (`/api/v1.0/batch-agent`)

공통: `X-Protocol-Version: 1` 필수(없거나 다르면 409 `batch.protocol.unsupported`), 오류는 `{code, message}`.

관리자 API와 Runner API 모두 요청 본문은 **UTF-8 기준 1 MiB 이하**여야 하며 초과는 413 `batch.payload.too_large`다(계약). 배치 소유 서블릿 필터가 Content-Length와 무관하게 실제로 읽은 byte 수로 판정하므로 chunked 요청도 제한된다. 처리 순서는 **인증(401/403) → 본문 한도(413) → 프로토콜 버전(409)·메뉴 권한(403) → 요청 검증(400)** 이다. 요청 DTO의 정수 필드는 JSON 정수, 문자열 필드는 JSON 문자열만 받는다(Jackson 기본 변환으로 `1.9`→1, `"1"`→1, `1`→`"1"`이 되지 않도록 배치 DTO 필드에만 엄격한 deserializer를 붙이고 전역 설정은 바꾸지 않는다).

- **인증**: 토큰은 `{credentialId}.{256-bit 난수}`, DB에는 SHA-256만 저장하고 상수 시간 비교한다. 폐기·만료·미존재는 401 `batch.credential.invalid`, 토큰 종류 불일치(등록 토큰으로 일반 endpoint 등)는 403 `batch.runner.forbidden`. `last_used_dtm`은 1분 이상 지났을 때만 갱신한다.
- **`POST /registrations`** (ENROLLMENT 토큰): runner 행 잠금 → `REGISTERING` 확인 → 토큰 원자 소비 → `runnerCode` 대조(불일치 403) → 호스트/OS/버전 저장 → ACCESS 토큰 생성(기본 30일, `kkdugi.batch.access-token-ttl`) → `ACTIVE` 전환(**가정**: 설치 승인이 실제 실행 관문이므로 PAUSED를 거치지 않는다). `architecture`는 검증만 하고 event `detail_data`에 남긴다. 응답 `{runnerId, credentialId, accessToken, tokenExpiresAt, session:"0"}`, `no-store`.
- **`POST /sessions`** (ACCESS): runner 행 잠금 아래 `boot_ref = :bootId`이면 현재 세대를 그대로 반환(재전송), 아니고 `session_ver = :expectedSession`이면 `session_ver + 1`과 `boot_ref` 갱신, 그 외 409 `batch.session.stale`. `REGISTERING`/`REVOKED` runner는 거절한다. 응답에 heartbeat/poll/lease 주기(기본 10/3/60초)와 `limits`를 포함한다.
- **`POST /heartbeat`** (ACCESS): `X-Runner-Session`이 현재 세대와 다르면 409 `batch.session.stale`. `freeSlots`(0~capacity), assignments(≤200), mode 검증. 갱신하는 컬럼은 `last_seen_dtm`뿐이고 `config_ver`, `upd_*`, ACTIVE/PAUSED는 건드리지 않으며 event도 남기지 않는다. `acceptingAssignments`는 runner가 ACTIVE일 때만 true. **S1 한계**: 배정 테이블이 없으므로 요청에 포함된 assignment id는 모두 admin이 모르는 ID로 보고 `RECONCILE`로 응답한다. S3에서 실제 조회로 대체한다.
- event: `RUNNER` 대상으로 `CREATED`, `UPDATED`, `DELETED`, `ENROLLMENT_ISSUED`, `REGISTERED`, `SESSION_OPENED`, `REVOKED`를 상태 변경과 같은 트랜잭션에서 저장한다. 토큰 값은 남기지 않고 credential ID만 남긴다.

## 8. 동시성 규칙

- **잠금 순서**: 멱등 advisory lock → runner 행 `SELECT … FOR UPDATE` → credential 행. 등록, 등록 토큰 재발급, 폐기, 세션 개설, 관리자 수정·삭제는 모두 같은 runner 행 잠금을 공유한다. 토큰 소비 CAS 한 행만으로 runner 상태 전이 전체가 직렬화됐다고 보지 않는다.
- 인증(credential 조회)은 잠금 전에 하지만, 상태를 바꾸는 runner 작업은 **잠금 아래에서 credential 유효성(`revoked_dtm IS NULL`, `expires_dtm > now()`)과 runner 상태를 다시 확인**한다. 등록 중 폐기가 겹치면 폐기 완료 후 등록이 새 ACCESS 키를 만들거나 ACTIVE로 되살리지 못한다.
- heartbeat의 `UPDATE`는 `runner_stat IN (ACTIVE, PAUSED)` 조건을 포함해 폐기된 runner의 `last_seen`을 갱신하지 않는다.
- S3에서 Attempt를 도입할 때 capacity 축소, 삭제, `REVOKED` runner의 재등록 토큰 발급에 미해결 실행 검사와 같은 잠금을 연결한다(폐기됐다는 사실은 실행 종료 증거가 아니다).

## 9. 테스트와 검증

기존 방식대로 실제 Postgres(`docker-compose up -d`)에서 `./mvnw.cmd -B -ntp test`로 실행한다. 구현 전에 베이스라인 결과를 기록하고, 이후에는 베이스라인에 없던 실패만 이 작업의 회귀로 본다.

- **mapper/service 통합**: 동시 등록 토큰 소비(1건만 성공), 동시 세션 개설 CAS, bootId 재전송, 폐기·만료 토큰 거절, 토큰 종류별 접근, `If-Match` 412/428, 상태 전이, 삭제 규칙, event/멱등 기록의 롤백 원자성.
- **동시성 경합**: **등록 대 폐기**, **세션 대 폐기**, 등록 토큰 재발급 대 등록, **잠금 대기 중 만료된 토큰**(등록·세션 개설이 잠금을 얻은 시점에 이미 만료됐으면 401).
- **입력 경계**: 본문 한도 이하/정확한 경계/1 byte 초과, 다중 byte UTF-8, 선행 공백, Content-Length 없는 요청, 거절 시 상태·event·멱등 기록 미생성. 잘못된 JSON 타입(`capacity` 1.9/`"1"`, `expectedSession` 숫자, `freeSlots` 문자열)과 heartbeat 항목의 `phase` 누락/null은 500이 아니라 400.
- **멱등**: 같은 key 재요청 재현(생성·PUT·DELETE·폐기), 같은 key/다른 본문 409, 동시 요청 직렬화, 응답 유실(성공 후 재전송 시 version 증가에도 최초 응답 재현), 오래된 신규 key 410, 롤백 시 기록 없음.
- **API/권한**(MockMvc + `TestAuthorization`): 허용 메뉴의 READ/WRTE/DELT 성공과 권한 부족 403, **다른 메뉴의 WRTE 비트로 runner를 생성하는 요청 403**, SYS_ADMIN 제한 endpoint의 일반 사용자 403, 오류 `{code, message}` 형식.
- **보안 체인**: 사용자 JWT로 `/batch-agent/**` 호출 시 401, runner 토큰으로 `/api/v1.0/admin/**` 호출 시 401, 기존 인증 테스트 회귀 없음(전체 `mvn test`).
- **실제 runner 검증(수동)**: 로컬 HTTPS(사설 CA + runner `admin.ca_file`)로 `kkdugi-runner`의 register CLI를 실제 admin에 연결해 등록을 확인한다. 세션·heartbeat는 위 개별 계약 테스트로 검증하며 `run` 루프는 S3에서 검증한다.
- **메뉴·권한 준비 절차**: 제품용 메뉴 seed는 호스트 앱 데이터이므로 별도 단계로 미룬다. 단, S1의 수동 검증을 위해 배치 메뉴(`admin/batch/runner`)를 추가하고 기본 관리자에게 권한을 부여하는 **개발용 SQL 스크립트(migration 아님)** 와 사용법을 계획서에 포함한다.

## 10. 완료 기준과 범위 밖

완료 기준: V13 migration 적용, 위 테스트 전체 통과(`mvn test`), register CLI 실제 등록 확인, ADR-0019와 CLAUDE.md 갱신, `docs/api/`에 S1 API 문서 추가.

범위 밖(후속 조각): Program·설치 테이블·보고·승인(S2), Job·Run·Attempt·claim·로그·완료·`GET /assignments`(S3), 취소·timeout·lease·reconcile·resolve(S4), Schedule·재시도·보관 정리(S5), 배치 화면용 제품 메뉴 seed, 프런트엔드.

## 11. 미확정·가정

- 등록 성공 시 runner 상태를 `ACTIVE`로 두는 것은 문서에 없는 **가정**이다.
- 삭제 허용 상태(`REGISTERING`/`REVOKED`), `online` 판정 창(`3 × heartbeatSeconds`), ACCESS 토큰 기본 유효기간(30일)도 가정이며 `BatchProperties`로 조정할 수 있다.
- program 식별자 `admin/batch/runner`는 기존 컨벤션(`admin/user`, `admin/authority`)을 따른 제안이다.
- 구현 계획에서 확정한 세부: PUT의 `status`는 선택(생략하면 유지, 지정하면 `ACTIVE`/`PAUSED`이고 현재 상태도 `ACTIVE`/`PAUSED`여야 함). `config_ver`는 등록·폐기·재발급(`REVOKED`→`REGISTERING`)·PUT에서 올라가고 heartbeat·세션 개설에서는 올라가지 않는다. 예외는 단일 `BatchException`이다. `kkdugi_batch_event.run_id`/`attempt_no`는 컬럼만 두고 S3에서 FK를 추가한다.
