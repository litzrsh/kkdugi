# Runner 기술 스택

- 작성일: 2026-09-20
- 상태: **채택 (2026-09-20, 프로젝트 owner 승인)**. 초기 runner 구성 요소의 구현·검증을 시작했다. 범위와 미완료 단계는 [구현 계획](runner-implementation-plan.md)에 기록하며 서비스 설치 패키지와 성능 검증은 후속 작업이다.
- 채택 조합: **Go + 표준 HTTP/JSON + TOML + 로컬 SQLite + OS 서비스**.
- 지원 범위 검토 기준: Linux/Windows 모두 대응할 수 있는 구조, 최초 배포 후보는 linux/amd64와 windows/amd64. 실제 지원 OS 버전·CPU 범위는 운영 환경에 맞춰 확정한다.
- [전체 설계](README.md) / [실행·복구 규칙](execution.md) / [Workflow 진행 책임](workflow-extension.md)

## 1. 선정 기준

Runner는 머신에 설치하는 상주 에이전트다. 외부 프로그램을 실행하고 결과를 admin에 보고하는 역할에 집중한다.

1. 설치 머신에 runner용 JVM/Python 등의 언어 런타임을 별도로 요구하지 않는 배포.
2. 작업 수신, heartbeat, 로그 전송과 여러 프로세스 감시를 동시에 수행.
3. OS별 프로세스 트리 종료, 서비스 중지, 재시작 복구 지원.
4. Admin 연결 단절 때 미전송 결과·로그를 로컬에 보관.
5. Runner 업데이트와 업무 프로그램 배포를 독립적으로 수행.

Workflow 순서·분기·owner 승인은 admin이 판단한다. Runner에 Spring Batch, cron 스케줄러, Workflow 엔진을 넣을 필요는 없다. Java 프로그램 실행에 필요한 JRE나 Python 프로그램의 가상환경은 해당 **업무 프로그램의 의존성**으로 머신에 별도 설치한다.

## 2. 언어 비교와 선택 근거

아래 평가는 현재 요구에 대한 설계 판단이며 성능 벤치마크 결과가 아니다.

| 후보 | 이 프로젝트에서의 장점 | 고려할 비용 | 판단 |
| --- | --- | --- | --- |
| **Go** | OS별 실행 파일 배포, HTTP·동시성·프로세스 실행 표준 라이브러리, 에이전트 역할과 잘 맞음 | Java 외 언어 추가, OS별 프로세스 관리 코드는 별도로 구현 | **채택** |
| Java | Admin과 언어·개발 경험 통일, Java 라이브러리 활용 | JRE 포함 패키지 관리와 OS 서비스/프로세스 제어 연동 필요 | 팀의 Java 통일이 최우선이면 유효한 대안 |
| C# / .NET | self-contained 배포 가능, Windows 중심 서비스에 적합 | Linux까지 운영할 경우 양쪽 서비스·프로세스 정책 검증, 새 언어 추가 | Windows 전용이고 .NET 경험이 많다면 재검토할 대안 |
| Python | 간단한 실행·연동 로직 작성에 편리 | 인터프리터/의존성 관리 또는 실행 파일 패키징과 OS별 검증 필요 | 업무 스크립트 언어로 활용, runner 본체 우선안에서는 제외 |

GitLab Runner도 Go로 작성되어 단일 실행 파일로 배포된다. 유사한 설치형 runner의 선례이며, 자체 runner의 성능이나 안정성을 자동 보장하는 근거로 삼지는 않는다. [GitLab Runner 공식 문서](https://docs.gitlab.com/runner/)

Java도 `jpackage`로 런타임을 포함할 수 있고 .NET도 self-contained single-file 배포가 가능하다. 따라서 Go만 별도 런타임 설치를 피할 수 있다는 이유로 선택한 것은 아니다. 현재 runner의 좁은 역할과 배포 구성을 기준으로 Go를 선택했다. [Java 패키징](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html), [.NET 배포](https://learn.microsoft.com/en-us/dotnet/core/deploying/single-file/overview)

## 3. 채택 기술 조합

| 영역 | 채택 기술 | 적용 범위 |
| --- | --- | --- |
| 언어·빌드 | **Go 1.27 계열**, Go Modules | 조사 시점 최신 patch는 1.27.1. 구현 시작 시 지원 patch를 재확인하고 CI에서 고정 |
| 프로세스 실행 | `os/exec`, `context` + OS별 adapter | 고정 executable/argv 실행, 취소·timeout·종료 코드·stdout/stderr |
| OS API | `golang.org/x/sys` | Windows 서비스·Job Object, Linux 프로세스 제어에 필요한 OS 기능 |
| Admin 통신 | `net/http`, `crypto/tls`, `encoding/json` | HTTPS REST/JSON, runner 전용 인증키, polling·heartbeat·결과 보고 |
| 설정 | TOML, `github.com/pelletier/go-toml/v2` | runner 설정과 프로그램 설치 manifest, 엄격한 값 검증 |
| 로컬 영속 상태 | SQLite + `database/sql` + `modernc.org/sqlite` | 배정 journal, 시작 의도·프로세스 식별, 미확인 완료 보고, 로그 업로드 위치 |
| 업무 로그 | 크기를 제한한 로컬 chunk/segment 파일 | stdout/stderr spool, 업로드 재전송, ACK 후 정리 |
| Runner 자체 로그 | `log/slog` JSON | runner ID·Run ID·Attempt를 포함한 운영 진단 로그 |
| 상주 실행 | Linux systemd / Windows Service | 설치·시작·중지·재시작, 전용 서비스 계정 |
| CLI | 우선 표준 `flag`와 작은 subcommand dispatcher | register, run, verify, version; 복잡해지면 CLI 라이브러리 검토 |
| 검증 | `go test`, `go vet`, 지원 CI 환경의 race detector | 프로토콜·저장·프로세스·재시작/통신 장애 검증 |

Go patch 기준은 [공식 릴리스 이력](https://go.dev/doc/devel/release)을 확인했다. TOML은 [go-toml](https://github.com/pelletier/go-toml), Windows Service는 [x/sys/windows/svc](https://pkg.go.dev/golang.org/x/sys/windows/svc)를 사용한다. 외부 모듈의 정확한 버전은 초기 구현에서 호환성 검증 후 `go.mod`/`go.sum`에 고정한다.

별도 web framework, ORM, PostgreSQL/Redis client는 초기 runner에 필요하지 않다. Admin DB에 직접 접근하지 않고 runner API만 호출한다. 완료 이벤트도 최초에는 HTTPS 결과 보고이며 Kafka/RabbitMQ 도입을 전제로 하지 않는다.

## 4. 로컬 SQLite를 두는 이유

메모리 상태만 있으면 runner가 재시작했을 때 이미 실행한 배정인지, 결과를 보고했는지 알 수 없다. TOML은 운영 설정이고 SQLite는 실행 중 변하는 복구 상태이므로 역할을 구분한다.

- `modernc.org/sqlite`는 CGo-free SQLite 드라이버이며 별도 DB 서버를 요구하지 않는다. Linux/Windows amd64 등 목표 조합의 지원은 고정할 모듈 버전에서 재확인한다. [드라이버 문서](https://pkg.go.dev/modernc.org/sqlite)
- 로컬 디스크의 전용 state 디렉터리에 두고, 네트워크 공유 파일시스템은 최초 지원 범위에서 제외한다.
- 초기에는 단일 DB 작성 경로와 짧은 트랜잭션을 사용한다. WAL 및 `synchronous=FULL`을 기본안으로 삼고 연결별 적용·busy timeout·checkpoint를 검증한다. SQLite 설정만으로 모든 저장 장치 장애를 해결한다고 가정하지 않는다. [SQLite 동기화 설정](https://sqlite.org/pragma.html#pragma_synchronous)
- SQLite에 바이너리 로그를 계속 쌓지 않는다. 로그는 파일에 쓰고 DB에는 파일 식별·순번·ACK 위치를 둔다. 파일 flush와 DB 기록 순서, 장애 후 부분 파일 검사를 정의해야 한다.
- SQLite와 OS 프로세스 실행을 하나의 트랜잭션으로 묶을 수는 없다. 실행 전 journal을 기록하고 복구 시 프로세스 식별과 admin 배정을 대조한다. 불명확하면 UNKNOWN이며 무조건 재실행하지 않는다.
- 로컬 DB는 admin 실행 이력의 복제본이나 독립적인 작업 큐가 아니다. 새로운 실행 권한은 admin이 발급한다. ACK 전 상태를 보존하고 디스크 부족 시 신규 수신을 중단한다.

단순 JSON 파일 journal도 가능하지만 상태 변경·완료 재전송·로그 ACK 갱신을 함께 관리해야 하므로 SQLite 트랜잭션을 선택하는 안이다. bbolt 같은 KV 저장소도 가능하지만 이 설계에서는 복구 상태 조회와 migration을 SQL로 관리하는 쪽을 택한다.

`domains.xml`과 `kkdugi_batch_*`는 admin PostgreSQL 스키마의 기준이다. Runner의 내부 SQLite 스키마는 별도 문서로 정의하며 Oracle/PostgreSQL 타입을 그대로 복사하지 않는다. admin이 발급한 ID·프로토콜 필드의 의미와 값 범위는 유지한다.

## 5. 프로세스 제어는 OS별로 구현한다

`os/exec.CommandContext`의 기본 취소는 해당 프로세스의 Kill을 호출한다. 이를 호출했다고 모든 자손 프로세스 종료가 보장된다고 가정하지 않는다. 명령 실행 공통 로직과 OS별 프로세스 제어를 분리한다. [Go os/exec](https://pkg.go.dev/os/exec)

| OS | 제어 설계 |
| --- | --- |
| Linux | 실행별 process group을 만들고 정상 종료 신호 후 강제 종료. 새 session으로 빠져나가는 daemon까지 포함하려면 실행별 cgroup 등의 관리 범위가 필요하므로 지원 범위를 명시하고 검증 |
| Windows | 실행별 Job Object에 프로세스를 넣어 자손을 관리. 자식 생성 전에 관리 범위에 들어가도록 생성 절차를 구현하고, 종료·handle 상속·breakaway 정책 검증 |

Windows Job Object는 연관 프로세스를 묶어 관리할 수 있으며 `KILL_ON_JOB_CLOSE` 동작은 마지막 handle이 닫히는 조건에 따른다. 기능 사용만으로 서비스 재시작 복구가 자동 해결되지는 않는다. [Microsoft Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)

첫 executor는 **foreground 프로세스** 계약을 권장한다. shell 문자열을 임의 실행하지 않고, 경로·고정 인자와 업무 인자를 분리한다. 백그라운드로 daemon을 시작하고 부모만 종료하는 프로그램은 초기 지원에서 제외한다. `.cmd`/`.bat`처럼 command interpreter가 필요한 형식은 직접 실행 파일과 구별하고 OS별 인자 인코딩을 검증한다.

서비스를 정상 중지할 때는 신규 수신을 막고 drain한다. 강제 중지·crash 때 자손을 종료할지 유지할지는 설치 정책과 프로세스 관리 구현을 함께 맞춰야 한다. 초기에는 자손을 관리 범위 밖에 남기지 않는 정책을 목표로 하되 실제 종료 확인이 안 되면 admin에는 UNKNOWN으로 보고한다. Linux와 Windows의 정상 종료 요청 방식이 같다고 가정하지 않는다.

## 6. 설치·통신·업데이트

배포 목표는 OS/CPU별 실행 파일과 설정 예시·서비스 설치 자료다. `CGO_ENABLED=0` 빌드를 기본 목표로 하되 목표 OS에서 의존성과 실제 실행을 검증한다. 단일 실행 파일이라는 말은 설정·인증키·SQLite·로그 파일이 없다는 뜻이 아니다.

Linux에서는 systemd unit과 배포 archive, Windows에서는 exe와 서비스 등록 절차를 제공한다. Windows 서비스 계정의 디렉터리 ACL과 Linux 전용 계정의 파일 권한을 설정한다. OS/패키지/인증서 저장소는 여전히 필요하다. 설치 자체는 운영자 권한으로 수행하되 업무 실행은 필요한 권한을 가진 전용 계정으로 제한한다.

TOML에는 admin 주소, runner identity, 동시 실행 수, state/log 경로와 프로그램 manifest를 둔다. 운영 인증키는 별도 보호 파일에 보관하고 일반 설정 조회·로그에서 제외한다. HTTPS 서버 인증서를 검증하며 내부 CA가 필요하면 명시적으로 trust 설정을 지원한다.

최초 통신은 현재 설계의 polling + backoff/jitter다. 작업 claim이 지연되거나 로그 업로드가 밀려도 heartbeat·완료 보고·취소 수신이 굶지 않도록 독립 루프와 용량 제한을 둔다. API version과 runner version을 등록/heartbeat에서 확인하고 호환되지 않는 runner에는 작업을 배정하지 않는다.

초기 업데이트는 drain → 서비스 중지 → 검증된 실행 파일 교체 → 시작 → 상태 복구로 진행한다. 자동 self-update는 후속 기능이다. 로컬 DB migration 버전을 관리하고 이전 binary가 새 schema를 읽지 못하는 경우 무조건 rollback하지 않는다.

## 7. 구현 경계와 선행 검증

별도 `kkdugi-runner` Go 프로젝트를 제안한다. API DTO는 JSON 계약을 기준으로 만들며 admin의 Java 클래스·MyBatis 모델을 공유하지 않는다.

구체적인 패키지, 실행 worker와 통신 루프, 로컬 저장 구조와 구현 순서는 [Runner 프로그램 구조](runner-structure.md)를 따른다.

| 구성 | 책임 |
| --- | --- |
| agent/client | 등록·polling·heartbeat·결과 보고, protocol version |
| executor | 프로그램 실행과 OS별 프로세스 제어 |
| state | SQLite journal·복구·ACK 관리 |
| spool | 로그 파일·업로드·용량 및 보관 제한 |
| service/config | OS 서비스 수명 주기·설정·identity 보관 |

본격 구현 전에 작은 실행 검증으로 다음을 확인한다. 이는 기술 선정 후의 구현 작업이며 이 문서 작성 과정에서 수행한 테스트가 아니다.

1. 목표 OS에서 설치·서비스 시작/중지, `CGO_ENABLED=0` binary와 SQLite 동작.
2. 공백·한글이 있는 경로/인자, stdout/stderr 동시 대량 출력, 종료 코드 전달.
3. 자식·손자 프로세스의 취소/timeout, runner 강제 중지 후 잔존 여부.
4. 실행 직전·직후 crash, admin 응답 유실, SQLite/로그 디스크 부족 시 복구와 중복 실행 차단.
5. 모듈 버전 호환성과 로그·파일·프로세스 handle 누수. race 검사는 지원 환경에서 별도 빌드 조건으로 수행한다.

Go 선택은 runner를 Java/Python 업무와 분리한다. 예를 들어 Go runner가 머신의 JRE로 Java JAR를 실행하고 JSON 결과를 admin에 전달할 수 있다. 이후 Workflow가 추가되어도 runner의 책임은 동일한 단일 Job 실행으로 유지한다.
