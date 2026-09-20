# Admin–Runner API 계약 v1

- 상태: 2026-09-20 설계 계약. 아직 구현된 API가 아니며 `docs/api/`의 현행 API와 구분한다.
- 대상: Go runner와 `kkdugi-admin` 사이의 등록·작업 실행·복구 프로토콜.
- 기준: [실행 규칙](execution.md), [테이블](tables.md), [기술 스택](runner-tech-stack.md), [관리자 API](admin-api.md).
- 책임: Runner는 프로그램 하나를 실행해 결과를 보고한다. **다음 Job 실행과 Workflow owner 승인 여부는 admin이 결정한다.**

## 1. 공통 형식

Base URL은 `https://{host}{contextPath}/api/v1.0/batch-agent`다. 이하 경로는 이 기준의 상대 경로다. HTTPS 인증서를 검증하고 redirect를 따라 인증키를 다른 origin에 보내지 않는다.

| 항목 | 계약 |
| --- | --- |
| 요청/응답 | UTF-8 `application/json`, 공통 성공 envelope 없음. 204는 본문 없음 |
| 인증 | `Authorization: Bearer {accessToken}`. 등록만 enrollmentToken 사용 |
| 버전 | `X-Protocol-Version: 1`, 모든 요청 필수. 지원하지 않으면 409 `batch.protocol.unsupported` |
| 세션 | `X-Runner-Session: {session}`. 등록·세션 개설 외 필수, bigint를 십진 문자열로 표현 |
| 식별자 | admin 발급 ID는 문자열, 최대 20자. 예시 ID는 설명용 |
| 요청 식별자 | `Idempotency-Key`: 1~200자 ASCII, UUID 권장. 재전송 시 같은 값 |
| 요청 생성 시각 | `X-Request-Created-At`: key 생성 때 저장한 UTC 시각. key를 요구하는 요청에는 필수 |
| 시각 | RFC 3339 UTC, `2026-09-20T02:00:00.000000Z` 형식, 소수 초 최대 6자리 |
| 숫자 | 횟수·초·attempt는 JSON 정수. bigint 세션·로그 순번·설정 버전은 JSON 십진 문자열 |
| null/생략 | 표에서 선택이라고 명시한 필드만 생략 가능. nullable 필드는 null 허용. 미지의 요청 필드는 400, 응답의 추가 필드는 무시 |
| 민감 정보 | 토큰·비밀 값은 로그/Event/일반 조회에 포함하지 않음. 토큰 발급 응답은 `Cache-Control: no-store` |

초기 상한: 일반 JSON 요청 1 MiB, 해석 완료 입력·결과 각각 256 KiB, manifest 256 KiB, heartbeat 항목 200개, runner capacity 최대 200. 크기는 UTF-8 인코딩한 본문 기준이다. 용량을 넘으면 413이며 서버가 조용히 자르지 않는다. 등록/세션 응답의 limits는 이 상한 이하의 실제 운영 제한을 알린다.

### 오류

```json
{"code":"batch.assignment.stale","message":"현재 배정 소유권이 아닙니다."}
```

Machine API는 기존 `ExceptionMessage`와 같은 단일 `{code, message}` 형태를 사용한다. runner는 번역 가능한 message가 아닌 code로 분기한다.

| HTTP | code 예 | 처리 |
| --- | --- | --- |
| 400 | `batch.request.invalid` | 타입·필수 필드·입력 규격 수정 |
| 401 | `batch.credential.invalid` | 인증키 만료/폐기 포함. 신규 수신 중단, 무한 재시도 금지 |
| 403 | `batch.runner.forbidden` | 다른 runner의 자원, 잘못된 자격증명 종류 |
| 404 | `batch.assignment.not_found` | 존재하지 않거나 조회할 수 없는 ID |
| 409 | `batch.session.stale`, `batch.assignment.stale`, `batch.state.conflict`, `batch.idempotency.conflict`, `batch.protocol.unsupported` | 해당 상태 조회·복구. 새 key로 같은 실행을 무조건 반복하지 않음 |
| 410 | `batch.request.expired`, `batch.log.expired` | 재전송 보장 기간/로그 보관 종료. 새 실행으로 대체하지 않음 |
| 413 | `batch.payload.too_large` | 분할 가능한 로그만 분할, 완료 결과는 계약 한도 안으로 수정 |
| 429 | `batch.rate_limited` | Retry-After(초) 준수 |
| 503/500 | `batch.unavailable`, `err.default` | 처리 여부가 불명. 같은 key로 backoff/jitter 재전송 |

토큰 폐기·네트워크 단절 시 기존 프로세스의 timeout·로컬 복구 기록은 계속 유지한다. 권한 오류가 프로세스 종료 증거가 되지는 않는다.

## 2. 멱등성과 타이밍

표에서 K로 표시한 명령은 key가 필수다. `(인증 주체, HTTP method, 정규화 path, key)` 범위로 요청의 의미와 응답을 영속 저장한다. 같은 key와 같은 요청은 최초 HTTP 상태·본문을 반환하고, 다른 요청은 409다. JSON 객체 키 순서는 동일성에 영향을 주지 않으며 숫자 정밀도를 잃지 않는 구조 비교/정규화를 사용한다. 인증 토큰 자체는 hash 대상이 아니다. 세션·요청 생성 시각·프로토콜 버전은 동일성에 포함한다.

응답 재현 기간은 72시간이다. 기존 key가 없을 때 생성 시각이 서버 시각과 5분 넘게 차이나면 410으로 거절한다. 오래된 key가 정리된 뒤 같은 요청을 새 명령으로 처리하지 않기 위한 규칙이다. 시계가 어긋난 runner는 동기화한 후 **미접수임을 확인한 명령만** 새 key로 보낸다. 최초로 보낼 미전송 완료는 전송 시 key를 생성하며 실제 종료 시각과 요청 생성 시각은 다르다.

멱등 응답은 과거 시점의 응답이다. 인증·현재 세션 검사 후 반환하며 시작 가능 여부·lease·취소는 현재 상태를 다시 확인해야 한다. 72시간 이후에도 배정 ID/완료 hash/로그 복합키의 의미상 중복 방지는 이력 보관 중 유지된다. 미확인 실행·결과 이력을 기간만으로 삭제하지 않는다.

초기 운영 기본값은 heartbeat 10초, claim polling 3초, lease 60초, 시작 허가 유효기간 최대 15초다. 서버가 실제 값을 응답한다. Runner는 UTC 시각 비교와 별도로 로컬 monotonic 경과 시간으로 timeout을 집행하고 lease 여유를 고려한다. 네트워크 요청을 열어둔 채 DB 트랜잭션을 유지하지 않는다.

## 3. 엔드포인트 목록

| Method / 경로 | 인증/세션 | K | 성공 |
| --- | --- | --- | --- |
| POST `/registrations` | enrollment / 없음 | 아니오 | 201 |
| POST `/sessions` | access / 본문 expectedSession | boot ID로 멱등 | 200 |
| PUT `/programs/{programCode}` | access / 현재 | 예 | 200 |
| POST `/heartbeat` | access / 현재 | 아니오 | 200 |
| POST `/assignments/claim` | access / 현재 | 예 | 200 또는 204 |
| GET `/assignments?state=UNRESOLVED` | access / 현재 | 아니오 | 200 배열 |
| GET `/assignments/{assignmentId}` | access / 현재 | 아니오 | 200 |
| POST `/assignments/{assignmentId}/reconcile` | access / 현재 | 예 | 200 |
| POST `/assignments/{assignmentId}/start` | access / 현재 | 예 | 200 |
| POST `/assignments/{assignmentId}/started` | access / 현재 | 예 | 200 |
| PUT `/assignments/{assignmentId}/logs/{stream}/{sequence}` | access / 현재 | 복합키로 멱등 | 200 |
| POST `/assignments/{assignmentId}/completion` | access / 현재 | 예 | 200 |

Runner가 Job을 직접 생성·재시도하거나 임의의 Run을 가져가는 API는 없다. access 인증 주체에서 runner ID를 결정하며 경로나 본문으로 다른 runner를 지정할 수 없다.

## 4. 등록과 세션

### POST /registrations

관리자가 사전 생성한 runner에 연결된 일회성 enrollmentToken을 Bearer로 보낸다.

```json
{"runnerCode":"worker-01","agentVersion":"0.1.0","hostname":"batch-01","os":"LINUX","architecture":"AMD64"}
```

runnerCode는 20자 이내, agentVersion 50자, hostname 200자, os는 LINUX/WINDOWS, architecture는 AMD64/ARM64다. 값이 있어도 서버의 실제 지원 조합에 없으면 409다.

```json
{"runnerId":"BR000000000000000001","credentialId":"BC000000000000000001","accessToken":"example-access-token","tokenExpiresAt":"2026-10-20T02:00:00Z","session":"0"}
```

등록 토큰 소비와 ACCESS 키 생성은 같은 트랜잭션이다. ACCESS 토큰은 이 응답에서 한 번만 제공한다. 응답 유실 시 평문 토큰을 재조회하지 않고 관리자가 새 등록 토큰을 발급하는 복구 절차를 밟는다. 재등록은 기존 ACCESS 키를 폐기하고 실행이 남아 있으면 운영자가 먼저 확인한다. 단순 프로세스 재시작에는 등록을 반복하지 않는다.

### POST /sessions

Runner가 로컬 단일 인스턴스 잠금을 획득한 뒤 호출한다. 매 프로세스 기동마다 bootId(UUID)를 로컬에 저장한다. 같은 개설 요청의 재전송은 같은 bootId를 사용한다.

```json
{"bootId":"95358b21-7e8b-4f51-b7a4-93b1d0a4ab73","expectedSession":"0","agentVersion":"0.1.0"}
```

```json
{"runnerId":"BR000000000000000001","session":"1","serverTime":"2026-09-20T02:00:00Z","heartbeatSeconds":10,"pollSeconds":3,"leaseSeconds":60,"capacity":1,"limits":{"jsonBytes":1048576,"inputBytes":262144,"resultBytes":262144,"logChunkBytes":32768}}
```

같은 bootId가 현재 값이면 동일 세대를 반환한다. 다른 bootId는 expectedSession이 현재 세대와 일치할 때만 세대를 원자적으로 증가시킨다. 불일치는 409다. 세대 값을 임의로 바꾸며 다른 프로세스의 세션을 빼앗지 않는다. 구세션 보고는 409이며 세대 값을 잃으면 관리자 복구 절차를 따른다.

새 세션은 기존 Attempt의 소유 세대를 자동 변경하지 않는다. 미해결 목록과 로컬 journal을 대조하고 reconcile을 마친 뒤 claim한다.

## 5. 프로그램 설치 보고

### PUT /programs/{programCode}

동일 runner의 설치 정보다. 논리 Program은 admin에 사전 등록하며 없는 programCode는 404다. 사용 여부와 승인 revision은 runner가 변경하지 못한다.

```json
{"version":"1.2.0","revision":"manifest-sha256-example","availability":"AVAILABLE","manifest":{"executable":"/usr/bin/java","arguments":["-jar","/opt/batch/sales/1.2.0/sales.jar"],"workingDirectory":"/opt/batch/sales","inputContract":"1","inputMode":"JSON_FILE","secretNames":["SALES_DB_PASSWORD"],"files":[{"path":"/opt/batch/sales/1.2.0/sales.jar","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}}
```

version은 50자, revision은 200자 이내의 불변 설치 버전 식별자다. 내용 변경 시 새 revision을 사용한다. 같은 revision에 다른 manifest는 409다. availability는 AVAILABLE/MISSING/INVALID다. manifest는 실행 절대경로, 고정 argv, 작업 디렉터리, 입력 계약 ID, 입력 모드, secret 참조 이름, 배포 파일 digest 목록이며 비밀 값을 포함하지 않는다.

v1 입력 모드는 JSON_FILE이다. 최종 입력을 작업 영역의 JSON 파일에 저장하고 `KKDUGI_INPUT_FILE`, 결과 위치를 `KKDUGI_RESULT_FILE` 환경 변수로 전달한다. 입력을 shell 문자열로 펼치지 않는다. 자유로운 argv 매핑은 후속 버전이며 기존 프로그램은 고정 wrapper로 연결할 수 있다.

```json
{"programId":"BP000000000000000001","revision":"manifest-sha256-example","approvedRevision":null,"enabled":true,"runnable":false}
```

Program·runner·설치 사용 여부, revision 일치와 availability로 runnable을 계산한다. true여도 미래 실행 권한을 예약하지 않는다. 설치에서 사라진 항목은 MISSING으로 명시적으로 보고한다.

## 6. Heartbeat와 취소 전달

### POST /heartbeat

```json
{"observedAt":"2026-09-20T02:01:00Z","mode":"ACCEPTING","freeSlots":0,"assignments":[{"id":"BA000000000000000001","phase":"RUNNING"}]}
```

mode는 ACCEPTING/DRAINING/DEGRADED, phase는 ASSIGNED/STARTING/RUNNING/STOPPING/FINISHED/UNKNOWN이다. freeSlots는 0~capacity 관측값으로 admin의 용량 검사를 대체하지 않는다. 빈 배열은 유효하며 누락된 Attempt를 종료 처리하지 않는다. 오래된 heartbeat를 쌓아 재생하지 않고 현재 상태를 보낸다.

```json
{"serverTime":"2026-09-20T02:01:01Z","acceptingAssignments":true,"assignments":[{"id":"BA000000000000000001","action":"CONTINUE","leaseUntil":"2026-09-20T02:02:01Z","stopReason":null,"graceSeconds":null}]}
```

action은 CONTINUE/STOP/RECONCILE이다. STOP은 stopReason=CANCEL/TIMEOUT과 graceSeconds를 포함하며 종료 확인까지 반복한다. Runner는 종료 확인 전에 CANCELED/TIMED_OUT을 보고하지 않는다. RECONCILE은 일반 갱신 불가이며 새 시작 허가가 아니다. leaseUntil=null이면 갱신하지 않은 것이다.

전체 응답은 200이어도 항목별 RECONCILE이 가능하다. 다른 runner ID가 포함되면 전체를 403으로 거절한다. 유효한 현재 세대의 배정만 lease를 갱신하며 LOST/종결 Attempt를 heartbeat만으로 RUNNING으로 되돌리지 않는다.

## 7. 배정・조회

### POST /assignments/claim

```json
{"freeSlots":1}
```

요청당 최대 1개 배정하며 없으면 204다. 같은 key는 204까지 원래 응답을 반환하므로 **다음 polling은 새 key**를 사용한다. 배정과 멱등 기록은 같은 트랜잭션이다. 응답이 불명인 채 다른 claim을 겹쳐 보내지 않는다.

```json
{"id":"BA000000000000000001","runId":"BX000000000000000001","attempt":1,"session":"1","program":{"id":"BP000000000000000001","code":"daily-sales","version":"1.2.0","revision":"manifest-sha256-example"},"input":{"businessDate":"20260919"},"execution":{"timeoutSeconds":3600,"stopGraceSeconds":10,"businessKey":"BX000000000000000001"},"leaseUntil":"2026-09-20T02:03:00Z","state":"ASSIGNED","startAllowed":false,"startBefore":null}
```

program은 Run snapshot에 고정된 승인 설치다. 실행 직전에도 로컬 manifest·revision/digest를 비교하고 불일치 시 최신 버전으로 대체하지 않는다. input은 admin이 기본값·재정의·날짜 표현식을 해석한 JSON이며 재시도에서도 유지한다.

### GET /assignments/{id} / GET /assignments?state=UNRESOLVED

상세는 claim 응답에 `outcome`(완료 본문, 미완료면 null), `logOffsets`(stream별 연속 ACK 순번, 없으면 null)를 추가한다. 종결 배정도 이력 보관 중에는 조회한다. 미해결 목록은 세대와 무관하게 slot을 보유한 ASSIGNED/RUNNING/LOST 등을 최대 200개 배열로 반환한다.

상세의 startAllowed는 현재 세대·미시작·취소 없음·저장된 허가 및 만료 전이라는 조건을 모두 만족할 때만 true다. GET은 허가를 새로 만들거나 연장하지 않는다. 이미 시작 의도를 기록한 runner는 true 응답을 받아도 두 번째 프로세스를 시작하지 않는다. state는 Attempt의 저장 상태이며 runState는 별도 반환한다.

응답 유실 후 목록과 journal을 대조한다. 다른 세대의 배정은 조회만 가능하고 보고하려면 reconcile이 필요하다. 보관이 끝난 종결 배정은 410, 알 수 없는 ID는 404다. 미해결 배정은 정기 삭제하지 않는다.

## 8. 재시작·통신 장애 후 대조

### POST /assignments/{id}/reconcile

```json
{"previousSession":"1","observation":"RUNNING","process":{"pid":"18420","startedAt":"2026-09-20T02:02:12Z","bootId":"95358b21-7e8b-4f51-b7a4-93b1d0a4ab73"}}
```

observation은 NEVER_STARTED/RUNNING/FINISHED/UNKNOWN이다. process는 RUNNING에서 필수, 나머지는 null 가능하다. FINISHED는 `completion`에 아래 완료 본문을 포함한다. NEVER_STARTED는 journal로 start 호출 전임을 확인한 경우만 사용한다. 시작 의도 기록 후 crash로 불명확하면 UNKNOWN이다.

```json
{"id":"BA000000000000000001","disposition":"CONTINUE_EXISTING","session":"2","leaseUntil":"2026-09-20T02:04:00Z","startAllowed":false}
```

disposition은 CONTINUE_EXISTING / STOP_AND_REPORT / REPORT_COMPLETION / HOLD / RESOLVED다. 같은 runner의 구세대 배정인지, 운영 종결·후속 실행이 없는지, 관측과 상태가 맞는지 검사하고 보고 소유 세대를 갱신한다. 어떤 응답도 **새 프로세스 시작을 허가하지 않는다**.

- RUNNING이고 동일 프로세스로 확인되면 기존 실행 계속 또는 중지를 지시한다. 불명확하면 HOLD다.
- FINISHED의 완료를 함께 검증·저장하면 RESOLVED, 추가 보고가 필요하면 REPORT_COMPLETION이다.
- NEVER_STARTED여도 만료된 start 허가를 되살리지 않는다. 미시작을 확정하고 이전 Attempt를 실패/취소로 종결한다. 다음 시도는 admin의 Run 정책으로 판단한다.
- UNKNOWN/HOLD는 slot을 유지하고 운영 확인을 기다리며 다른 runner에 자동 재배정하지 않는다.
- 같은 세션에서 lease만 만료되어도 이 API를 사용한다. 종결 후 로그 재전송 목적이면 결과는 유지하고 현재 세대의 보고를 허용한다.

HOLD는 Run UNKNOWN/Attempt LOST와 slot 점유를 유지한다. CONTINUE_EXISTING은 같은 프로세스의 존재가 확인되고 운영 종결 전인 경우에만 Run/Attempt를 RUNNING으로 복구하고 lease를 갱신한다. STOP_AND_REPORT는 취소 요청을 유지하며 보고를 받을 현재 소유 세대만 연결한다. 모든 조정은 Event에 근거를 남긴다.

## 9. 시작 허가와 보고

### POST /assignments/{id}/start

```json
{"programRevision":"manifest-sha256-example"}
```

```json
{"id":"BA000000000000000001","startAllowed":true,"startBefore":"2026-09-20T02:02:15Z","leaseUntil":"2026-09-20T02:03:00Z"}
```

admin은 취소·사용 중지·현재 세대·미시작·revision을 검사하고 단기 시작 허가를 한 번 저장한다. startBefore는 lease를 넘지 않으며 재전송/새 key로 연장하지 않는다. Runner는 허가를 받은 뒤 시작 의도를 영속 기록하고 한 번만 spawn한다. 허가 대기·시작 의도·실제 시작을 journal에서 구분하고 crash로 불명확하면 spawn을 반복하지 않고 reconcile한다.

허가와 OS spawn 사이 취소 경합을 완전히 없앨 수는 없다. 알려진 취소는 시작 전에 확인하고 시작 후 STOP을 받으면 종료한다. 이를 정확히 한 번 실행 보장으로 표현하지 않는다.

### POST /assignments/{id}/started

```json
{"startedAt":"2026-09-20T02:02:12Z","process":{"pid":"18420","startedAt":"2026-09-20T02:02:12Z","bootId":"95358b21-7e8b-4f51-b7a4-93b1d0a4ab73"}}
```

```json
{"id":"BA000000000000000001","state":"RUNNING","action":"CONTINUE"}
```

PID는 문자열이며 process.startedAt+bootId와 함께 식별한다. 허가 내 시작한 증거와 현재 상태를 대조한다. 통신 지연만 있으면 startBefore 이후 수신도 대조할 수 있지만 구세대/만료 lease는 reconcile한다. 취소 경합이면 200/action=STOP을 반환할 수 있다. 동일 프로세스 보고는 멱등, 다른 프로세스는 409다.

## 10. 로그

### PUT /assignments/{id}/logs/{stream}/{sequence}

stream은 STDOUT/STDERR, sequence는 0부터 시작하는 bigint 십진 문자열이다. UTF-8 text chunk는 최대 32 KiB로 secret 마스킹 후 코드포인트 경계에서 나눈다.

```json
{"emittedAt":"2026-09-20T02:02:13Z","text":"sales aggregation started\n","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
```

sha256은 `text` UTF-8 bytes의 SHA-256 hex 64자다. 예시 값은 형식 설명용이며 실제 digest가 아니다.

```json
{"acceptedSequence":"0","contiguousThrough":"0","logsClosed":false}
```

복합 key `(assignment, stream, sequence)`의 같은 내용은 200, 다른 내용은 409다. 순서가 바뀌어 수신해도 contiguousThrough는 누락이 채워질 때만 전진한다. null은 연속 수신이 시작되지 않았다는 뜻이다. Runner는 연속 ACK까지의 로컬 chunk만 삭제한다.

업무 종료 후에도 미완성 로그는 전송 가능하다. 완료 본문의 최종 sequence 초과는 409다. 초기 상한은 Attempt 합계 10 MiB, 초과는 413이다. Runner는 상한 이후에도 파이프를 읽어 버려 업무 정체를 막고 TRUNCATED를 보고한다. 저장된 chunk를 일방적으로 교체하지 않는다.

## 11. 완료 이벤트

### POST /assignments/{id}/completion

```json
{"outcome":"SUCCEEDED","startedAt":"2026-09-20T02:02:12Z","finishedAt":"2026-09-20T02:03:12Z","exitCode":0,"processExited":true,"failure":null,"result":{"processed":1250},"logs":{"STDOUT":{"lastSequence":"3","status":"COMPLETE"},"STDERR":{"lastSequence":null,"status":"COMPLETE"}}}
```

| 필드 | 계약 |
| --- | --- |
| outcome | SUCCEEDED / FAILED / CANCELED / TIMED_OUT. UNKNOWN은 reconcile로 보고 |
| startedAt | UTC 또는 null, spawn 전 실패/취소이면 null |
| finishedAt | UTC 필수. 시작한 경우 startedAt 이후 |
| exitCode | OS 종료 코드 정수 또는 null(미시작 등). v1 범위 -2147483648~4294967295 |
| processExited | true 필수. 프로세스 관리 범위 종료가 불명확하면 완료 처리 불가 |
| failure | null 또는 `{code, message}`. FAILED는 필수. code 20자, message 4000자 이내 |
| result | JSON object 또는 null, 최대 256 KiB, 프로그램 출력 계약 검증 |
| logs | STDOUT/STDERR 모두 필수. lastSequence는 마지막 전송 대상 chunk 순번, 빈 stream은 null. status=COMPLETE/TRUNCATED/LOST |

SUCCEEDED는 기본 성공 코드 0과 일치해야 한다. CANCELED/TIMED_OUT에는 취소 요청 또는 timeout 근거가 필요하며 로컬 timeout도 실행 제한과 대조한다. 성공 후 도착한 취소로 결과를 바꾸지 않는다. 실패 코드 예는 PROGRAM_MISSING / REVISION_MISMATCH / SPAWN_FAILED / EXIT_NONZERO / OUTPUT_INVALID다.

```json
{"id":"BA000000000000000001","completionAccepted":true,"attemptState":"SUCCEEDED","runState":"SUCCEEDED","logState":"PENDING"}
```

종료·slot 해제·재시도 예약·Event는 같은 트랜잭션이다. 재시도 예정이면 runState=RETRY_WAIT다. started보다 completion이 먼저 와도 저장된 start 허가와 일치하면 시작·종료를 함께 반영할 수 있다.

같은 완료를 다른 key로 재전송해도 구조 hash로 중복을 확인하고 현재 ACK를 반환한다. 다른 outcome/결과/종료시각은 409다. `logs` 최종 sequence와 누락 선언도 첫 완료 때 고정하고 후속 chunk에 따라 admin의 logState만 갱신한다. 미수신이면 PENDING, 전체 수신 또는 TRUNCATED/LOST 선언에 따라 확정한다. 로그 유실이 업무 SUCCEEDED를 FAILED로 바꾸지는 않는다.

이 200은 **admin이 완료 이벤트를 영속 저장한 ACK**이며 다음 Job 실행 허가가 아니다. Workflow 진행과 owner 승인은 admin이 처리하며 후속 Job ID나 승인 작업을 runner에 반환하지 않는다. 미처리 완료는 재시작 후 복구하며 메모리 이벤트만 믿지 않는다.

## 12. 구현 수용 조건

- 등록 응답 유실, 동일 boot 재전송, 중복 기동, 구세션 보고를 검증한다.
- claim의 200/204 응답 유실, 72시간 이후 key 재전송, 같은 key의 다른 본문에서 중복 배정하지 않는다.
- start 허가 응답 유실과 spawn 전후 crash에서 재시작을 무조건 반복하지 않는다.
- heartbeat·취소·완료 경합, 통신 단절 중 timeout, 자손 프로세스 잔존을 검증한다.
- 로그 순서 역전·누락·중복·상한·완료 후 재전송을 검증한다.
- 완료 ACK 이후 admin 재시작에도 Workflow 판단을 복구하고 owner 승인 전 후속 Run을 만들지 않는다.

API 구현 시 알 수 없는 JSON 필드, 숫자 경계, 크기 상한, 오류 code를 계약 테스트로 검증한다. 이 API를 구현 완료로 취급하지 않는다.
