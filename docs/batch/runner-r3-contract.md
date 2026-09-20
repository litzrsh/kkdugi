# R3: 로컬 상태·영속 요청 계약

R3는 `internal/state`의 SQLite 저장소와 OS 단일 인스턴스 잠금을 구현한다. HTTP·executor를 호출하지 않으며 `run` CLI 연결은 R5다. Workflow·재시도 결정은 admin에 남는다.

## 파일과 DB

- `<data_dir>/runner.lock`은 Windows LockFileEx / Linux flock으로 잠근다. 파일은 지우지 않으며 종료·강제 종료 시 OS가 잠금을 해제한다. 같은 identity를 다른 data_dir/머신에 복사하는 것은 지원하지 않는다.
- `<data_dir>/state.db`와 WAL/SHM은 서비스 계정만 접근 가능한 디렉터리에 둔다. 기존 넓은 권한·symlink/reparse 경로는 자동 수정하지 않고 거절한다.
- 최초 초기화 의도를 `.state.initialized`에 먼저 기록한다. DB 유실·빈 파일·손상·미지원 버전은 새 빈 DB로 대체하지 않는다. 초기화 도중 중단되어 정상 DB가 없으면 운영자 복구가 필요하다.
- `modernc.org/sqlite` + database/sql, WAL, synchronous=FULL, foreign_keys=ON, busy_timeout=5000. 하나의 전용 연결과 mutex로 DB 작업을 직렬화한다. 모든 변경은 짧은 트랜잭션이며 외부 작업을 포함하지 않는다.
- migration SQL을 바이너리에 embed한다. user_version과 migration checksum을 검사하며 DB 무결성·identity(admin URL + runnerId)를 열 때 확인한다. 다운그레이드와 알 수 없는 schema는 거절한다.
- `kkdugi_runner_meta`는 단일 행(키/값 제안 대신 타입과 제약을 적용), `kkdugi_runner_assignment`, `kkdugi_runner_request`, `kkdugi_runner_migration`을 사용한다. 로그 테이블은 R6에서 추가한다. SQLite TEXT/INTEGER/BLOB을 사용하며 Oracle 도메인 타입을 복사하지 않는다.

## 세션과 journal

Open마다 현재 프로세스 bootId(UUID)를 만든다. `PrepareSession`은 미확정 요청이 있으면 그 bootId/expectedSession/agentVersion을 그대로 반환한다. `ConfirmSession`은 요청과 응답 runnerId·세대를 대조하여 확정한다. 이전 bootId 확인 뒤 현재 bootId의 세션을 별도로 준비해야 일반 요청을 만들거나 전송할 수 있다. 세대는 십진 문자열로 저장하되 비교는 정수로 수행한다.

배정 snapshot은 불변 UTF-8 JSON 객체다. phase·version·현재 소유 session·bootId·detail·startIntent latch를 저장한다. `Commit`은 배정의 version/phase CAS, 요청 예약, 이전 요청 ACK를 함께 처리한다. 허용 단계만 전진하며 START_INTENT latch는 지우지 않는다. START_INTENT로의 전이는 현재 boot/session에서 한 번만 성공한다. R5는 이 커밋의 성공을 받은 같은 worker에서만 OS start를 호출해야 한다. journal만으로 실행 허가·프로세스 생존·완료를 추정하지 않는다.

FINISHED의 detail은 불변 완료 JSON으로 사용하고 완료 요청 본문과 정확히 일치시킨다. 통신 불가 시 완료 본문만 먼저 저장할 수 있다. 최초 전송을 준비할 때 그 본문으로 요청 key·생성 시각을 확정한다(API의 5분 최초 접수 규칙). 곧 전송할 수 있다면 FINISHED와 요청을 함께 커밋한다. ACKED로 바꾸려면 해당 완료 요청 ACK를 같은 트랜잭션에 반영한다. 구세대 journal의 조회·보존은 가능하지만 R3에서 소유권을 자동 이전하거나 다시 실행하지 않는다. R7 reconcile 연결 전에는 구세대 수정도 거절한다.

## 영속 요청

요청은 ID(UUID), assignmentId(선택), method/path, key(UUID), 생성 시각(UTC), session, 원문 body, SHA-256, status(PENDING/IN_FLIGHT/ACKED), 재전송 예정 시각을 저장한다. Authorization·credential은 저장하지 않는다. body/hash/key/session/시각은 갱신 API가 없다. JSON bigint를 재직렬화하지 않고 원문 bytes를 보존한다. 일반 출력은 요청·journal의 본문을 숨긴다.

`Commit` 후 `BeginSend`로만 전송용 복사본을 받는다. 현재 boot의 확정 session과 다르면 거절하며 IN_FLIGHT도 같은 요청으로만 다시 얻는다. 응답 유실 시 key/body/session을 바꾸지 않는다. `ScheduleRetry`는 다음 시각만 바꾸며 실제 재시도 여부는 상위 agent가 결정한다. 72시간 경과 기록도 자동 삭제하거나 새 key로 바꾸지 않는다. 단일 세션에서 미해결 claim은 하나만 허용한다.

## Ollama 작업 단위: models.go

package state, import encoding/json 및 kkdugi-runner/internal/client. 아래 데이터 선언만 생성한다. 함수·SQL은 제외한다.

- Phase string 상수: Received="RECEIVED", Prepared="PREPARED", StartRequested="START_REQUESTED", Permitted="PERMITTED", StartIntent="START_INTENT", Running="RUNNING", Stopping="STOPPING", Finished="FINISHED", Acked="ACKED", Unknown="UNKNOWN".
- Identity: BaseURL string, RunnerID string.
- Assignment: ID string, Session client.Decimal, BootID string, Phase Phase, Version int64, Snapshot json.RawMessage, Detail json.RawMessage, StartIntent bool.
- AssignmentChange: ID string, ExpectedVersion int64, ExpectedPhase Phase, NextPhase Phase, Snapshot json.RawMessage, Detail json.RawMessage. ExpectedVersion=0은 새 RECEIVED 배정.
- RequestDraft: AssignmentID string, Method string, Path string, Body json.RawMessage.
- Request: ID string, AssignmentID string, Method string, Path string, Key string, CreatedAt client.Timestamp, Session client.Decimal, Body json.RawMessage, Hash string, Status string, NextAt client.Timestamp.
- Acknowledgement: RequestID string, Response json.RawMessage. 204는 빈 Response.
- Mutation: Assignment *AssignmentChange, Request *RequestDraft, Ack *Acknowledgement.

## 검증 기준

Windows 및 Linux 실제 SQLite·잠금, 프로세스 중복 기동과 강제 종료 후 재개, migration rollback/checksum/미지원 버전/손상 거절, 트랜잭션 커밋 전후 강제 종료, 상태·요청·ACK 원자성, bigint 원문·hash 보존, 다른 세션 전송 차단, START_INTENT 재허용 금지를 테스트한다. 실제 정전·저장 장치 고장과 운영 서비스 계정 검증은 별도다.

## 저장 타입과 상위 계층의 책임

| 테이블 | 주요 컬럼 |
| --- | --- |
| kkdugi_runner_meta | singleton INTEGER PK(1), base_url/runner_id/session/boot_id TEXT, pending_session BLOB, pending_hash TEXT |
| kkdugi_runner_assignment | id TEXT PK, session/boot_id/phase TEXT, version INTEGER, snapshot/detail BLOB, start_intent INTEGER(0/1) |
| kkdugi_runner_request | id TEXT PK, assignment_id TEXT FK(nullable), method/path/request_key/created_at/session/body_hash/status/next_at TEXT, body/response BLOB |
| kkdugi_runner_migration | version INTEGER PK, checksum TEXT |

DDL은 [001_initial.sql](../../kkdugi-runner/internal/state/migrations/001_initial.sql)에 있다. 모든 테이블은 STRICT다. JSON은 정확한 UTF-8 bytes 보존을 위해 TEXT 제안에서 BLOB으로 확정했다. 완료 본문 hash는 동일 본문으로 만들어진 request의 body_hash에 저장한다. 권한 처리는 기존 credential의 검증 함수를 재사용하며 인증 토큰을 state API에 전달하지 않는다.

R3의 `detail`은 시작 허가·프로세스 식별·종료 증거·완료 JSON을 저장하는 내부 경계다. R5에서 실제 protocol DTO로 해석하고 startBefore·lease·취소·종료 증거를 검증한 뒤 Commit해야 한다. State 자체가 `startAllowed`를 추정하지 않는다. Request는 key를 사용하는 v1 mutation endpoint만 예약하며 heartbeat·GET·로그 chunk는 이 큐에 넣지 않는다. 로그는 R6의 복합키·spool 계약을 사용한다.

각 반환값은 DB와 별도의 복사본이다. `Request`/`Assignment` 조회는 복구용이며 전송 허가가 아니다. 전송은 BeginSend 성공 이후에만 수행한다. 구세대 미해결 배정이 남은 상황에서 신규 claim을 막는 조정 루프는 R5/R7의 책임이다. 별도 프로세스의 저장소 복사는 로컬 잠금으로 감지할 수 없으므로 identity당 data_dir 하나라는 운영 계약을 유지한다.

요청 본문은 1 MiB 이하 JSON 객체, ID는 20자 ASCII 식별자, 세대는 signed bigint 범위 십진 문자열이다. 목록은 ID cursor와 1~200개 page로 읽는다. UNKNOWN/ACKED 등의 journal·요청을 삭제하는 API는 제공하지 않는다. SQLite 쓰기 실패·커밋 실패·상태 손상 감지 후에는 해당 Store의 추가 변경을 거절하고 재개방·복구를 요구한다.

의존성은 `modernc.org/sqlite v1.59.0`으로 고정했다. [드라이버 문서](https://pkg.go.dev/modernc.org/sqlite@v1.59.0), [SQLite WAL](https://sqlite.org/wal.html), [동기화 설정](https://sqlite.org/pragma.html#pragma_synchronous)을 기준으로 구현했으며 이번 검증은 Windows/amd64와 AlmaLinux 10 WSL2/Linux/amd64에서 수행했다.
