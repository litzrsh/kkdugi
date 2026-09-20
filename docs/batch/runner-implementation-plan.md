# Runner 실제 구현 계획

- 작성일: 2026-09-20
- 기준: [프로그램 구조](runner-structure.md), [Runner API v1](runner-api.md), [기술 스택](runner-tech-stack.md).
- 작업 위치: 이 저장소의 `kkdugi-runner/`를 독립 Go module로 만든다. Admin Java 모델을 공유하지 않으며 나중에 별도 저장소로 분리할 수 있게 한다.
- 진행 방식: 아래 순서대로 구현하고 각 단계의 테스트 결과를 기록한다. 앞 단계의 실패를 숨긴 채 다음 단계를 완료 처리하지 않는다. 운영 배정 기능은 영속 상태·시작 의도 기록을 구현한 뒤 연결한다.

## 1. 단계별 작업과 완료 기준

| 순서 | 구현 내용 | 선행 조건 | 완료 기준 |
| --- | --- | --- | --- |
| R0 | Go module, Ollama Go profile, 설정·manifest 명세, CLI version/verify | 없음 | Go 빌드, 엄격한 TOML 검증, 잘못된 설정·경로·revision 거절, Ollama 기존 Java 모드 회귀 테스트 |
| R1 | 배정별 workspace, JSON 입력·결과, foreground executor | R0 | 실제 보조 프로그램으로 입출력·종료 코드·timeout·취소·자손 종료·동시 실행 격리 검증 |
| R2 | HTTPS client, protocol DTO, 등록·credential 저장 | R0 | TLS 검증, redirect 제한, 숫자 문자열·크기 제한·오류 코드·인증 헤더 계약 테스트. 토큰 출력 금지 |
| R3 | SQLite migration, 단일 인스턴스 잠금, journal·영속 요청 | R0 | 커밋 전후 장애 주입, 요청 key/body 보존, 다른 세션 재전송 차단, 중복 기동 거절 |
| R4 | 프로그램 catalog와 설치 보고·승인 상태 | R2·R3 | 같은 revision 내용 변경 거절, MISSING 보고, 미승인·불일치 프로그램 실행 차단 |
| R5 | Agent, session, claim/start/started/completion, 슬롯 관리 | R1~R4 | 가짜 admin과 정상 1회 실행, 응답 유실에도 배정당 start 호출 1회 이하, 완료 ACK 전 슬롯 유지 |
| R6 | 영속 로그 spool, 마스킹, 제한·ACK·재전송 | R3·R5 | UTF-8/마스킹 경계, 대량 stdout/stderr, 순서 역전·중복 ACK, 디스크 부족 시 신규 claim 차단 |
| R7 | 재시작 reconcile, admin 장애 복구, UNKNOWN 처리 | R5·R6 | start 전후 강제 종료, admin 중단 중 작업 완료, 복구 후 결과 접수, 불명 실행 자동 재시작 금지 |
| R8 | 병렬 실행, drain, OS 서비스·설치·업데이트 | R7 | capacity 준수, 시작하지 않은 배정 drain, 서비스 계정 권한·종료·복구, Linux/Windows 각 환경 테스트 |
| R9 | 실제 admin 연동·운영 검증 | Admin 배치 API 구현 + R8 | 수동·예약 실행, 취소·재시도, 장애 시 이중 배정 차단, API·DB·journal 일치. 지원 OS별 설치 검증 |

R2와 R3는 논리적으로 독립이지만 첫 구현에서는 위 순서로 진행한다. 한 단계가 크면 아래 Ollama 작업 단위로 나누며 로컬 모델 호출은 항상 하나씩 수행한다.

R0~R1은 admin 승인 없이 업무를 실행하는 운영 모드가 아니다. Executor는 테스트에서 호출하고 CLI `run`의 실제 배정 루프는 R5에서 연결한다. 구현하지 않은 명령을 성공 응답하는 placeholder로 제공하지 않는다.

## 2. Ollama 작업 분담

[AGENTS.md](../../AGENTS.md)의 `scripts/ollama_implements.py`를 사용한다. 모델 이름은 기존 역할 매핑을 유지한다.

| 작업 | 담당 | 결과 적용 |
| --- | --- | --- |
| 계획·API 해석·패키지 경계·실행/복구 상태 전이 | 주 에이전트 | 직접 작성·검토 |
| 작은 Go struct·JSON/TOML tag·고정 검증 규칙·fixture | `coder` | 파일 단위로 생성, 주 에이전트가 오류 수정 후 적용 |
| 프로세스 수명 주기·입출력 같은 중요한 구현의 결함 검토 | `reviewer` | 근거 있는 지적을 코드·테스트에 반영하고 재검증 |
| 빌드·단위/통합 테스트·실제 실행 | 주 에이전트 | 모델의 자기 평가 대신 실행 결과 기록 |

기존 스크립트는 Java 시스템 프롬프트가 고정되어 있으므로 `--profile go`를 추가한다. 기본값은 Java로 유지하여 기존 호출을 바꾸지 않는다. 코드/문서 참조는 `-c`로 전달하고 모델 출력을 자동 실행하거나 검토 없이 파일에 덮어쓰지 않는다. 별도의 Go용 모델을 내려받지 않는다.

```text
python scripts/ollama_implements.py --role coder --profile go "한 파일의 구체적 요구사항" -c <참조 파일>
python scripts/ollama_implements.py --role reviewer --profile go "검토 범위와 불변 조건" -c <구현 파일>
```

Reviewer 실행 중에는 다음 Ollama 요청을 시작하지 않는다. `EXIT_BUSY=3`이면 lock을 삭제하지 않고 기존 요청 완료를 기다린다. 모델 답변 원문은 임시 산출물로 두고 채택 내용·수정 이유·검증 결과를 이 문서에 요약한다.

## 3. 테스트 전략

- Go: `go test ./...`, `go vet ./...`, 실행 파일 빌드. 지원되는 환경에서 `go test -race ./...` 추가. Cross compile 결과와 해당 OS에서 실제 실행한 결과를 구분한다.
- 실제 프로세스: 외부 JRE/Python 설치가 없어도 테스트할 수 있는 Go 보조 프로그램으로 JSON echo, exit 실패, sleep, 출력 폭주, 자식 프로세스 생성을 재현한다.
- 시간·HTTP: fake clock/`httptest`로 timeout·재전송·중복 응답을 재현한다. 프로세스 종료 검증에는 실제 OS 실행을 사용한다.
- 복구: 오류 반환만 흉내 내는 테스트와 별도로 runner 프로세스를 실제 강제 종료하고 동일 data 디렉터리로 재기동한다. START_INTENT 이후 start 횟수 증가가 없어야 한다.
- Admin: `kkdugi-admin/`에서 `./mvnw.cmd -B -ntp test`, `node --test src/test/js/*.test.mjs`. 기존 실패도 그대로 보고하고 runner 작업의 통과 결과와 구분한다.

## 4. 먼저 고정할 불변 조건

1. Runner는 Job/Workflow를 선택하거나 재시도 Attempt를 만들지 않는다.
2. 미승인 revision, 유효 시작 허가 부재, journal 커밋 실패에서는 새 프로세스를 시작하지 않는다.
3. Admin 단절 후 기존 실행은 관측·로컬 timeout을 계속하며 결과를 보관한다. lease 만료만으로 완료·실패·재배정을 결정하지 않는다.
4. 프로세스 트리 종료가 불명확하면 UNKNOWN이다. PID 소멸만으로 성공을 추정하지 않는다.
5. Assignment별 입출력과 자식 환경을 분리하고 secret·runner token을 진단 로그에 기록하지 않는다.
6. 완료/로그 전송 내용은 확정 후 불변이며 ACK 전 기록을 보관 기간만으로 삭제하지 않는다.

## 5. 실제 진행 기록

| 항목 | 상태·증거 |
| --- | --- |
| 계획 | 작성 완료 |
| 실행 환경 | Go 1.27.1 windows/amd64. 기존 AlmaLinux 10 WSL2에서도 Linux binary·테스트를 실제 실행. 최초 운영 OS·서비스 환경은 미확정 |
| Ollama | 로컬 서버와 `qwen2.5-coder:7b`, `qwen3.8:latest` 설치 확인 |
| R0 | 완료: Go module, 엄격한 설정·manifest 검증, CLI version/verify, 예시 TOML, Ollama Go profile |
| R1 | Windows 및 AlmaLinux 10 WSL2에서 구현·실행 테스트 완료: workspace, JSON 보존, 실제 프로세스·자손 제어. 운영 서비스 환경 검증은 R8에서 수행 |
| R2 | 완료: HTTPS client, 등록·세션 DTO, register CLI, 보호된 credential 저장. Windows 및 AlmaLinux 10 WSL2에서 TLS 테스트 서버로 검증 |
| R3 | 구현 완료: SQLite migration·journal·영속 요청, OS 단일 인스턴스 잠금, 세션 개설 복구. 상태 저장 API이며 CLI run 연결은 R5 |
| R4 | 구현 완료: 영속 catalog·revision 이력, 설치 PUT 보고·승인 ACK, 실행 전 파일·승인 대조. CLI 배정 루프 연결은 R5 |
| R5 | 구현 완료: `run`, 세션·catalog·heartbeat·배정 worker, 시작 허가·START_INTENT·완료 ACK, 슬롯·drain. Windows 및 AlmaLinux 10 WSL2에서 실제 프로그램 실행 검증 |
| R6~R9 | 미착수. 다음 단계는 영속 로그 spool·마스킹·전송. 실제 admin 배치 API는 아직 미구현 |

### R0~R1 검증 결과

- Go `go test ./...`, `go vet ./...`, Windows binary build 및 `version`/예시 설정 `verify` 통과.
- 실제 Windows 테스트: 공백·한글 executable/argv, 2^53 초과 JSON 정수 보존, 부모 환경의 비밀 값 미상속, 종료 코드 7, timeout, 자식·손자 취소, 부모만 종료한 프로그램의 자손 정리, 출력 저장 실패 중 pipe drain, 병렬 workspace 격리.
- Linux/amd64 `CGO_ENABLED=0` build 후 AlmaLinux 10 WSL2에서 4개 패키지의 테스트 binary와 `version`/`verify` 실제 실행 통과. Race 검사·서비스 설치는 미검증. 현재 환경에서 C compiler가 확인되지 않아 race 검사는 실행하지 않음.
- Ollama 스크립트 Python 테스트 6개 통과. Java 기본 profile, Go prompt 선택, 모델 매핑 유지, thinking 옵션, 빈 응답 실패 처리를 검증.
- Admin JS 테스트 55개 통과. Java는 296개 중 기존 `DefaultMenuSeedTest.everySeededMenu_hasJapaneseName` 1개 실패(`admin/user` 일본어 이름 누락), 나머지 295개 통과. Runner 변경으로 해결했다고 간주하지 않음.

### R2 검증 결과

- Go `go test -timeout 90s ./...`, `go vet ./...`, Windows 실행 파일 빌드 통과. Linux/amd64로 빌드한 7개 패키지 테스트를 AlmaLinux 10 WSL2에서 실제 실행하여 통과.
- TLS 신뢰 체인·호스트명 검증, 사설 CA, redirect 차단, 인증·세션·멱등 헤더, bigint 문자열, 응답 크기·형식·필수 필드, HTTP 오류와 응답 유실 시 재전송 금지를 검증.
- 실제 등록 CLI와 credential 저장을 TLS 테스트 서버에 연결하여 검증. 동시 등록 중 단일 요청 허용, pending 기록의 재기동 후 보존, 기존 credential 덮어쓰기 금지, Linux 파일 권한·Windows ACL, symlink/reparse 경로 거절을 포함.
- Admin JS 55개 통과. Java 296개 중 `DefaultMenuSeedTest.everySeededMenu_hasJapaneseName` 1개 실패(이번 실행에서는 `admin/menu` 일본어 이름 누락), 나머지 295개 통과. 기존 실패이며 admin 코드는 변경하지 않음.
- 실제 admin 배치 API 연동, race 검사, 운영 서비스 환경은 아직 검증하지 않음.
- `coder --profile go`에 [R2 계약](runner-r2-contract.md)을 참조로 전달하여 등록·세션 DTO를 생성. 필드·JSON tag를 직접 대조한 뒤 적용했으며 통신·저장·검증 로직과 테스트는 주 에이전트가 작성.

### R3 검증 결과

- Go 8개 패키지 `go test -timeout 90s ./...` 통과. Windows/Linux `go vet ./...` 및 빌드 통과. Linux의 state·credential 테스트 바이너리를 WSL에서 실제 실행하여 통과.
- `internal/state`에 `modernc.org/sqlite v1.59.0`, WAL·FULL, 전용 연결, embed migration/checksum, identity 확인, request/journal/ACK 원자적 변경을 구현. [상세 계약](runner-r3-contract.md).
- Windows와 AlmaLinux 10 WSL2에서 실제 SQLite 파일 및 OS 잠금 테스트. 별도 프로세스를 트랜잭션 커밋 전후에 강제 종료하고 journal·요청·ACK가 전부 이전 상태 또는 전부 이후 상태인지 확인. 잠금 파일을 지우지 않아도 강제 종료 후 잠금을 다시 얻음.
- START_INTENT 동시 요청 8개 중 1개만 성공, 재기동 후 구세대 변경·전송 차단, 이전 bootId 세션 개설 요청 보존, 2^53 초과 세대와 JSON 정수 보존, 단일 claim, 완료 본문 불변, 지연 전송, ACK 멱등성을 검증.
- SQLite max_page_count로 SQLITE_FULL을 실제 발생시켜 부분 갱신 rollback과 이후 쓰기 중단을 확인. DB 유실·빈 파일·손상·미지원 버전·migration checksum·요청 hash 오류를 거절. Windows ACL 및 Linux 0700/0600 경계를 확인.
- Ollama coder가 생성한 모델에서 누락된 `Phase` 타입과 계약과 다른 상수 이름을 수정 후 적용. 상태 전이·트랜잭션·세션·잠금 로직과 장애 테스트는 직접 작성·검토.
- Admin JS 55개 통과. Java 296개 중 기존 `DefaultMenuSeedTest.everySeededMenu_hasJapaneseName` 1개 실패(이번 실행에서는 `admin/message` 일본어 이름 누락), 나머지 295개 통과. Admin 코드는 변경하지 않음.
- 운영 배정 루프(R5), 로그 영속화(R6), 구세대 reconcile(R7), 실제 정전·저장 장치 고장·서비스 계정 검증은 후속 범위. Race 검사는 C compiler가 없어 실행하지 않음.

### R4 검증 결과

- `internal/catalog`, 설치 보고 client DTO/PUT, state의 schema v2를 구현. [R4 계약](runner-r4-contract.md)에 사용 경계와 보고·승인 무효화 정책을 기록.
- Go 9개 패키지 테스트, Windows/Linux 정적 검사와 빌드 통과. Linux의 catalog·client·state 테스트 바이너리를 AlmaLinux 10 WSL2에서 실제 실행하여 통과.
- TLS 테스트 서버와 연결해 인증·세션·멱등 key·원문 본문 전송부터 승인 ACK·Resolve까지 확인. 실제 admin 연동은 후속 범위.
- 같은 revision의 version/manifest 변경·재시작 후 이력 덮어쓰기 거절, 전체 refresh rollback, 파일 삭제/MISSING·digest 불일치/INVALID, 잘못된 승인 응답·철회·재시작·보고 실패 후 실행 차단을 검증.
- 응답 유실 시 기존 요청 보존, 보고 중 설치 변경 시 과거 응답의 최신 설치 승인 차단, 이후 새 snapshot 보고를 확인. Schema v1→v2에서 영속 요청 보존과 DDL 실패 시 migration 전체 rollback을 검증.
- Ollama coder로 4개 wire DTO를 생성하고 필드·JSON tag를 대조하여 적용. Canonical 변환·영속성·승인 검증·파일 조사와 테스트는 직접 구현·검토.
- Admin JS 55개 통과. Java 296개 중 기존 `DefaultMenuSeedTest.everySeededMenu_hasJapaneseName` 1개 실패(이번 실행에서는 `admin/code` 일본어 이름 누락), 나머지 295개 통과. Admin 코드 변경 없음.
- `Resolve`는 로컬 실행 자격 검증이며 admin start 허가가 아니다. 실제 executor 연결·배정별 lease/취소 대조는 R5, race 및 운영 서비스 환경 검증은 미실행.

### R5 검증 결과

- [R5 계약](runner-r5-contract.md)에 CLI 사용법, 실행·통신 경계와 후속 제한을 기록. `internal/agent`가 기존 R1~R4를 연결하며 client DTO는 Ollama coder로 생성 후 검토·수정했다.
- Windows Go 10개 패키지 테스트, Windows/Linux `go vet ./...`와 빌드 통과. Agent·client·executor·credential·state의 Linux 테스트 바이너리를 AlmaLinux 10 WSL2에서 실제 실행하여 통과.
- TLS fake admin과 실제 보조 프로세스로 claim/start/started/completion 응답 유실 시 원래 멱등 요청 재전송 및 단일 실행, 완료 ACK 전 슬롯 유지, 로컬/서버 capacity의 작은 값 적용, 취소·timeout·만료 허가·승인 거절을 검증.
- Admin 단절 중 로컬 timeout·FINISHED 저장, 재연결 후 완료 ACK, drain 후 결과 보존, 이전 실행/서버 미해결 목록이 있을 때 신규 claim 거절을 확인. START_INTENT SQLite 쓰기를 실제 실패시켜 OS spawn이 발생하지 않는 것도 확인.
- 보호된 등록 credential·CA·secret 파일을 실제 `RunConfigured`에 연결하여 실행. 부모 비밀 환경 미상속, 큰 JSON 정수 보존, 비정상 종료·출력 JSON 오류도 검증.
- Admin JS 55개 통과. Java 296개 중 기존 `DefaultMenuSeedTest.everySeededMenu_hasJapaneseName` 1개 실패(`admin/menu` 일본어 이름 누락), 나머지 295개 통과. Admin 코드 변경 없음.
- 로그 bytes 저장·전송은 R6, 재시작 후 자동 reconcile은 R7. 실제 admin 연동·race 검사·운영 OS 서비스 검증은 아직 수행하지 않음.

### Ollama 활용과 발견한 문제

- `coder --profile go`: 로컬 설정 계약을 참조하여 `Config`/`Program`/`Manifest`/`FileDigest`를 생성. 필드·TOML tag를 직접 대조하고 gofmt 후 적용. 파싱·검증·프로세스 제어와 테스트는 주 에이전트가 작성.
- 첫 `reviewer` 호출이 본문 없는 응답을 반환했다. 기존 스크립트가 이를 성공으로 처리하는 문제를 수정하고 회귀 테스트를 추가했다. 검토 통과로 기록하지 않는다.
- `--think auto|true|false`를 추가했다. 기본값은 기존 모델 동작을 보존하며 명시적으로 false를 지정해 더 작은 Windows adapter 검토를 요청했다. [Ollama generate API의 thinking 옵션](https://docs.ollama.com/api/generate)
- 재검토 답변도 직접 검증했다. `runtime.KeepAlive`를 시스템 호출 앞으로 옮기라는 지적은 `go doc runtime.KeepAlive`의 공식 예제와 반대이므로 기각했다. 이미 있는 defer cleanup을 누락으로 지적했다가 번복한 내용도 적용하지 않았다. 모델 답변을 검토 통과의 증거로 사용하지 않으며 실제 테스트와 코드 대조를 기준으로 한다. 직접 점검한 실행 환경 문자열의 UTF-8 검증은 코드·회귀 테스트로 보강했다.
- Windows 병렬 테스트에서 process handle의 종료 신호와 Job Object accounting 갱신 사이 경합을 발견했다. 짧은 대조 시간을 둔 뒤 잔존 여부를 판정하도록 수정하고 실제 자손 잔존 사례도 별도로 테스트했다.

## 6. 현재 구성 요소 실행 방법

`kkdugi-runner/`에서 실행한다. `go`가 현재 터미널 PATH에 아직 없으면 새 터미널을 열거나 설치한 `go.exe`의 절대경로를 사용한다.

```text
go test ./...
go vet ./...
go build -o bin/kkdugi-runner.exe ./cmd/kkdugi-runner
bin/kkdugi-runner.exe version
bin/kkdugi-runner.exe verify --config configs/runner.windows.example.toml
```

설정·입출력 필드는 [로컬 구현 계약](runner-local-contract.md)을 따른다. 예시 `whoami.exe`/`id` 설정은 파일 검증을 위한 예시이며 admin 승인이나 실행 권한을 만들지 않는다. `register`는 [R2 계약](runner-r2-contract.md), `run --config <설정 파일>`은 [R5 계약](runner-r5-contract.md)에 따라 구현했다. 실제 admin 배치 API는 아직 미구현이므로 현재 실행 연동은 TLS fake admin으로 검증했다.

운영에서 R1 executor를 사용하기 전에 R3의 START_INTENT 영속화와 R7의 crash 복구를 연결해야 한다. Workspace는 서비스 계정이 소유한 신뢰된 루트 디렉터리를 전제로 하며 악의적인 동일 계정 프로세스를 격리하는 sandbox가 아니다. Windows는 console 없는 실행을 사용하고, 일반화된 graceful signal 대신 stop grace 이후 Job Object 전체를 종료한다. Linux는 process group을 벗어나는 daemon을 지원하지 않으며 cgroup 격리는 후속 검증 대상이다. [Windows 프로세스 관리 근거](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)

Go SDK는 설치된 1.27.1을 사용한다. [공식 배포 목록](https://go.dev/dl/)에서 확인한 버전이며 이후 의존성은 `go.mod`/`go.sum`에 고정한다.
