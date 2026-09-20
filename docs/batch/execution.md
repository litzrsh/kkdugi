# 실행 및 장애 처리 설계

- 상태: 2026-09-20 검토 초안. URL·상태 코드·enum DB 코드는 API/DDL 설계 시 확정.
- [전체 설계](README.md) / [테이블](tables.md)

## 1. 자동·수동 실행의 공통 경로

1. 수동 실행 API 또는 영속 스케줄러가 `requestRun(jobId, inputs, trigger, requestKey)`에 해당하는 기능을 호출한다.
2. 권한·Job 활성화·입력·큐 상한을 검사하고 Job/프로그램/설치 revision과 최종 입력을 Run에 저장한다.
3. 동일 scope/key 재요청은 기존 Run을 반환한다. 내용이 다른 동일 key는 충돌이다. HTTP 요청 성공은 요청 접수이며 프로그램 성공을 뜻하지 않는다.
4. Runner가 빈 슬롯을 가지고 작업을 요청한다. Admin은 실행 가능한 Run을 원자적으로 배정하고 Attempt·lease를 생성한다.
5. Runner가 배정 정보를 로컬에 영속 기록하고 승인된 프로그램 revision을 확인한다. 시작 승인을 받은 뒤 프로세스를 실행하고 실행 상태를 보고한다.
6. 실행 중 heartbeat·로그를 전송한다. 종료 후 종료 코드·결과·마지막 로그 순번을 반복 전송할 수 있다.
7. Admin은 배정 소유권과 상태를 검사해 완료를 한 번만 반영한다. 재시도 여부를 판단하고 Event를 기록한다.

향후 Workflow에서 이 완료 보고는 **진행 판단을 요청하는 이벤트**로 사용한다. Admin이 현재 Attempt의 보고를 검증하고 논리 Run의 최종 상태를 확정한 뒤, Workflow의 선행 조건과 다음 단계 진입 정책을 평가한다. 자동 진행이면 다음 Run을 요청하고, owner 승인이 필요하면 승인 대기를 저장한다. Runner가 다음 Job ID나 자동 진행 여부를 결정하지 않는다. 실패한 Attempt가 재시도 중인 상태는 단계 완료로 취급하지 않는다.

Runner 완료 API의 성공 응답은 결과를 영속적으로 접수했다는 뜻이며 owner 승인이나 다음 Job 완료까지 기다리지 않는다. Admin은 저장된 Run과 Workflow 단계 상태를 대조해 미처리 완료를 재평가할 수 있어야 한다. 승인 대기는 Workflow 단계 상태이며 기존 Job Run/Attempt를 실행 중으로 붙잡거나 runner 슬롯·lease를 유지하지 않는다. [Workflow 진행·승인 규칙](workflow-extension.md)

스케줄러는 대상 schedule을 잠근 뒤 Run 생성 또는 누락 기록과 `next_fire_dtm` 이동을 **한 트랜잭션**으로 처리한다. 여러 admin 인스턴스가 있어도 UQ(schedule, 예정 시각)와 행 잠금으로 같은 발생을 중복 생성하지 않는다. 스케줄 수정도 같은 행 잠금을 거친다. 스케줄 비활성화는 미래 생성만 중단하며 이미 접수한 Run을 취소하지 않는다.

## 2. 배정과 동시 실행

대기 Run 조회에 `FOR UPDATE SKIP LOCKED`를 사용할 수 있다. PostgreSQL은 이를 여러 소비자가 접근하는 큐 같은 테이블의 잠금 경합 회피 용도로 설명한다. 장시간 업무 실행 동안 DB 트랜잭션을 유지하지 않는다. [PostgreSQL 17 SELECT 문서](https://www.postgresql.org/docs/17/sql-select.html)

배정 트랜잭션은 다음을 함께 확인한다.

- Job, runner, 설치의 사용 여부와 승인 revision, runner 세션 일치.
- Run의 배정 가능 시각·대기 만료, 현재 유효 Attempt 부재.
- Job 동시 실행 상한과 runner 및 runner/program 용량.
- Run 상태 변경, Attempt 생성, 슬롯 점유, Event 기록의 원자적 커밋.

Job·runner·설치의 용량 검사와 배정은 해당 부모 행 잠금 아래 수행한다. 단순히 COUNT 후 INSERT하면 다른 admin에서 동시에 배정할 수 있다. 모든 배정·취소·완료·lease 회수 경로가 같은 잠금 순서를 사용해야 하며 순서는 SQL 구현 시 확정·경합 테스트한다. 예를 들어 runner → 설치 → Job → Run → Attempt 순서를 기준으로 삼고, 사전 후보 조회 후 잠금을 획득한 상태에서 조건을 재검증한다. 스케줄 생성 경로가 반대 순서로 잠금을 잡지 않도록 함께 설계한다.

1차 QUEUE는 같은 Job의 이전 비종결 Run이 해결되어야 다음 Run을 시작한다. 재시도 대기·결과 불명도 이전 Run에 포함한다. 이는 FIFO에 가까운 실행이며 전체 시스템의 절대적 순서를 보장하지 않는다. SKIP은 요청 시 이전 비종결 Run이 있으면 SKIPPED로 이력을 남긴다. ALLOW도 Job/runner/프로그램 상한을 초과할 수 없다.

재시도 시에는 해당 Run이 자기 자신을 이전 실행으로 세지 않도록 한다. 큐 만료는 한 번도 시작하지 않은 대기 Run과 재시도 대기에 적용하며, 실행 중 timeout은 별도로 처리한다. 재시도 대기 마감은 재시도 예약 시 snapshot의 queue timeout을 기준으로 다시 계산한다.

## 3. 상태 모델

### Run: 사용자가 보는 실행 상태

| 상태 | 의미·다음 상태 |
| --- | --- |
| QUEUED | 배정 대기 → ASSIGNED / CANCELED / EXPIRED |
| ASSIGNED | 배정 완료, 시작 확인 대기 → RUNNING / CANCEL_REQUESTED / RETRY_WAIT / FAILED / UNKNOWN |
| RUNNING | 실행 중 → SUCCEEDED / FAILED / RETRY_WAIT / CANCEL_REQUESTED / TIMED_OUT / UNKNOWN |
| CANCEL_REQUESTED | 종료 요청 전달·확인 중 → CANCELED / SUCCEEDED / FAILED / UNKNOWN |
| RETRY_WAIT | 종료가 확인된 이전 시도 이후 대기 → ASSIGNED / CANCELED / EXPIRED |
| UNKNOWN | 실행 여부·종료 결과 불명 → 확인된 결과 또는 운영자 조치로 종결 |
| SUCCEEDED / FAILED / CANCELED / TIMED_OUT / EXPIRED / SKIPPED | 종결 상태. 수동 재실행은 새 Run 생성 |

timeout 시에도 프로세스 트리 종료 확인 전에는 TIMED_OUT으로 종결하지 않는다. 종료 요청 원인을 Event에 기록하고, 통신/종료 확인이 안 되면 UNKNOWN이다. 실행 전 명확한 파일 누락 같은 실패는 Attempt FAILED로 처리할 수 있다.

### Attempt: Runner의 시도 상태

`ASSIGNED → RUNNING → SUCCEEDED / FAILED / CANCELED / TIMED_OUT`을 기본으로 한다. 시작 전 취소·검증 실패도 각각 CANCELED/FAILED로 종료할 수 있다. lease 만료·프로세스 상태 유실은 LOST이며 Run은 UNKNOWN으로 바뀐다. LOST는 실제 종료를 뜻하지 않아 슬롯을 유지한다.

UNKNOWN에서 같은 배정의 지연된 완료 보고가 오면 아직 운영자 해결이나 후속 실행이 없는지 확인하고 정상 결과로 조정할 수 있다. 모든 조정은 Event에 근거를 남긴다. 이미 해결된 시도의 늦은 응답은 현재 결과를 덮어쓰지 않고 충돌/과거 증거로 기록한다.

## 4. 실패·재시도·중복 방지

**임의의 외부 프로그램에 대해 업무 효과가 정확히 한 번만 발생한다고 보장하지 않는다.** DB가 배정·상태 변경 중복을 막아도 프로세스 실행과 외부 업무 DB 커밋을 하나의 트랜잭션으로 묶을 수 없기 때문이다.

| 상황 | 처리 |
| --- | --- |
| 수동 버튼/HTTP 재전송 | 요청 key로 기존 Run 반환, 서로 다른 실행 요청이면 새 key 사용 |
| 작업 배정 응답 유실 | Runner가 동일 미해결 배정을 조회·복구, lease 만료만으로 다른 runner에 재배정하지 않음 |
| 시작 승인 응답 유실 | 동일 assignment에 대한 시작 승인 재조회; 승인 여부가 불명하면 새 프로세스를 시작하지 않음 |
| 프로세스 실행 직후 runner 장애 | 로컬 journal과 OS 프로세스 상태로 복구, 확인 불가하면 UNKNOWN |
| 실행 종료 코드 실패 | 종료 확인·retry 대상·남은 횟수 확인 후 같은 Run의 다음 Attempt 예약 |
| 완료 보고 응답 유실 | 같은 Attempt 완료를 재전송, 내용 hash가 다르면 충돌 |
| Runner 통신 단절 | 신규 배정 중지, lease 만료 후 LOST/UNKNOWN, 자동 재시도 금지 |
| Admin 재시작 | PostgreSQL 큐·스케줄·lease에서 복구; runner는 로컬 로그·결과를 재전송 |
| 로그 전송 실패 | 로컬 크기 제한 spool에 보관·재전송, 로그 미완성과 업무 결과를 따로 표시 |

Runner는 `runId`, `attemptNo`, `assignmentKey`, `runnerSession`, 업무 idempotency key를 실행 컨텍스트로 전달한다. 자동 재시도는 같은 업무 key를 유지한다. 수동 재실행은 기본 새 요청이지만, 프로그램이 일자·전표 번호 등 업무 key를 사용해 중복 처리를 막을 수 있어야 한다.

lease 갱신·시작·완료·로그 보고는 해당 runner identity와 배정 세대에만 허용한다. 이전 세션의 보고는 일반 갱신으로 받지 않고 재시작 복구 절차에서 기존 journal과 배정을 대조한다. admin은 만료된 소유권을 가진 runner의 외부 DB 쓰기까지 차단할 수 없으므로 fencing 값만으로 중복 업무 효과가 해결된다고 가정하지 않는다.

UNKNOWN을 운영자가 종결할 때는 머신의 프로세스 종료와 업무 처리 결과를 확인하고 사유를 기록한다. 확인되지 않은 프로세스가 있는데 슬롯만 해제하는 기능은 기본 제공하지 않는다. 별도 강제 해제 기능이 필요하면 위험을 드러내는 권한과 감사 이력을 설계한다.

## 5. 취소·timeout·runner 중지

- 대기 Run은 즉시 CANCELED로 변경한다. 배정된 뒤에는 시작 승인 단계에서도 취소 여부를 확인한다.
- 실행 중 취소는 CANCEL_REQUESTED를 기록하고 runner heartbeat 응답으로 전달한다. Runner는 정상 종료 요청 후 유예 시간이 지나면 프로세스 트리 강제 종료를 시도한다.
- timeout은 runner의 로컬 경과 시간 기준으로도 집행한다. admin 연결 장애가 timeout을 무력화하지 않게 한다.
- 취소 전에 프로세스가 이미 성공했다면 SUCCEEDED를 인정하고 취소 경합을 Event에 남긴다. 늦은 취소 요청이 확인된 성공을 덮어쓰지 않는다.
- runner PAUSED는 새 배정만 차단한다. 인증키 폐기는 새 인증을 막으며 이미 실행 중인 OS 프로세스가 즉시 종료되었다는 뜻은 아니다.
- runner 종료 시 신규 수신을 중단하고 기존 작업을 drain하는 정책을 기본으로 한다. 강제 재시작 후에는 PID만으로 실행 여부를 판단하지 않는다.

## 6. 실행 입출력

입력은 크기가 제한된 JSON 객체이며 프로그램 계약에 따라 argv/환경 변수/입력 파일로 변환한다. 비밀 값은 runner가 현지에서 해석하며 admin의 Run·로그·Event에 평문 저장하지 않는다. 로그 마스킹은 유출을 완전히 방지하는 수단은 아니므로 프로그램 자체도 비밀 값을 출력하지 않아야 한다.

구조화 결과는 선택 사항이다. 일반 프로그램은 종료 코드와 로그만으로 실행 가능하다. 결과가 필요한 프로그램은 runner가 지정한 결과 파일 등에 JSON을 기록하고, runner가 크기·스키마를 검증해서 보고한다. stdout을 자동으로 JSON 결과라고 가정하지 않는다. 큰 결과는 후속 artifact 참조를 사용한다.

수동 재실행은 기본적으로 원본 Run의 해석 완료 입력과 실행 설정을 복사한 새 Run이다. 이전 revision이 더 이상 승인·설치되어 있지 않으면 거절한다. 현재 Job 설정으로 실행하려면 별도 신규 실행 요청으로 명확히 구분한다. 자동 재시도는 반드시 원본 snapshot을 유지한다.

## 7. API 영역과 프로젝트 적용

| 영역 | 경로 후보·기능 |
| --- | --- |
| 관리자 | `/api/v1.0/admin/batch/*`: runner, program, job, schedule, run 관리·조회·실행·취소 |
| Runner 통신 | `/api/v1.0/batch-agent/*`: 등록, 설치 보고, heartbeat, claim, 시작 확인, 로그·결과 보고 |

Runner 인증은 사용자 브라우저 세션/JWT와 별개이며 runner 전용 범위의 API만 허용한다. 기존 Spring Security 설정에 독립된 machine 인증 경로를 추가하는 설계가 필요하다. Runner 자격증명으로 관리자 API를 호출할 수 없어야 한다.

배치 관련 backend는 우선 `kkdugi.app.admin.batch` 아래 `models`, `mapper`, `service`, `exceptions`, 필요한 `config`로 둔다. 관리 API와 machine API의 컨트롤러·서비스 진입점과 권한 검사는 분리하되 같은 배치 도메인의 상태 전이 서비스를 재사용한다. 배치 CRUD를 `core`로 이동시키거나 `app.batch`가 `app.admin.batch`를 import하는 구조를 만들지 않는다. Workflow도 우선 같은 배치 도메인의 orchestration 서비스로 확장할 수 있다.

컨트롤러는 기존 flat 규칙에 따라 `kkdugi.api.admin`에 둔 관리 컨트롤러와 `kkdugi.api`에 둔 machine 전용 컨트롤러로 제안한다. 패키지 위치가 권한을 의미하지 않으며 URL·SecurityFilterChain으로 접근을 분리한다. Runner 실행 프로그램 자체는 별도 저장소/프로젝트다.

새 도메인은 [공통 모델 규약](../conventions/common-base-model.md)과 [ADR-0016](../adr/0016-app-and-admin-feature-split.md)을 따른다. `BaseModel`/`BaseParams`/`Page<T>`, params 정규화, `COUNT(*) OVER()`, `SerialConfig`, `CodeEnums`, 역할별 패키지와 MyBatis 경로를 재사용한다. 상태명은 문서상 의미 이름이며 실제 DB enum 코드는 구현 시 정한다. record를 추가하지 않는다.

## 8. 구현 완료 시 검증할 시나리오

- 같은 요청 key·같은 예정 시각을 두 admin이 동시에 접수해도 Run은 하나다.
- 두 claim 요청이 경합해도 같은 Run이 이중 배정되지 않고 Job/runner/프로그램 용량을 지킨다.
- 배정·시작·완료 응답 유실과 runner 재시작 시 같은 프로세스를 무조건 새로 실행하지 않는다.
- lease 만료 시 UNKNOWN으로 표시하고 중복 재실행·다음 QUEUE 작업을 차단한다.
- 완료 보고·로그 chunk 재전송이 멱등이며 다른 내용의 같은 key는 충돌한다.
- 실패 재시도는 snapshot을 유지하고 최대 횟수·대기 마감을 지킨다.
- 취소/성공 경합, timeout 후 프로세스 잔존, 프로그램 revision 변경을 처리한다.
- admin 중단 후 스케줄 누락, 시간대/DST, 로그 한도·보관 정리를 검증한다.
- 다른 runner identity로 결과 보고하거나 사용자 인증으로 machine API를 호출할 수 없다.
- Workflow 확장 시: 완료 이벤트 중복·admin 재시작에도 다음 Run/승인 요청이 중복 생성되지 않고, owner 승인 전에는 다음 Job이 배정되지 않는다. 승인·거절·만료·취소 경합도 검증한다.

실제 코드 구현 시 PostgreSQL을 기동하고 프로젝트 wrapper의 `./mvnw.cmd -B -ntp test` 및 해당 JS 계약 테스트를 실행한다. 이 초안은 실행 코드나 마이그레이션을 추가하지 않는다.
