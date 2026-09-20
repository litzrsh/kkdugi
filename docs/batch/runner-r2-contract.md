# R2: HTTPS client와 등록 구현 계약

- 범위: [구현 계획](runner-implementation-plan.md)의 R2. 실제 admin API는 아직 미구현이며 TLS 테스트 서버로 계약을 검증한다.
- `register --config <path>`는 enrollment 토큰을 표준 입력으로 받고 한 번 요청한다. 토큰 인자·환경 변수·진단 출력은 제공하지 않는다.
- 세션 개설은 client 메서드까지만 구현한다. CLI가 임의로 bootId를 만들거나 세대를 변경하지 않으며 R3/R5의 journal을 연결한 뒤 사용한다.

## DTO

`internal/client/protocol.go`, package client. 아래 Go struct 필드는 모두 필수 JSON 필드이며 `omitempty`를 사용하지 않는다. 공통 타입은 별도 파일에서 구현한다.

- `Decimal`: signed bigint 범위의 음수가 아닌 십진 **문자열**. JSON 숫자·null·음수·범위 초과를 거절한다.
- `Timestamp`: RFC3339 UTC 문자열, 소수 초 최대 6자리. JSON null 거절.
- `Secret`: Bearer 토큰 문자열. 일반 fmt 출력은 가리며 JSON 직렬화는 wire/store 용도로만 사용한다.

| Struct | Go 필드와 타입 / JSON tag |
| --- | --- |
| RegistrationRequest | RunnerCode string / runnerCode; AgentVersion string / agentVersion; Hostname string / hostname; OS string / os; Architecture string / architecture |
| RegistrationResponse | RunnerID string / runnerId; CredentialID string / credentialId; AccessToken Secret / accessToken; TokenExpiresAt Timestamp / tokenExpiresAt; Session Decimal / session |
| SessionRequest | BootID string / bootId; ExpectedSession Decimal / expectedSession; AgentVersion string / agentVersion |
| SessionResponse | RunnerID string / runnerId; Session Decimal / session; ServerTime Timestamp / serverTime; HeartbeatSeconds int / heartbeatSeconds; PollSeconds int / pollSeconds; LeaseSeconds int / leaseSeconds; Capacity int / capacity; Limits Limits / limits |
| Limits | JSONBytes int / jsonBytes; InputBytes int / inputBytes; ResultBytes int / resultBytes; LogChunkBytes int / logChunkBytes |

요청 길이·허용 enum, 응답 필수 필드와 숫자 상한을 검증한다. 응답의 추가 필드는 무시한다. DTO만 구현했다고 뒤 단계의 heartbeat·claim·완료 API를 구현한 것으로 표시하지 않는다.

## 통신

- Base URL은 context path를 포함한 `/api/v1.0/batch-agent`까지 지정한다. HTTPS만 허용하며 userinfo·query·fragment·경로 이동을 거절한다.
- 시스템 CA 검증을 유지하고 `admin.ca_file`로 사설 CA를 추가할 수 있다. TLS 검증 해제 옵션은 없다. 모든 redirect를 차단한다.
- 요청과 응답은 1 MiB 이하 UTF-8 JSON 객체로 제한한다. 204는 본문이 없어야 하며 실제 호출별 성공 HTTP status를 검사한다.
- `Authorization`, `X-Protocol-Version`, 필요한 세션·멱등 헤더를 설정한다. Client는 멱등 key를 만들거나 HTTP 실패를 자동 재전송하지 않는다.
- HTTP 오류는 status/code/Retry-After를 별도로 제공한다. 서버 message·응답 본문·토큰은 error 문자열에 포함하지 않는다. 알 수 없는 오류 code도 분기용으로 보관하되 일반 error 출력은 HTTP status만 표시한다.
- 등록 201은 `Cache-Control: no-store`를 요구한다. 응답 본문·필수 값이 잘못되면 토큰이 이미 소비되었을 수 있으므로 새 요청을 만들지 않는다.

## Credential 저장

설정의 `[admin]`에는 `base_url`, `runner_code`, 선택 `ca_file`, `credential_dir`, `request_timeout_seconds`를 둔다. Timeout 기본 30초(1~300), credential_dir 기본 `<data_dir>/credentials`다. 상대 파일 경로는 설정 파일 기준이다. 프로그램 없는 초기 등록 설정도 허용한다.

Credential 디렉터리는 runner 전용이며 경로의 symlink/reparse point를 거절한다. 기존 디렉터리가 넓은 권한으로 열려 있으면 자동 변경하지 않고 실패한다. 새 디렉터리는 Linux 0700, 파일 0600; Windows는 현재 계정과 SYSTEM만 허용하는 보호 ACL을 설정한다.

1. 파일 상태를 사전 확인하고 등록 토큰을 읽는다.
2. `.registration.pending`을 배타적으로 생성하고 영속화한다. 이미 credential/시도 기록이 있으면 HTTP 요청을 보내지 않는다.
3. 등록 API를 정확히 한 번 호출하고 반환 필드·만료를 검증한다.
4. Credential JSON을 제한된 권한의 임시 파일에 기록·동기화하고 같은 디렉터리에 덮어쓰기 없는 원자적 방식으로 `credential.json`을 공개한다.
5. 성공 파일을 영속화한 뒤 pending 기록을 지운다. 공개 후 정리 실패는 credential을 삭제하거나 재등록하는 이유가 아니다.

등록 응답 유실·저장 실패·기동 중단 때 pending/임시 파일을 자동 삭제하지 않는다. 운영자가 admin의 키·실행 상태를 확인하고 필요하면 재발급한 뒤 로컬 미완료 기록을 정리한다. R2에는 강제 재등록·키 교체 CLI를 제공하지 않는다.

Credential 파일은 schema version, base URL, runner code, admin 발급 runner/credential ID, ACCESS 토큰, 만료 시각, 최초 session을 저장한다. 등록 토큰은 저장하지 않는다. 마지막 실행 session의 원장은 이후 SQLite journal이며 이 파일의 최초 session을 매번 덮어써서 복구하지 않는다.

## 실행과 복구

`kkdugi-runner/configs/runner.registration.example.toml`을 복사하여 실제 URL·runner code·경로를 설정한다. 프로그램 목록 없이 `verify`로 설정을 확인할 수 있다. Admin의 등록 API가 준비된 환경에서 등록 토큰을 표준 입력으로 공급한다.

```text
kkdugi-runner verify --config runner.toml
kkdugi-runner register --config runner.toml
```

표준 입력은 EOF까지 읽는다. CLI 자체에 터미널 입력 숨김 기능은 없으므로 비밀 관리 도구 등의 비대화형 파이프로 전달한다. 토큰을 명령 인자·쉘 명령 문자열에 직접 적지 않는다. 성공 출력에는 토큰·응답 본문을 포함하지 않는다.

등록 실패 후에는 같은 명령을 반복해도 pending 기록 때문에 새 요청을 보내지 않는다. 운영자는 admin에서 해당 runner의 credential 발급 여부를 먼저 확인한다. `credential.json`이 있으면 공개 이후 정리 실패일 수 있으므로 삭제하거나 자동 재등록하지 않는다. 최종 파일 없이 임시 파일만 있거나 응답을 잃은 경우에도 발급된 credential 상태를 확인하고 필요한 폐기·재발급을 마친 다음 로컬 파일을 복구한다. 이 절차의 자동화는 후속 단계이며 R2 CLI는 임의로 미완료 기록을 지우지 않는다.

Windows와 AlmaLinux 10 WSL2에서 TLS 테스트 서버를 사용한 등록·권한·응답 유실 테스트를 통과했다. 실제 admin 연동은 R9에서 검증한다. 전체 결과는 [구현 계획](runner-implementation-plan.md)의 R2 기록을 참조한다.
