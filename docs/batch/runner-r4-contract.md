# R4: 프로그램 catalog·설치 보고

범위는 로컬 설치 snapshot, revision 불변성, 영속 PUT 보고, 승인 응답과 실행 전 대조다. 프로그램 다운로드·배포, admin API 구현, claim/start 루프는 포함하지 않는다. R5가 catalog를 호출한다.

## 모델 생성 작업

Ollama coder는 `internal/client/program_types.go`, package client의 아래 struct만 생성한다. 모든 필드에 JSON tag, omitempty 없이 선언한다.

- ProgramFile: Path string `path`, SHA256 string `sha256`.
- ProgramManifest: Executable string `executable`, Arguments []string `arguments`, WorkingDirectory string `workingDirectory`, InputContract string `inputContract`, InputMode string `inputMode`, SecretNames []string `secretNames`, Files []ProgramFile `files`.
- ProgramReport: Version string `version`, Revision string `revision`, Availability string `availability`, Manifest ProgramManifest `manifest`.
- ProgramApproval: ProgramID string `programId`, Revision string `revision`, ApprovedRevision *string `approvedRevision`, Enabled bool `enabled`, Runnable bool `runnable`.

## catalog

설정의 전체 프로그램 목록을 읽어 syntax 검증한 뒤 파일을 조사한다. 정상은 AVAILABLE, 파일·디렉터리 없음은 MISSING, digest 불일치·권한·파일 형식 오류는 INVALID다. 설정 자체가 잘못되면 전체 refresh를 거절하며 다른 항목을 삭제된 것으로 처리하지 않는다.

등록 목록에서 빠진 프로그램은 마지막 version/revision/manifest로 MISSING을 저장한다. 같은 (code, revision)의 version·manifest 변경은 재기동 이후에도 거절한다. 파일 내용 변화는 digest 검증으로 INVALID 처리한다. Canonical JSON은 고정 struct 순서, nil 배열을 빈 배열로 정규화하고 SHA256은 소문자로 만든다. argv 순서는 보존한다. digest 목록에 없는 실행 파일의 변경까지 추적한다고 주장하지 않는다. 추적할 실행 파일·wrapper·산출물은 files에 등록한다.

## 보고와 승인

`QueueProgramReport`는 현재 확정 세션에서 요청을 영속화한다. 미해결 요청이 있으면 같은 key·본문을 먼저 해결한다. 그 사이 설치가 바뀌었으면 과거 응답은 ACK만 저장하며 최신 설치를 승인하지 않는다. 이어지는 보고 호출에서 최신 상태를 보낸다. 구세션 요청은 보존하고 현재 세션의 새 설치 보고를 만든다. 실행 명령의 자동 재발급과는 구분한다.

보고는 R3 BeginSend를 거쳐 client의 PUT 메서드로 전송한다. 응답의 필수 필드·revision·programId를 검증하고 ACK와 승인 snapshot을 원자적으로 저장한다. `runnable=true`여도 enabled=false, 승인 revision 불일치, 로컬 unavailable이면 오류다. 승인 조회·갱신을 위해 같은 설치를 다시 보고할 수 있으며 새 보고를 준비하면 이전 승인 캐시는 무효가 된다.

승인은 현재 boot/session과 현재 보고 본문에만 적용한다. 재시작·파일 상태 변경·revision 변경·보고 오류 때 과거 승인을 실행 권한으로 사용하지 않는다. `Resolve`는 admin programId/code/version/revision과 대조하고 AVAILABLE·enabled·runnable·approvedRevision을 검사한 후 실제 파일 digest를 다시 검사한다. 반환된 실행 명세도 독립 복사본이다. 승인 snapshot은 미래 실행 허가가 아니며 R5는 매 배정에서 start 허가·lease·취소를 별도로 검증해야 한다.

## 영속성

Schema v2의 `kkdugi_runner_program_revision`은 불변 version/manifest, `kkdugi_runner_program`은 현재 설치 상태·요청 참조·승인 snapshot을 저장한다. 기존 v1 migration은 변경하지 않고 checksum을 검증한 후 v2를 트랜잭션으로 적용한다. SQL은 `internal/state/migrations/002_program_catalog.sql`에 둔다. HTTP는 SQLite 트랜잭션 밖에서 수행한다.

| 테이블 | 컬럼 |
| --- | --- |
| kkdugi_runner_program_revision | code/revision TEXT 복합 PK, definition BLOB, definition_hash TEXT |
| kkdugi_runner_program | code TEXT PK, revision TEXT(이력 FK), report BLOB, report_hash TEXT, request_id TEXT(nullable 요청 FK), approval BLOB(nullable), approval_session/approval_boot TEXT(nullable) |

모든 테이블은 SQLite STRICT다. JSON은 원문 bytes 보존용 BLOB, ID·revision·hash·session은 TEXT다. Oracle 도메인 타입을 복사하지 않는다. [migration SQL](../../kkdugi-runner/internal/state/migrations/002_program_catalog.sql).

## 상위 루프에서의 사용

1. R3 Store와 R2 client를 같은 credential의 admin URL로 구성하고 세션 개설·확정을 마친다.
2. `catalog.New(store, client, token)`으로 생성한 뒤 `Refresh(ctx, cfg.Programs)`에 전체 목록을 전달한다.
3. `Store.ProgramCodes(ctx, after, limit)`으로 삭제 이력까지 조회하여 `Publish(ctx, code)`를 호출한다. 목록은 code cursor, page 1~200개다. Publish 한 번은 HTTP 시도 한 번이며 최신 설치와 다른 과거 요청을 해결한 경우 다음 호출에서 최신 설치를 보고한다.
4. 배정의 programId/code/version/revision으로 `Resolve`를 호출한다. 성공한 실행 명세에 대해 R5의 시작 허가·시작 의도 저장을 거친다. 실행 도중 프로그램 배포 파일을 교체하지 않는다.

R4는 새 CLI 명령을 추가하지 않는다. 프로그램 사용 여부·승인 revision은 admin 응답에서만 받는다. 로컬 설정으로 승인하지 않는다. R5가 보고 주기·backoff·새 배정 수락을 조정해야 한다. `Refresh`는 파일 존재·권한·digest를 검사하지만 프로그램을 실행하지 않는다.

테스트는 Windows 및 AlmaLinux 10 WSL2에서 수행했다. 상세 결과·기존 Admin 테스트 실패는 [진행 기록](runner-implementation-plan.md)의 R4 절에 남겼다.
