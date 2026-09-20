# R6·R7 구현 계약

R6 로그 영속화 후 R7 reconcile을 연결한다. Admin이 실행·재시도·Workflow를 결정하며 runner는 기존 배정의 증거만 보고한다.

`run --config <설정 파일>`에 자동 연결되며 별도 복구 명령은 필요하지 않다. R5 문서의 로그 폐기·미해결 상태 일괄 중지는 이 구현으로 대체한다. 실제 admin 배치 API 연동은 R9 범위다.

상태: R6·R7 구현 완료. Windows 전체 Go 테스트·정적 검사·빌드와 AlmaLinux 10 WSL2 실제 실행 검증을 통과했다.

## 작업 순서

1. LogChunk/LogAck, AssignmentDetail, ReconcileRequest/Response wire DTO와 검증.
2. 마스킹·UTF-8 chunk·파일 영속화, SQLite 메타데이터·연속 ACK, 용량 제한과 uploader.
3. 구세대 요청 재생을 막는 reconcile journal·소유권 이전, 미시작/완료/UNKNOWN 복구.
4. Agent 연결 및 실제 프로세스 강제 종료·재기동, 응답 유실·로그 장애 테스트.

초기 한도는 chunk 32 KiB(서버 제한 우선), Attempt 합계 10 MiB, 로컬 미ACK 파일 256 MiB이며 디스크 여유 64 MiB를 확보한다. 원문은 영속 저장하지 않고 secret 값의 읽기 경계까지 마스킹한다. EOF 전 crash로 남은 메모리 출력은 복원하지 못한다. 완료 본문 확정 후 파일 유실은 완료를 수정하지 않고 운영 오류로 남긴다.

한도는 현재 Go `spool.Options`의 기본값이며 TOML 설정 항목을 추가하지 않았다. JSON 이스케이프 팽창을 고려해 실제 text chunk는 서버 JSON 한도에서도 안전한 크기로 줄인다. ChunkBytes가 UTF-8 문자 하나를 담지 못하는 설정이면 시작을 거절한다.

재시작 시 신뢰할 수 있는 종료 증거가 없는 START_INTENT/RUNNING/STOPPING은 UNKNOWN이다. PID 존재 여부만으로 종료·성공을 추정하거나 새 프로그램을 시작하지 않는다. 같은 프로세스에서 단절 후 돌아오면 현재 executor의 관측 근거로 reconcile한다.

## 로그 저장·전송

- `<data_dir>/spool/<assignmentId>.<STDOUT|STDERR>.<sequence>.json`에 마스킹된 text, emittedAt, SHA-256을 보관한다. Secret 참조로 읽은 값과 runner ACCESS token을 정확한 byte 값으로 마스킹한다. 겹치는 secret과 읽기 경계도 처리한다. 잘못된 UTF-8은 U+FFFD로 변환한다. 인코딩·암호화 등으로 변형된 secret까지 탐지하는 기능은 아니다.
- 보호된 임시 파일 작성·file Sync·rename·디렉터리 sync 후 SQLite 메타데이터를 커밋한다. Windows 디렉터리 sync의 제약은 기존 credential adapter와 동일하며 실제 정전 내구성까지 검증한 것은 아니다.
- 업로드는 실행·heartbeat와 별도 루프에서 배정/stream마다 한 chunk씩 수행한다. 응답 유실·429·5xx에는 같은 파일 내용으로 backoff/jitter·Retry-After를 적용한다. 로그 PUT은 복합키로 멱등이므로 새 명령 key를 만들지 않는다.
- `contiguousThrough`까지 SQLite ACK를 먼저 커밋하고 파일을 삭제한다. 중복·역행 ACK는 삭제 범위를 늘리지 않고, 생성된 마지막 순번을 넘는 ACK는 거절한다. 완료 ACK 후에도 로그는 전송한다.
- Attempt 상한은 TRUNCATED, 저장 실패는 LOST로 선언하며 파이프를 계속 drain한다. 로그 실패만으로 성공한 업무 결과를 FAILED로 바꾸지 않는다. 디스크 여유 부족·파일 오류는 신규 claim/start를 차단하고 현재 실행은 관측한다. 오류를 고친 뒤 재기동하여 보호된 journal/spool을 다시 검사한다.
- 재기동 시 ACK된 파일의 지연 삭제를 마치고 참조 파일의 크기·digest를 검증한다. DB에 커밋되지 않은 임시/확정 파일은 업로드하지 않고 제거한다. 열려 있던 stream의 메모리 tail은 LOST로 닫는다. 참조 파일 누락·손상은 자동으로 덮어쓰거나 완료 본문을 변경하지 않고 복구 필요 오류로 중지한다.

## 복구 판단

세션 확정 후 DEGRADED heartbeat(`freeSlots=0`)를 보내며 원격 미해결 목록과 로컬 journal을 대조한다. 목록에 없는 미완료 배정이나 로그만 남은 배정도 상세 조회·reconcile한다. 현재 세션으로 구세대 start/started/completion 요청을 그대로 재생하지 않는다.

| 로컬 증거 | observation | 처리 |
| --- | --- | --- |
| START_INTENT 이전의 journal | NEVER_STARTED | Admin 종결 판단을 받으며 로컬 재시작 없음 |
| START_INTENT 이후, 종료 본문 없음 | UNKNOWN | HOLD로 슬롯 유지, 운영 확인 기다림 |
| FINISHED 또는 로그가 남은 ACKED | FINISHED | 저장된 완료 본문으로 RESOLVED 또는 REPORT_COMPLETION 처리 |
| 재기동 없이 계속 관측 중인 executor | RUNNING | 동일 PID·시작 시각·bootId로 CONTINUE_EXISTING 또는 STOP_AND_REPORT 처리 |
| 원격에만 있고 로컬 journal이 없음 | UNKNOWN | 미시작이라고 추측하지 않음 |

Reconcile 요청은 별도 영속 journal에 먼저 저장한다. 같은 세션의 응답 유실은 같은 key·본문으로 재전송하며, 응답 적용·배정 보고 소유권 이전·구 요청 superseded 처리는 한 SQLite 트랜잭션이다. Reconcile 응답의 startAllowed=true는 프로토콜 오류다.

HOLD는 슬롯을 유지하고 주기적으로 다시 대조한다. 대조 완료된 HOLD 이외의 빈 슬롯은 다른 배정에 사용할 수 있다. 대조 진행 중인 배정이 있으면 신규 claim을 차단한다. Lease 만료·409/410은 현재 실행을 재시작하는 근거가 아니다. Claim key 만료도 원격 inventory를 대조한 뒤 미해결 요청을 정리한다. 세션 충돌·인증 오류·기록 손상·복구 요청 자체의 만료처럼 안전하게 해결할 수 없는 경우에는 기록을 보존하고 중지한다.

초기 OS adapter에는 재기동 후 기존 프로세스의 종료 코드·pipe를 다시 회수하는 기능이 없다. Linux에서 남은 프로세스를 PID만으로 kill하지도 않는다. UNKNOWN의 운영 확인은 업무 효과와 프로세스 트리까지 확인해야 하며, 이 구현을 업무 효과 exactly-once 보장으로 해석하지 않는다.

## 파일과 스키마

| 위치 | 역할 |
| --- | --- |
| `internal/spool` | streaming 마스킹·UTF-8 chunk, 파일 검증·용량·ACK 정리, OS별 디스크 여유 조회 |
| `internal/state/log.go` | stream·chunk 메타데이터, 단조 증가 ACK |
| `internal/state/recovery.go` | 영속 reconcile, 소유권 이전·구 요청 superseded |
| `internal/state/migrations/003_logs_recovery.sql` | SQLite v2→v3. `kkdugi_runner_log_stream/log_chunk/reconcile/superseded` 및 요청 superseded 필드 |
| `internal/client/recovery*.go` | 로그·상세 조회·reconcile wire 타입과 검증 |
| `internal/agent/logs.go`, `recovery.go` | 전용 업로드 루프·시작 및 실행 중 복구 |

기존 v1/v2 migration 본문은 바꾸지 않는다. 기존 요청 key·본문과 START_INTENT는 업그레이드 후에도 보존한다.

## 검증 기록

- Windows Go 11개 패키지 테스트 및 Windows/Linux `go vet ./...`·빌드 통과. Linux Agent/spool/state/client 테스트를 AlmaLinux 10 WSL2에서 실제 실행하여 통과.

- 마스킹: 모든 read 간격에서 겹치는 secret·한글·emoji·잘못된 UTF-8·EOF, 실제 token 출력의 디스크 마스킹.
- 로그: file/hash·순번·UTF-8 크기, 중복/역행/미래 ACK, 제한 이후 대량 출력 drain, 파일 저장 실패와 신규 claim 차단, orphan·미완성 tail·참조 파일 누락 처리.
- 통신: 로그·reconcile 응답 유실과 본문 불변, 완료 ACK 후 로그만 남은 재기동, REPORT_COMPLETION의 동일 완료 재전송, 시작 거절·만료 claim 대조, 같은 프로세스에서 RUNNING reconcile.
- 실제 runner subprocess를 RECEIVED, START_INTENT, RUNNING, OS 종료 후 완료 저장 전, FINISHED에서 강제 종료하고 같은 data 디렉터리로 재기동한다. 새 start를 하지 않으며 미시작/UNKNOWN/완료 증거를 구분한다.
- SQLite: reconcile 반영 중 실패 시 소유권·적용 기록의 전체 rollback, 구 요청 재전송 차단, v1→v3 upgrade와 실패 rollback.
- Linux 취소·BeginSend 경합으로 DB query 취소를 저장소 장애로 오인하던 문제를 재현·수정했다. 영속 기록은 HTTP 취소로 중간에 끊지 않고, commit 시도 전 취소는 손상과 구분한다. 수정 후 취소 8회 연속 및 전체 실행 검증을 통과했다.
- Admin JS 55개 통과. Java 296개 중 기존 `DefaultMenuSeedTest.everySeededMenu_hasJapaneseName` 1개 실패(`admin/menu` 일본어 이름 누락), 나머지 295개 통과. Admin 코드 변경 없음.
- 실제 admin·운영 서비스 계정·정전·저장 장치 고장·race 검사는 별도 검증 범위다.

## Ollama 생성 단위

`internal/client/recovery_types.go`, package client. import encoding/json. 아래 struct와 정확한 필드 타입/tag만 생성한다. omitempty 금지, Decimal/Timestamp는 이미 정의된 타입이다.

- LogChunk: EmittedAt Timestamp/emittedAt, Text string/text, SHA256 string/sha256.
- LogAck: AcceptedSequence Decimal/acceptedSequence, ContiguousThrough *Decimal/contiguousThrough, LogsClosed bool/logsClosed.
- AssignmentDetail: Assignment (anonymous embedded), Outcome json.RawMessage/outcome, LogOffsets map[string]*Decimal/logOffsets, RunState string/runState.
- ReconcileRequest: PreviousSession Decimal/previousSession, Observation string/observation, Process *ProcessIdentity/process, Completion json.RawMessage/completion.
- ReconcileResponse: ID string/id, Disposition string/disposition, Session Decimal/session, LeaseUntil *Timestamp/leaseUntil, StartAllowed bool/startAllowed.

Coder 출력에서 기존 ProcessIdentity를 잘못 재정의한 부분과 nullable 필드의 omitempty를 제거했다. 상태 전이·저장·마스킹·복구 로직과 장애 테스트는 주 에이전트가 작성·검토했다.

Reviewer에는 `mask.go`만 전달해 경계 누출 검토를 요청했다. 응답은 여러 항목을 오류라고 명명한 뒤 본문에서 정상 동작이라고 정정했고, 마지막 문장이 잘려 명확한 결론을 제공하지 않았다. 리뷰 통과로 기록하거나 제안을 그대로 적용하지 않았다. 직접 검토와 read 경계·겹침·UTF-8·EOF 테스트를 검증 근거로 삼았다.
