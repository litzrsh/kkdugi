# 배치 관리자 API 계약 v1

- 상태: 2026-09-20 설계 계약, 미구현. [Runner API](runner-api.md)와 함께 최초 배치 범위를 정의한다.
- Base URL: `/api/v1.0/admin/batch`. 기존 사용자 JWT 및 `X-Menu-Id` 요청 컨텍스트를 사용한다.
- 코드·enum은 API 의미 이름으로 노출하고 DB 컬럼 축약형은 노출하지 않는다. 테이블 ID는 20자, 사용자 ID는 기존 호환 60자다.

## 1. 공통 계약

성공 객체는 별도 envelope 없이 반환하고 삭제·빈 성공은204다. 오류는 `{code, message}` 단일 객체다. 조회는 READ, 설정 생성/변경은 WRTE, 삭제는 DELT, 실행·취소는 EXEC 권한을 검사한다. Runner 등록 자격증명 발급·폐기는 SYS_ADMIN으로 제한한다. 현재 프로젝트의 [요청 컨텍스트 규칙](../api/request-context.md)을 확장 적용하며 아직 해당 endpoint가 허용되어 있는 것은 아니다.

목록은 기존 `Page<T>` 형식 `{page,pageSize,totalItems,totalPages,contents}`를 사용한다. page 기본1, pageSize 기본/최대200. 목록 필터가 없는 경우 전체 접근 가능 범위를 조회하고, 정렬은 생성 시각 내림차순 및 ID 내림차순으로 고정한다. `COUNT(*) OVER()` 기반이라 페이지 범위를 벗어난 빈 응답의 totalItems=0 한계도 동일하다. 일반 감사 컬럼은 API 응답에 포함하지 않는다.

```json
{"page":1,"pageSize":200,"totalItems":1,"totalPages":1,"contents":[{"id":"BJ000000000000000001","code":"daily-sales","name":"일별 매출 집계","enabled":true,"version":"1"}]}
```

상세 조회는 완전한 설정 객체와 `version`(bigint 십진 문자열)을 반환한다. 수정은 PUT 전체 교체이며 `If-Match: "1"`처럼 version을 전달한다. 누락은428, 불일치는412 `batch.version.conflict`다. 서버 관리 필드(id/version/접속 상태/실행 결과)는 수정 본문에 넣지 않는다. 코드와 참조 프로그램·실행 대상 변경은 기존 Run snapshot에 소급 적용하지 않는다.

생성·PUT·DELETE·수동 실행·취소·승인·복구 명령은 [Runner 계약의 key/생성 시각/72시간 멱등 규칙](runner-api.md#2-멱등성과-타이밍)을 사용자 주체 범위로 적용한다. 아래의 **일회성 토큰 발급만 예외**다. 재전송 응답은 과거 snapshot이며 현재 상태는 상세 조회한다. 사용자의 현재 권한 검사는 멱등 응답을 반환하기 전에도 수행한다.

JSON 요청 상한은1 MiB, 업무 입력/결과 각각256 KiB다. 표에 선택이라고 표시한 필드만 생략할 수 있다. 문자열 길이는 [도메인 타입 규칙](domain-types.md)을 따른다. Boolean 설정은 API에서true/false이며 DB의Y/N으로 매핑한다. 존재하지 않는 자원404, 참조 중 삭제409, 잘못된 값400, 상한413, 큐 용량 초과429다.

## 2. 관리 자원

각 자원은 GET 목록, POST 생성, GET `/{id}` 상세, PUT `/{id}` 수정, DELETE `/{id}`를 제공한다. POST는201+생성 객체+Location, PUT은200+수정 객체, DELETE는204다. 조회의 공통 query는page/pageSize이며 아래 필터를 추가한다.

| 자원 | 필터 | 생성·수정 설정 필드 |
| --- | --- | --- |
| `/runners` | code, name, status | code(Code20), name(Text200), capacity(1~200), status(ACTIVE/PAUSED) |
| `/programs` | code, name, enabled | code(Code20), name(Text200), description(선택/null,4000), enabled, inputSchema(object), outputSchema(선택/null,object) |
| `/jobs` | code, name, programId, runnerId, enabled | 아래 Job 객체 |
| `/schedules` | jobId, enabled | 아래 Schedule 객체 |

Runner POST는 code/name/capacity만 받으며 상태는 REGISTERING으로 생성한다. PUT의code는 기존 값과 같아야 한다. 등록 완료 전 ACTIVE 전환은409다. 상세는 추가로hostname, os, agentVersion, lastSeenAt(nullable UTC), online, session을 반환하며 로그인용 ACCESS 토큰은 반환하지 않는다. capacity를 점유 슬롯보다 작게 줄이거나 미해결 실행이 있는 runner를 삭제하는 요청은409다. REVOKED 전환은 자격증명 폐기 API로만 처리한다.

Program의 code는 생성 후 불변이다. inputSchema/outputSchema는 JSON Schema 2020-12 중 object/properties/required/type/enum/문자열 길이/숫자 범위/additionalProperties/items에 한정한 v1 부분집합으로 검증한다. 원격 `$ref`와 임의 코드는 허용하지 않는다. 스키마 내용 변경 시 version이 증가하고 기존 설치는 재검증·재승인할 때까지 새 Run 배정에 사용할 수 없다. inputContract는 해당 version의 십진 문자열이다.

Job의 생성·수정 본문은 다음과 같다. 모든 필드가 필수이며 description만 선택/null이다. code는 생성 후 불변이다.

```json
{"code":"daily-sales","name":"일별 매출 집계","description":null,"programId":"BP000000000000000001","runnerId":"BR000000000000000001","enabled":true,"input":{"businessDate":"20260919"},"execution":{"timeoutSeconds":3600,"queueTimeoutSeconds":3600,"queueLimit":100,"overlap":"QUEUE","parallelLimit":1,"retryLimit":0,"retryDelaySeconds":30,"retryableCodes":["EXIT_NONZERO"]}}
```

timeoutSeconds/queueTimeoutSeconds/queueLimit/parallelLimit은 양수 integer, retryLimit/retryDelaySeconds는0 이상이다. overlap은QUEUE/SKIP/ALLOW, QUEUE/SKIP의parallelLimit은1이다. input은 Program 규격을 검사하고 retryableCodes는20자 코드 배열이다. Run 생성 시 프로그램·설치·정책·최종 입력을 고정한다. enabled=false로 바꾸면 신규 요청·배정을 차단하고 대기 Run은취소한다. 이미 배정한 Attempt는 start 허가/취소 경합 규칙에 따라 종료 확인하며 실행 중인 작업은 별도 취소 요청이 없으면 유지한다.

Schedule의 본문은 다음과 같다. 모든 필드 필수다. jobId는 생성 후 불변이다.

```json
{"jobId":"BJ000000000000000001","name":"매일 새벽 집계","enabled":true,"cron":"0 0 2 * * *","timezone":"Asia/Seoul","input":{},"misfire":"SKIP","graceSeconds":60}
```

cron은 초·분·시·일·월·요일의6필드, timezone은IANA 이름이다. v1은 `*`, 숫자, 목록, 범위, 간격을 지원하며 일/요일 중 적어도 하나는 `*`여야 한다. 특수문자 `? L W #`, 매크로는400이다. misfire는SKIP/FIRE_ONCE, graceSeconds는0 이상이다. 상세에 nextFireAt/lastFireAt(nullable UTC)를 추가한다. 수정 후 nextFireAt은 커밋 시각 이후로 다시 계산하며 과거 시각을 새로 생성하지 않는다. 비활성화는 이미 접수한 Run을 취소하지 않는다.

입력 재정의는 최상위 key 단위 교체이며 중첩 객체는 통째로 교체한다. null은 삭제 지시가 아니라 실제 JSON 값이므로 최종 Program 규격으로 검증한다. 우선순위는Job 기본값 → Schedule/수동 입력이다. v1은 `{"$context":"previousDate"}`를 스키마의 문자열 필드 값 위치에서만 예약 표현으로 지원한다. 자동 실행은예정 시각, 수동 실행은접수 시각을 요청의IANA 시간대로 변환한 전날 YYYYMMDD 문자열로 해석한다. 다른표현/임의 스크립트는400이다. 재시도/원본 재실행에서는 다시 해석하지 않는다.

설정의 물리 삭제는 실행 이력·다른 설정에서 참조하지 않을 때만 허용한다. 참조가 있으면409로 비활성화를 안내한다. API에서 cascade로 실행 이력을 삭제하지 않는다.

## 3. Runner 등록·설치 승인

| Method / 경로 | 본문 | 성공·규칙 |
| --- | --- | --- |
| POST `/runners/{id}/enrollment` | `{}` | 201 `{credentialId,enrollmentToken,expiresAt}`. 기본10분 유효한1회 토큰. 이전 미사용 등록 토큰은 폐기 |
| POST `/runners/{id}/revoke` | `{reason}` | 200 runner 상세. ACCESS/등록 자격증명 폐기, REVOKED. 실행 종료를 의미하지 않음 |
| GET `/runners/{id}/programs` | 없음 | Page 목록, programId/code/name/version/revision/approvedRevision/availability/enabled/capacity |
| PUT `/runners/{id}/programs/{programId}/approval` | `{revision,enabled,capacity}` | 200 설치 상세. 현재 보고 revision과 같을 때만 승인, 변경 경합은409 |

설치 승인 PUT은key가 필수이고 일반 If-Match 대신 본문의revision과 현재 보고값을 잠금 아래 비교한다. capacity는1~runner capacity다. manifest가 현재 Program 입력 계약과 호환되어야 승인한다.

enrollment는 응답에서 평문 토큰을 한 번만 전달하므로 일반 멱등 재현 대상이 아니다. 응답을 잃으면 사용되지 않은 이전 토큰을 폐기하고 새 요청으로 재발급한다. ACTIVE/PAUSED runner의 재등록은 미해결 Attempt가 없고 명시적으로 폐기한 뒤만 허용한다. 토큰은no-store 응답으로 전달하며 승인 이력·Event에는 값이 아니라credential ID만 기록한다.

## 4. 실행 요청·조회·취소

### POST /jobs/{id}/runs

EXEC 권한과key가 필요하다.

```json
{"input":{"businessDate":"20260919"},"timezone":"Asia/Seoul"}
```

input은필수 object, timezone은예약 표현 해석을 위한필수 IANA 이름이다. 정상 접수는202+Location `/runs/{id}` 및 Run 요약을 반환한다. 없는 Job404, 비활성/미승인 설치409, 잘못된 최종 입력400이다. 온라인이 아니어도 유효한 사용 설정이면 queue timeout까지 대기할 수 있다.

```json
{"id":"BX000000000000000001","jobId":"BJ000000000000000001","trigger":"MANUAL","state":"QUEUED","requestedAt":"2026-09-20T02:00:00Z","scheduledAt":null,"reason":null}
```

SKIP 정책에 따라 이전 비종결 Run이 있으면202/state=SKIPPED로 이력을 남긴다. 처리 완료를 뜻하는200으로 위장하지 않는다. 같은key 재요청은 동일Run이다.

| Method / 경로 | 계약 |
| --- | --- |
| GET `/runs` | Page 목록. jobId, runnerId, state, trigger, from/to(요청 UTC 시각, from포함/to미포함) 필터 |
| GET `/runs/{id}` | 요약 + input, snapshot, firstStartedAt, finishedAt, cancelRequestedAt, activeAttempt(nullable), result, attempts 배열 |
| GET `/runs/{id}/attempts/{attempt}/logs` | query stream, afterSequence(선택), limit(기본100/최대200). `{contents:[{sequence,text,emittedAt}],nextSequence,logState}`. 순번 오름차순; 일반 Page와 달리 스트림 cursor 형식 |
| POST `/runs/{id}/cancel` | `{reason}` 필수 1~4000자. 202 현재 Run 요약. 대기 상태는즉시CANCELED, 실행/배정은CANCEL_REQUESTED. 이미 취소 중/취소됨은 멱등, 다른 종결 결과는409 |
| POST `/runs/{id}/reruns` | `{}` + key, EXEC. 202 새 Run, trigger=RERUN. 원본input/snapshot 유지, parentRunId 연결. 원본은종결이어야 함. 옛revision 미설치/비승인이면409 |
| POST `/runs/{id}/resolve` | 아래 운영 복구 본문 + key, SYS_ADMIN. 200 Run 상세 |

로그의afterSequence는해당 순번 다음부터이며 nextSequence는이번 응답의 마지막 순번이다. 빈 목록이면 전달한cursor(없으면null)를 유지한다. 다른stream 간 순서는보장하지 않으며 보관 만료는410이다. runnerId 필터는 배정된Attempt 기준으로 적어도한번 해당runner를 사용한Run을 의미한다.

Run 상세의 attempts는 `{attempt,assignmentId,runnerId,state,startedAt,finishedAt,exitCode,failure,logState}` 배열이다. Run 상태는[실행 상태 모델](execution.md#3-상태-모델)과 같다. 실제 토큰·secret 값·원본 파일시스템의 불필요한 민감 정보는관리자 조회에도 노출하지 않는다.

```json
{"expectedState":"UNKNOWN","outcome":"FAILED","processStopped":true,"reason":"머신에서 해당 프로세스 종료와 업무 미완료를 확인함"}
```

resolve는UNKNOWN에만 적용한다. outcome은SUCCEEDED/FAILED/CANCELED/TIMED_OUT 중 근거가 있는 결과, processStopped=true 필수이며 사용자 진술과확인 근거를Event에 남긴다. 이는서버가 원격 프로세스 종료를 증명했다는 의미가 아니다. 일반 실행 권한만으로slot을 강제 해제하지 않는다. 결과 불명 중에는새재실행을 자동 생성하지 않는다. 늦은runner 결과가운영 종결을 덮어쓸 수 없다.

## 5. Workflow 확장 시 계약 경계

v1 구현 범위에는 Workflow endpoint를 추가하지 않는다. Runner의 완료 ACK와 admin의 진행 판단을 분리한 계약은 유지한다. 이후 관리자 경로에 WorkflowRun과 승인 요청 조회·승인/거절 명령을 추가하고, 승인 주체·실행 세대·기한·멱등 검사를 [Workflow 설계](workflow-extension.md)대로 적용한다. Runner에게 승인 API나 후속 Job 선택권을 주지 않는다.

## 6. 구현 전 확인

관리자 API의 수동 요청과 스케줄러는 동일 Run 생성 서비스를 사용한다. 멱등 응답 재현, If-Match 경합, 실행 권한, 날짜 표현식, Program schema 검증, 원본 재실행, 로그 cursor를 계약 테스트로 검증한다. 이 문서는 아직 실행 중인 API의 문서가 아니다.
