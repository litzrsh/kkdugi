# Runner 프로그램 구조 설계

- 작성일: 2026-09-20
- 상태: **구현·테스트를 시작하기 위한 설계안**. Go 소스나 로컬 DB migration은 아직 추가하지 않았다. 테스트 결과에 따라 내부 패키지와 운영 기본값을 조정한다.
- 기준: [채택 기술 스택](runner-tech-stack.md), [Admin–Runner API v1](runner-api.md), [실행 및 장애 처리](execution.md).
- 범위: 별도 `kkdugi-runner` Go 프로젝트. Runner는 배정된 프로그램 하나를 실행한다. 스케줄, Job 재시도, Workflow 진행·owner 승인은 admin이 결정한다.

## 1. 전체 구성

프로세스 하나가 runner identity 하나를 담당한다. `agent`가 배정 상태와 수명 주기를 관리하고, 배정마다 worker 하나를 만든다. 프로그램은 Go goroutine 내부에서 업무를 수행하는 것이 아니라 **별도 OS 프로세스**로 실행된다.

```text
CLI / OS Service
       |
       v
     Agent -------------------------- Client ----HTTPS---- Admin
       |                                ^
       +-- Session / Recovery           |
       +-- Heartbeat loop --------------+
       +-- Claim loop ------------------+
       +-- Report loop -----------------+   시작·완료 보고
       +-- Log upload loop -------------+   제한된 동시 업로드
       |
       +-- Assignment worker [0..capacity]
               +-- Program catalog     승인 revision과 로컬 파일 확인
               +-- Workspace           배정별 입력·결과 파일
               +-- Executor            실행·관측·종료
               +-- Spool               stdout/stderr 수집
       |
       +-- State                       SQLite journal / 요청 / ACK
```

새 실행 권한은 admin의 배정과 시작 허가에서만 나온다. 로컬 DB에 남은 항목이나 프로세스 실패를 보고 runner가 새 Attempt를 만들지 않는다. 완료 보고가 접수되면 해당 실행의 책임을 정리하고 다음 배정을 요청한다. 다음 Workflow 단계 정보는 필요하지 않다.

## 2. 소스 디렉터리와 의존 관계

아래는 생성할 프로젝트의 제안 구조이며 현재 존재하는 파일 목록이 아니다. 처음에는 단일 Go module로 시작한다.

```text
kkdugi-runner/
  go.mod / go.sum
  cmd/kkdugi-runner/main.go
  internal/
    agent/             agent.go, worker.go, recovery.go, loops.go
    client/            client.go, protocol.go, errors.go
    config/            config.go, credentials.go
    program/           catalog.go, manifest.go
    workspace/         workspace.go, input.go, result.go
    executor/          executor.go, process_linux.go, process_windows.go
    state/             store.go, migrations/*.sql
    spool/             writer.go, chunk.go, retention.go
    service/           service.go, service_linux.go, service_windows.go
  testdata/            프로그램 manifest·API JSON fixture
  tests/              통합 테스트와 보조 실행 프로그램
  packaging/
    linux/             systemd unit, 설치 안내
    windows/           서비스 등록·중지·제거 스크립트
  configs/             비밀 값이 없는 설정 예시
```

| 패키지 | 책임과 경계 |
| --- | --- |
| `cmd` | CLI 해석, 설정 로드, 구현체 생성과 연결. 업무 상태 전이를 넣지 않음 |
| `agent` | 시작·복구·drain, 슬롯 예약, 배정 worker, API 호출 순서와 재전송 판단. 배정 상태 전이의 소유자 |
| `client` | API DTO, 인증·세션 헤더, 직렬화, HTTP timeout과 오류 해석. 독자적으로 새 멱등 key를 생성하거나 업무를 재시도하지 않음 |
| `config` | TOML 검증, 경로 정규화, 보호된 credential 파일 읽기·쓰기. 초기에는 설정 변경 후 재시작 |
| `program` | 프로그램 코드/revision 조회, 고정 argv와 파일 digest 검증, 설치 보고 자료 생성. 다운로드·자동 업데이트는 제외 |
| `workspace` | 배정별 디렉터리, 입력·컨텍스트 파일, secret 참조 해석, 결과 파일 크기·JSON 검증 |
| `executor` | 준비된 실행 명세로 한 번 실행, 프로세스 식별, 종료 대기, 취소·timeout에 따른 OS별 종료. HTTP·SQLite를 모름 |
| `state` | 로컬 migration, 짧은 트랜잭션, journal과 요청·ACK 저장. Admin 상태를 독자적으로 결정하지 않음 |
| `spool` | 마스킹·UTF-8 chunk 생성, 영속 파일 보관, ACK 후 정리. 업로드 호출은 agent의 전용 루프가 담당 |
| `service` | OS 서비스의 시작·중지 요청을 agent 수명 주기로 변환. foreground `run`도 같은 agent를 사용 |

의존 방향은 `cmd/service → agent → client/program/workspace/executor/state/spool`을 기본으로 한다. 하위 패키지가 agent를 호출하는 순환 의존은 만들지 않는다. API DTO는 `client`에 두고 실행 명세·프로세스 관측값은 `executor`에 둔다. Agent가 둘을 변환한다.

테스트 대역이 필요한 HTTP, executor, journal, 시간 경계에 작은 인터페이스를 둔다. 인터페이스는 소비하는 쪽에 정의하고 실제 동작이 없는 추상 계층·범용 plugin framework를 먼저 만들지 않는다. Agent는 완료를 추정하는 `IsAlive(pid)` 하나 대신 실행 식별·관측 신뢰도·종료 증거를 구분하는 결과를 받는다.

## 3. CLI와 설치 후 디렉터리

| 명령 | 동작 |
| --- | --- |
| `kkdugi-runner register --config <path>` | 일회성 토큰으로 등록하고 ACCESS credential을 보호 파일에 저장. 토큰은 표준 입력 등으로 받아 명령행 인자·로그에 남기지 않음 |
| `kkdugi-runner verify --config <path>` | 설정·프로그램 경로·digest·쓰기 권한·로컬 schema 호환성 검사. 업무 프로그램 실행이나 새 배정 없이 결과 출력 |
| `kkdugi-runner run --config <path>` | foreground 실행. 서비스에서도 같은 초기화·실행 로직 사용 |
| `kkdugi-runner version` | runner 버전, 빌드 정보, 지원 protocol version 출력 |

서비스 등록·제거는 최초에는 배포 스크립트로 제공한다. `verify`는 실행 중인 DB의 migration이나 복구 상태를 변경하지 않는다. 서비스 계정과 대화형 실행 계정이 다를 수 있으므로 최종 검증은 실제 서비스 계정에서도 수행한다.

```text
<config-dir>/runner.toml
<config-dir>/programs/*.toml
<protected-dir>/credential.json
<protected-dir>/secrets/...
<data-dir>/runner.lock
<data-dir>/state.db                 WAL 등 SQLite 부속 파일 포함
<data-dir>/work/<assignmentId>/     input.json, context.json, result.json
<data-dir>/spool/<assignmentId>/    stdout/..., stderr/...
<log-dir>/runner-*.jsonl            runner 자체 진단 로그
```

경로는 OS별 기본값을 제공하되 설정에서 바꿀 수 있게 한다. 프로그램 실행 파일은 운영자가 별도 배포하며 위 작업 폴더에 복제·다운로드하지 않는다. TOML은 admin 주소, 경로, 로컬 동시 실행 한도, 통신 timeout, drain 대기 시간, 디스크 상한을 담는다. API에서 받은 용량·크기 상한보다 로컬 설정을 높여 권한을 확장할 수 없다.

Identity 하나에 state 디렉터리 하나를 사용하고 **OS가 유지하는 단일 인스턴스 잠금**을 잡는다. lock 파일의 존재만으로 실행 여부를 판단하지 않는다. 같은 identity/credential을 다른 머신·data 디렉터리에 복사하여 동시에 사용하지 않는다.

## 4. 부팅과 복구 순서

1. 설정·credential을 읽고 단일 인스턴스 잠금을 획득한다. 로컬 DB 버전을 확인하고 지원되는 migration을 적용한다. 호환되지 않으면 배정을 받지 않고 종료한다.
2. journal과 spool을 검사한다. 미해결 배정, 전송 중이던 요청, 손상·부분 기록 파일을 수집한다. DB 자체가 손상되었으면 새 빈 DB를 만들어 자동 등록하지 않고 복구가 필요한 상태로 중지한다.
3. 현재 프로세스의 bootId를 만들고 세션 개설 요청을 전송 전에 영속 기록한다. **직전 프로세스의 세션 개설 응답이 유실된 경우**, 저장된 이전 bootId/expectedSession 요청으로 결과를 먼저 확인한 뒤 현재 bootId의 새 세션을 연다. 이전 요청을 재전송한 프로세스가 이전 실행을 시작하는 것은 아니다.
4. 세션 결과를 저장하고 heartbeat를 `DEGRADED`, `freeSlots=0`으로 시작한다. Admin의 미해결 배정 목록과 로컬 journal을 대조한다. 목록에서 빠진 로컬 배정도 상세 조회하여 완료·로그 ACK를 확인한다.
5. 이전 세대 배정은 관측 증거를 바탕으로 reconcile한다. 미해결 HTTP 요청을 새 세션 헤더로 무작정 재생하지 않는다. 먼저 조회·reconcile로 소유권과 의미상 처리 여부를 확인하고, 필요한 후속 보고는 현재 세션의 새 요청으로 저장한다.
6. 프로그램 설치 상태를 보고한다. 사라진 설치는 MISSING으로 보고하고, 새 revision은 admin 승인 전에는 실행 대상으로 사용하지 않는다.
7. 복구 대조가 끝나고 통신·디스크·승인 상태가 충족되면 `ACCEPTING`으로 전환한다. HOLD로 확인된 배정은 계속 슬롯을 점유한다. 대조 자체가 끝나지 않은 배정이 있으면 새 claim을 시작하지 않는다.

세션 409에서 서버 세대를 추측해 반복 증가시키지 않는다. 로컬 세대 정보 유실은 관리자 복구 대상이다. 등록 응답의 토큰을 저장하지 못했을 때도 성공으로 표시하지 않고 재발급 절차가 필요함을 알린다.

재시작 뒤 PID가 존재한다는 사실만으로 이전 실행이라고 인정하지 않는다. 원래 bootId, OS 프로세스 생성 식별 정보, 관리 범위를 함께 확인한다. **프로세스 존재 확인과 종료 코드·로그 수집 복구는 다른 능력**이다. 초기 구현은 신뢰할 수 있는 관측을 복원하지 못하면 UNKNOWN으로 보고한다. `CONTINUE_EXISTING`을 지원하는 OS adapter도 기존 실행만 관측하며 새 프로세스를 만들지 않는다.

## 5. 실행 worker와 병렬 처리

worker 하나는 assignmentId 하나만 맡는다. 같은 Run의 재시도를 로컬에서 만들지 않는다. 같은 Job의 서로 다른 Run은 admin의 `overlap=ALLOW`, Job `parallelLimit`, runner/설치 capacity를 만족할 때 각기 다른 worker와 OS 프로세스로 실행할 수 있다.

| 단계 | 영속 기록·행동 | Heartbeat phase |
| --- | --- | --- |
| `RECEIVED` | 배정 snapshot 저장, 로컬 슬롯 점유 | ASSIGNED |
| `PREPARED` | 승인 revision 검증, 입력·작업 폴더 준비 | ASSIGNED |
| `START_REQUESTED` | 시작 요청의 key·본문 저장 후 API 호출 | STARTING |
| `PERMITTED` | 허가 응답과 startBefore 저장 | STARTING |
| `START_INTENT` | 실행 의도를 커밋한 뒤 같은 worker에서 OS start를 한 번 호출 | STARTING |
| `RUNNING` | 프로세스 식별·시작 시각 저장, started 보고 예약, 종료 대기·로그 수집 | RUNNING |
| `STOPPING` | 종료 원인·요청 기록, OS 정상 종료 후 필요하면 강제 종료 | STOPPING |
| `FINISHED` | 종료 확인, 로그 수집 마감, 결과 검증 후 불변 완료 본문과 전송 요청을 함께 커밋 | FINISHED |
| `ACKED` | 완료 접수 또는 상세 조회로 동일 결과 확인, 실행 슬롯 해제 | 실행 목록에서 제외 |
| `UNKNOWN` | 실행·종료 증거가 부족함. reconcile 및 운영 확인 대기 | UNKNOWN |

이는 **runner 내부 단계**이며 admin의 Run/Attempt 상태 enum을 추가하는 것이 아니다. 시작 전 검증 실패·취소는 실행하지 않았다는 증거와 함께 FINISHED로 갈 수 있다. `START_INTENT` 이후 복구한 worker는 어떤 조회 결과에서도 start를 반복하지 않는다.

슬롯·배정 목록은 agent의 단일 조정 루프가 변경하고 worker는 이벤트를 보낸다. 조정 루프에서 HTTP나 프로세스 종료를 기다리지 않는다. 이벤트 전달은 용량을 제한하되 종료 이벤트를 버리지 않으며 로그 데이터는 이 큐를 통과시키지 않는다. SQLite 기록 실패 시 새 실행을 중지하고 기존 실행의 관측·종료 제어를 유지한다.

Claim은 동시에 하나만 요청한다. 요청 중 임시 슬롯을 예약하고 응답 유실 시 같은 요청을 해결하기 전 다른 claim을 보내지 않는다. 완료 ACK 전에는 프로세스가 종료했어도 로컬 슬롯을 보수적으로 유지한다. ACK 후 로그만 남은 배정은 슬롯을 점유하지 않는다. 단, 전체 디스크 한계에 도달하면 신규 수신을 중단한다.

각 worker는 자신의 `Cmd.Dir`, argv, 환경 변수를 가진다. 전역 `chdir`나 `Setenv`로 실행 환경을 바꾸지 않는다. `work/<assignmentId>` 아래 입력·결과·임시 파일을 분리하고 기존 다른 배정의 결과를 읽지 않는다.

현재 API의 `manifest.workingDirectory`는 그대로 프로세스의 작업 디렉터리로 사용한다. 따라서 **배정 폴더 분리만으로 프로그램의 상대경로 출력까지 격리되지는 않는다.** 병렬 프로그램은 아래 작업 경로를 사용하거나 업무 key별 경로를 자체 관리해야 한다. 고정 파일명·공유 파일을 사용하는 프로그램의 설치 capacity는 검증 전까지 1로 둔다.

### 프로그램에 전달하는 로컬 실행 계약

| 환경 변수 | 내용 |
| --- | --- |
| `KKDUGI_INPUT_FILE` | 최종 업무 입력 JSON 파일 절대경로. 기존 API 계약 |
| `KKDUGI_RESULT_FILE` | 선택적 결과 JSON을 쓸 절대경로. 기존 API 계약 |
| `KKDUGI_WORK_DIR` | 배정 전용 쓰기 디렉터리. 이번 구조 설계에서 제안하는 로컬 계약 |
| `KKDUGI_CONTEXT_FILE` | runId, assignmentId, attempt, 최초 실행 session, businessKey를 담은 JSON 파일 경로. 이번 설계의 로컬 계약 |

컨텍스트는 업무 입력과 분리하고 복구 시 기존 프로세스의 파일을 새 세션으로 덮어쓰지 않는다. Secret은 manifest의 승인된 이름을 현지에서 해석하여 해당 자식 프로세스에만 제공한다. Runner ACCESS 토큰을 자식 환경에 전달하지 않는다. 결과 파일이 없으면 `result=null`; 파일이 존재하지만 크기·JSON 객체 형식 검증에 실패하면 결과 수집 실패로 기록하는 것을 기본안으로 한다. 종료 코드가 0이어도 이 경우 outcome은 FAILED이며 업무 효과가 없었다는 뜻은 아니다.

## 6. 통신 루프와 중지 정책

| 루프 | 동작 |
| --- | --- |
| Heartbeat | 현재 상태 snapshot만 전송. 응답의 STOP·RECONCILE을 worker/복구 담당으로 전달 |
| Claim | 정상 상태·빈 슬롯에서 요청. 204면 다음 주기에 새 key, 응답 불명이면 기존 key 해결 |
| Report | 영속 요청에서 started·completion 등 전송. 같은 배정의 순서와 선행 응답을 확인하며 다른 배정은 독립 처리 |
| Log upload | 영속 chunk를 배정별로 번갈아 전송, 낮은 동시성으로 제어 트래픽 보호 |
| Maintenance | ACK 반영 후 파일 정리, spool 용량 검사, journal 보관 정리. 긴 정리 작업으로 상태 기록을 막지 않음 |

API timeout, 제한된 재시도 간격과 jitter, `Retry-After`를 적용한다. HTTP 500/503·응답 유실은 기존 요청의 key·본문·작성 시각을 유지한다. 401은 새 claim을 중단하고 credential 복구를 기다린다. 409/410은 단순 무한 재전송하지 않고 오류 종류에 따라 조회·reconcile 또는 운영 확인으로 넘긴다. Heartbeat와 claim의 반복 polling, 동일 업무 요청의 재전송을 구분한다.

통신 단절·lease 만료는 새 시작을 차단한다. 기존 실행은 관측과 로컬 timeout 집행을 계속하고 연결 복구 후 reconcile한다. lease 만료 자체를 프로세스 종료나 업무 실패로 기록하지 않는다. 향후 연결 단절 시 즉시 중지 정책이 필요하면 별도 실행 정책으로 추가한다.

프로세스 timeout은 실행 시점의 로컬 경과 시간으로 관리한다. 재시작으로 그 기준을 잃으면 남은 시간을 새 전체 timeout으로 초기화하지 않는다. 저장된 시작 시각·관측을 보수적으로 대조하고 신뢰할 수 없으면 복구 대상으로 둔다.

서비스 중지는 `DRAINING → 새 claim 중지 → 진행 중 claim 정리 → 기존 worker 종료 대기 → 남은 프로세스 종료 요청 → journal 저장 → 종료` 순서다. Drain에 들어간 뒤 아직 시작하지 않은 배정은 새로 실행하지 않고 미시작 증거를 바탕으로 reconcile한다. Drain 제한에 도달한 실행의 중지는 사용자 취소·Job timeout과 구분하여 기록한다. 확인된 종료는 FAILED와 서비스 중지 사유로 보고하고, 확인되지 않으면 UNKNOWN이다. 이미 관측한 성공은 유지한다.

Drain 중에도 heartbeat·완료·로그 전송은 가능한 동안 유지한다. 수신 중단 context와 실행/보고 context를 분리하여 claim 중지가 모든 자식 프로세스를 즉시 kill하지 않도록 한다. OS 서비스 종료 제한과 drain 설정은 함께 맞춘다. 정상 종료 시 ACK를 받지 못한 결과는 다음 기동에서 재전송한다.

## 7. 로컬 SQLite와 파일 영속성

Admin 테이블과 구분해 로컬 테이블은 `kkdugi_runner_*` prefix를 제안한다. `domains.xml`은 Oracle 기준 admin 논리 도메인의 참조이며 **SQLite에 Oracle/PostgreSQL 타입을 복사하지 않는다.** API ID 길이·값 범위는 애플리케이션에서도 검증한다. 아래는 저장 구조이며 최종 컬럼 DDL은 구현 단계에서 작성한다.

| 로컬 테이블 | 저장 내용·주요 키 |
| --- | --- |
| `kkdugi_runner_meta` | schema version, runnerId, 확정 session, 현재 bootId, 미완료 세션 개설 요청. key/value |
| `kkdugi_runner_assignment` | assignmentId PK, Run/프로그램 snapshot, 소유 session, 내부 단계, 시작 허가·의도, 프로세스 식별, 종료 증거, 불변 완료 본문·hash, 완료 ACK |
| `kkdugi_runner_request` | 요청 ID PK, assignmentId 선택, method/path/key, 작성 시각·session·본문·hash, 전송 상태·다음 시각. 인증 토큰은 저장하지 않음 |
| `kkdugi_runner_log_stream` | (assignmentId, stream) PK, 다음 순번, 연속 ACK, 마지막 확정 순번, COMPLETE/TRUNCATED/LOST |
| `kkdugi_runner_log_chunk` | (assignmentId, stream, sequence) PK, 상대 파일 경로, UTF-8 byte 수, digest, emittedAt, 영속·전송 상태 |

ID·session·JSON 본문·UTC 시각은 TEXT, 카운터·순번·크기·플래그는 INTEGER를 기본으로 하고 protocol의 범위를 넘지 않게 한다. Session처럼 문자열로 저장한 숫자는 사전식 정렬로 대소 비교하지 않는다. 로그 본문은 SQLite 밖에 보관하고 DB에는 참조만 둔다. 업무 입력·결과가 들어 있으므로 state와 work도 credential과 마찬가지로 서비스 계정에 맞는 접근 권한을 설정한다.

초기 DB 접근은 단일 작성 경로와 짧은 트랜잭션으로 직렬화한다. HTTP, 프로세스 start/wait, 큰 파일 I/O를 트랜잭션 안에서 수행하지 않는다. 요청을 전송하기 전에 요청 자체와 관련 상태를 커밋하고, 응답 반영과 다음 상태·요청 예약을 함께 커밋한다.

로그는 `읽기 → secret 마스킹 → UTF-8 경계에 맞춰 chunk 확정 → 파일 영속화 → chunk 메타데이터 커밋 → 업로드` 순서다. 마스킹은 읽기 경계에 걸친 값도 고려하며 chunk의 byte·digest·시각은 확정 이후 재전송에서 바꾸지 않는다. 비 UTF-8 출력의 처리 정책은 초기에는 잘못된 시퀀스를 대체 문자로 변환하는 안으로 테스트한다.

Chunk 임시 파일과 확정 파일을 구분하고 flush·rename·디렉터리 변경의 내구성은 OS adapter에서 검증한다. 재기동 시 DB 참조가 없는 파일은 식별 정보·digest로 대조하고 곧바로 업로드하지 않는다. DB가 가리키는 파일이 없으면 로컬 유실로 판단한다. ACK는 먼저 DB에 저장한 뒤 해당 파일을 삭제한다. 장애로 삭제가 늦어지는 쪽을 택하고 ACK 전에 지우지 않는다.

프로세스가 끝나면 stdout/stderr EOF 또는 제한된 수집 종료를 확인하고 마지막 chunk와 결과를 확정한 뒤 completion 본문을 만든다. 로그 전송 성공까지 기다릴 필요는 없다. 완료 본문 생성 이후 마지막 순번·상태를 변경해 같은 완료를 다시 보내지 않는다.

시도별 API 로그 한도와 전체 로컬 spool 한도를 모두 집행한다. 로그 한도 초과 후에도 pipe를 계속 읽고 버려 프로그램이 출력 대기로 멈추지 않게 하며 TRUNCATED로 표시한다. 상태·완료 저장에 필요한 디스크 여유를 우선 확보하고 부족하면 신규 claim을 중단한다. 미확인 완료·UNKNOWN journal과 미ACK chunk를 단순 보관 기간 만료로 삭제하지 않는다.

## 8. 장애 지점별 기대 동작

| 장애 지점 | 복구 기준 |
| --- | --- |
| Claim 응답 유실 | 같은 세션에서는 저장된 동일 요청으로 재조회. 재기동 후에는 미해결 목록·journal 대조와 reconcile. 새 claim으로 덮지 않음 |
| 시작 허가 응답 유실 | 허가만으로 실행했다고 판단하지 않음. 같은 세션에서 조회하더라도 유효 허가·미기록 시작 의도를 모두 확인 |
| START_INTENT 커밋 후 start 전/직후 crash | 실행 여부가 불명확하므로 자동 start 금지. 신뢰할 수 있는 관측이 없으면 UNKNOWN |
| 실행 종료 후 완료 journal 커밋 전 crash | 프로세스가 없다는 이유로 성공·실패를 추정하지 않음. 검증 가능한 종료 증거가 없으면 UNKNOWN |
| 완료 접수 후 응답 유실 | 저장된 완료 재전송 또는 상세 조회로 동일 결과 확인. 새로운 실행·새 완료 본문을 만들지 않음 |
| Chunk 파일 기록 후 DB 커밋 전 crash | 임시/고아 파일 대조, 아직 등록되지 않은 byte를 임의의 새 순번으로 중복 전송하지 않음 |
| 로그 ACK 후 로컬 파일 삭제 전 crash | 영속 ACK 기준으로 정리 재개. 필요 시 동일 chunk 재전송은 API 복합키로 멱등 |
| 완료 본문 확정 전 로그 유실 | 실제 남은 범위와 LOST 상태로 완료 본문 확정 |
| 완료 본문 확정 후 미ACK 로그 유실 | 기존 완료를 변경하지 않고 운영 오류를 남김. 현재 v1에는 사후 로그 유실 확정 API가 없으므로 별도 계약 보완 대상 |

초기에는 별도 supervisor 프로세스를 추가하지 않는다. 재시작 이후에도 종료 코드와 로그를 반드시 회수해야 한다는 운영 요구가 생기면 실행별 supervisor/영속 출력 방식의 비용과 복구 정확성을 테스트한 뒤 확장한다. 현재 설계는 UNKNOWN 처리를 통해 불명확한 실행의 중복 시작을 막는다.

## 9. 구현과 테스트 순서

| 단계 | 구현 범위 | 통과 기준 |
| --- | --- | --- |
| 1. 로컬 실행 | CLI run/verify, config, program, workspace, foreground executor | 단일 프로그램의 입력·출력·종료 코드, 공백·한글 경로, 취소·timeout, 자식 프로세스 종료 |
| 2. 통신 연결 | client, agent, 가짜 admin, claim/start/started/completion | JSON 계약 fixture, 정상 단일 실행, 응답 유실·중복 응답에도 같은 배정 start 호출 1회 이하 |
| 3. 영속 복구 | SQLite, 영속 요청, session, reconcile | 실제 runner 강제 종료를 각 journal 경계에 주입, 재기동 후 중복 실행 차단·UNKNOWN 유지 |
| 4. 병렬·로그 | 다중 worker, spool, upload, 용량 제한 | 같은 프로그램의 서로 다른 입력 동시 실행, 경로·결과 혼선 없음, 로그 폭주 중 heartbeat·취소 정상 처리 |
| 5. 실서비스 | 실제 admin 연동, OS 서비스 설치·중지·업데이트 | 세션 경합, 서버 장애, 디스크 부족, 서비스 재시작, 실제 DB 상태와 runner journal 일치 |

단계 1부터 작은 보조 프로그램으로 정상 종료, 비정상 종료, 무한 대기, 대량 출력, 자식·손자 생성 사례를 실행한다. Go 단위 테스트는 시간·HTTP·executor 대역으로 재현하고 OS 동작은 지원 대상별 실제 통합 테스트로 확인한다. `go test ./...`, `go vet ./...`, 지원 환경의 race 검사를 수행하며 컴파일 성공만으로 다른 OS 지원을 선언하지 않는다.

Linux/Windows를 분리할 구조는 유지하되 최초 운영 OS와 버전·CPU 범위는 아직 확정하지 않았다. 해당 환경의 프로세스 관리·서비스 종료·SQLite 내구성 검증이 끝난 조합부터 지원 대상으로 표시한다. Admin과 함께 테스트할 때는 기존 Java·JS 테스트도 수행한다.

이번 문서는 설계 결과이며 위 테스트를 이미 통과했다는 의미가 아니다. 최초 구현은 단계 1의 실행 경로부터 만들고, 각 단계에서 발견한 API 모호성은 [Runner API 계약](runner-api.md)에 함께 반영한다.
