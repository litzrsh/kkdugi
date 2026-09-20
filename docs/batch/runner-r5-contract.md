# R5: Agent·배정 실행 루프

R5는 `run --config`를 연결한다. 세션 확정 → 미해결 상태 확인 → catalog 보고 → heartbeat/claim → 배정별 worker → 시작 허가/START_INTENT → executor → 불변 완료 저장/ACK 순서다. Workflow와 재시도 Attempt 생성은 admin 책임이다.

상태: 구현 및 Windows·AlmaLinux 10 WSL2 실행 검증 완료. 실제 admin 배치 API는 아직 미구현이다.

후속 변경: 아래 R5 시점의 로그 폐기·미해결 상태 일괄 중지 제한은 [R6·R7 구현](runner-r6-r7-contract.md)으로 대체되었다. 현재 동작은 해당 문서를 따른다.

## 실행 방법

1. [R2 계약](runner-r2-contract.md)에 따라 admin HTTPS/CA, credential 디렉터리와 프로그램 TOML을 준비하고 `register --config <설정 파일>`로 등록한다.
2. `register`는 data 디렉터리를 새로 만들 때 전용 권한을 설정한다. 이미 존재하는 디렉터리가 다른 사용자에게 열려 있으면 거절하며 임의로 권한을 변경하지 않는다. 기존 등록은 같은 서비스 계정이 소유한 보호된 data 디렉터리를 준비한다.
3. Manifest의 `secret_names`가 있다면 보호된 `<credential_dir>/secrets` 디렉터리에 같은 이름의 UTF-8 파일을 준비한다. Linux는 디렉터리 0700/파일 0600, Windows는 credential과 같은 소유자·ACL 정책을 따른다.
4. `kkdugi-runner run --config <설정 파일>`을 foreground로 실행한다. Ctrl+C 또는 SIGTERM은 drain을 요청한다. OS 서비스 설치·운영 계정 검증은 R8이다.

R5는 시작 시 프로그램 설치를 보고하며 이후 admin 승인이 있어야 배정을 실행한다. 미해결 상태로 중지되면 data/DB를 지워 재등록하지 않는다. 기록을 보존하고 R7 reconcile 또는 admin 운영 복구로 해결한다.

## 이번 단계의 경계

- worker 수와 미완료 슬롯은 min(로컬 capacity, 세션 capacity) 이하. 미해결 claim은 한 개이며 그 응답까지 임시 슬롯을 예약한다. FINISHED도 ACK 전 슬롯을 유지한다.
- heartbeat는 별도 goroutine으로 현재 snapshot만 전송한다. HTTP 대기 중에도 executor 관측·timeout은 계속한다. 네트워크 오류는 신규 claim/start를 중단하고 성공한 통신 후 현재 허가를 다시 대조한다. 401/403/409/410과 protocol 오류는 무한 재시도하지 않고 DEGRADED로 보존한다.
- 시작 허가 응답 유실은 같은 key/body로 대조한다. 응답의 startBefore/lease가 만료되면 spawn하지 않는다. 최종 파일 확인 뒤 START_INTENT를 커밋한 같은 worker에서 한 번만 OS start를 호출한다. 알려진 STOP은 시작 전에도 확인한다.
- 이전 boot의 미해결 배정/실행 요청 또는 admin의 미해결 목록이 있으면 R5는 자동 복구·재실행하지 않고 복구 필요 오류로 중지한다. 구세대 reconcile과 자동 복구는 R7이다. 초기 세션 개설의 응답 유실은 R3의 저장된 요청으로 복원한다.
- R6 로그 spool 이전이므로 stdout/stderr는 계속 drain하되 bytes를 저장하지 않는다. 출력이 있으면 LOST, 없으면 COMPLETE, lastSequence=null을 명시한다. 이를 운영 로그 수집 완료로 표시하지 않는다.
- 종료 신호는 새 claim 중단 후 기본 30초 drain, 이후 남은 worker의 프로세스 트리 중지·결과 영속화. 미ACK 결과는 남기며 R7 이전에는 재기동 시 운영 복구가 필요하다.
- secret은 승인된 manifest의 이름만 `<credential_dir>/secrets/<name>`의 보호 파일에서 읽어 자식 환경에 전달한다. 값은 출력하지 않으며 ACCESS token·부모 환경을 상속하지 않는다. 값 파일은 최대 64 KiB UTF-8, NUL 금지, 끝 CR/LF 한 줄 제거.

Admin의 `acceptingAssignments=false`는 신규 claim을 중지한다. 이미 받은 배정은 유효한 개별 시작 허가와 STOP 여부로 판단한다. 완료 본문은 프로세스 관측 직후 FINISHED에 저장하며, started ACK 대기가 끝난 후 최초 완료 전송 직전에 멱등 요청을 만든다. 따라서 오랜 단절 중 생성된 key가 최초 전송 시각 제한을 넘는 문제를 피한다. 전송을 시작한 요청은 key·본문을 바꾸지 않는다.

프로세스 종료를 확인할 수 없으면 UNKNOWN을 보존하고 신규 claim을 차단한다. 응답 유실 테스트의 단일 실행 보장은 시험한 장애 구간에 대한 결과이며, 업무 효과의 exactly-once나 미구현 crash reconcile을 보장하지 않는다.

## 구현 위치

| 위치 | 역할 |
| --- | --- |
| `internal/agent/agent.go` | 세션·catalog bootstrap, 슬롯, heartbeat·claim, drain |
| `internal/agent/worker.go` | 배정 준비, 시작 gate, executor 관측, 불변 완료 저장·ACK |
| `internal/agent/retry.go` | 영속 요청 재전송, backoff·jitter·Retry-After |
| `internal/agent/runtime.go` | 설정·등록 credential·CA·SQLite·secret 연결 |
| `internal/client/assignment*.go` | 배정·heartbeat DTO, 응답 검증과 HTTP 전송 |
| `internal/executor` | OS start 직전 gate 및 시작·중지 관측 callback |
| `internal/state/session.go` | 현재 boot의 확정 세션 조회 |
| `internal/credential/secrets.go` | 보호된 secret 파일 읽기 |

## Ollama 모델 생성 작업

`internal/client/assignment_types.go`, package client, import encoding/json. 아래 struct만 생성한다. Go 필드 / 타입 / JSON tag 순서다. omitempty는 사용하지 않는다.

- AssignmentProgram: ID/string/id, Code/string/code, Version/string/version, Revision/string/revision.
- Execution: TimeoutSeconds/int/timeoutSeconds, StopGraceSeconds/int/stopGraceSeconds, BusinessKey/string/businessKey.
- Assignment: ID/string/id, RunID/string/runId, Attempt/int/attempt, Session/Decimal/session, Program/AssignmentProgram/program, Input/json.RawMessage/input, Execution/Execution/execution, LeaseUntil/Timestamp/leaseUntil, State/string/state, StartAllowed/bool/startAllowed, StartBefore/*Timestamp/startBefore.
- StartPermit: ID/string/id, StartAllowed/bool/startAllowed, StartBefore/Timestamp/startBefore, LeaseUntil/Timestamp/leaseUntil.
- ProcessIdentity: PID/Decimal/pid, StartedAt/Timestamp/startedAt, BootID/string/bootId.
- Started: StartedAt/Timestamp/startedAt, Process/ProcessIdentity/process.
- StartedAck: ID/string/id, State/string/state, Action/string/action.
- CompletionAck: ID/string/id, CompletionAccepted/bool/completionAccepted, AttemptState/string/attemptState, RunState/string/runState, LogState/string/logState.

## 검증

TLS fake admin과 실제 foreground 보조 프로그램으로 claim/start/started/completion 응답 유실, 완료 ACK 전 슬롯 유지, 병렬 상한, 취소·timeout, 만료 허가, 승인 불일치, 재기동 시 중복 시작 거절을 검증한다. 실제 admin 구현·OS 서비스·재기동 reconcile은 후속 단계다.

- Windows: Go 10개 패키지 테스트, `go vet ./...`, 실행 파일 빌드 통과.
- Linux: 정적 검사·빌드 후 Agent/client/executor/credential/state의 테스트 바이너리를 AlmaLinux 10 WSL2에서 실제 실행하여 통과. Cross compile만으로 실행 지원을 판단하지 않았다.
- 실제 SQLite START_INTENT 쓰기 실패 시 spawn 0회, admin 단절 중 로컬 timeout·FINISHED 저장, 복구 후 완료 ACK, drain 후 재기동 차단을 확인했다.
- Admin JS 55개 통과. Java 296개 중 기존 일본어 메뉴 시드 테스트 1개 실패(`admin/menu`), 295개 통과. Runner 구현과 별개로 남은 실패다.
- Ollama coder의 출력에서 `float64` 세션/PID, protobuf timestamp, nullable 필드의 `omitempty`를 발견하여 기존 `Decimal`·`Timestamp` 및 필수 JSON 필드 계약으로 수정했다. 상태·통신·실행 로직과 장애 테스트는 주 에이전트가 직접 작성·검토했다.
- Race 검사, 실제 admin 및 운영 서비스 계정 연동은 미검증이다. 다음 단계는 R6 로그 영속화·마스킹·전송이다.
