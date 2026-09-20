# 배치 테이블 설계

- 상태: 2026-09-20 논리 설계 초안. 아직 Flyway migration이 아니다.
- [전체 설계](README.md) / [실행 규칙](execution.md) / [Workflow 확장](workflow-extension.md)

## 1. 관계와 공통 규칙

| 관계 | 카디널리티 |
| --- | --- |
| Runner → Credential | 1:N, 등록용·운영용 자격증명 이력 |
| Runner ↔ Program | N:M, RunnerProgram으로 연결 |
| Program → Job | 1:N |
| RunnerProgram → Job | 1:N, 1차는 특정 설치를 실행 대상으로 선택 |
| Job → Schedule | 1:N |
| Job → Run | 1:N |
| Schedule → Run | 1:N, 수동 실행은 schedule 없음 |
| Run → Attempt | 1:N, 배정 전·건너뛴 실행은 0개 |
| Attempt → Log | 1:N |
| Event → 대상 객체 | 대상 종류·ID로 참조하는 감사 이력 |

테이블은 요청에 따라 `kkdugi_` 접두사를 붙인 **`kkdugi_batch_*`**로 통일한다. 아래 컬럼은 DB 이름이며 API에서는 `id`, `name`, `status`, `createdAt` 등의 별도 의미 이름으로 매핑한다. 컬럼별 도메인·길이·PostgreSQL 타입은 [domains.xml](../../domains.xml)을 참조한 [도메인 적용 규칙](domain-types.md)에 따른다. enum 저장 코드·ID 값의 prefix는 DDL 단계에서 확정하되 새 배치 ID는 20자 이내로 생성한다.

- 독립 식별자가 필요한 엔티티는 `SerialConfig` / `SerialUtils.next(config)`를 재사용한다. 복합키의 attempt 번호·로그 순번은 부모 내 순번이며 새 전역 ID 생성 체계를 만들지 않는다.
- 모든 테이블에 아래 공통 감사 컬럼을 적용한다. 개별 표에는 반복하지 않는다. 사용자 변경은 사용자 ID, 자동 작업은 예약 시스템 actor, runner 변경은 runner actor로 기록한다.
- 업무 시각은 `timestamptz(6)`. 기존 `BaseModel` 감사 시각은 `timestamp(6) without time zone` 매핑을 유지하며 업무 시각과 혼동하지 않는다.
- `*_data`는 JSONB 제안. 입력·스냅샷처럼 가변 구조에만 사용하며 FK·상태·배정·검색 조건은 일반 컬럼에 둔다. JSON 형태·크기·허용 키를 검증한다.
- 실행에 참조된 설정은 비활성화로 보존한다. FK는 기본 RESTRICT이며 관리 화면 삭제가 실행 이력을 연쇄 삭제하지 않는다.
- 다음 표에서 PK/FK/UQ를 명시한다. 선택 사항이라고 적지 않은 핵심 컬럼은 NOT NULL을 기본으로 검토한다.

### 공통 감사 컬럼

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `reg_dtm` | Timestamp | `timestamp(6) without time zone` | 등록일시, NOT NULL, UTC 기준 |
| `reg_id` | ID (기존 사용자 호환) | `varchar(60)` | 등록 actor ID, NOT NULL |
| `upd_dtm` | Timestamp | `timestamp(6) without time zone` | 수정일시, 수정 전 NULL |
| `upd_id` | ID (기존 사용자 호환) | `varchar(60)` | 수정 actor ID, 수정 전 NULL |

감사자 ID의 60자는 기존 사용자 PK·감사 컬럼과의 호환 예외다. 새 배치 PK/FK의 ID 도메인 길이 20자를 변경하는 근거로 사용하지 않는다. actor 종류는 Event의 `actor_type`으로 구분하고 ID에 접두사를 무제한 결합하지 않는다.

## 2. 필수 테이블 11개

### 2.1 `kkdugi_batch_runner` — Runner

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `runner_id` | ID | `varchar(20)` | PK |
| `runner_cd` | Code | `varchar(20)` | 운영 코드 UQ |
| `runner_nm` | Text field | `varchar(200)` | 표시 이름 |
| `runner_stat` | Code | `varchar(20)` | REGISTERING / ACTIVE / PAUSED / REVOKED |
| `host_nm` | Text field | `varchar(200)` | 머신 이름, 등록 전 NULL 가능 |
| `os_cd` | Code | `varchar(20)` | OS 코드, 등록 전 NULL 가능 |
| `agent_ver` | Text field (Short) | `varchar(50)` | Runner 버전, 등록 전 NULL 가능 |
| `capacity_cnt` | Integer | `integer` | 동시 배정 최대 수, 양수 |
| `session_ver` | Integer (범위 확장) | `bigint` | 재등록/재시작 시 발급되는 세션 세대, 이전 세션 구분 |
| `boot_ref` | Text field (Short) | `varchar(50)` | 현재 runner 기동 UUID. 같은 세션 개설 요청의 재전송 판별, 최초 개설 전 NULL |
| `last_seen_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 마지막 인증된 heartbeat, 등록 전 NULL |
| `config_ver` | Integer (범위 확장) | `bigint` | 관리 설정 낙관적 잠금 버전 |

ONLINE/OFFLINE은 `last_seen_dtm`으로 계산한다. heartbeat가 ACTIVE/PAUSED를 덮어쓰지 않는다. 동일 runner identity의 중복 실행은 로컬 단일 인스턴스 잠금과 서버 세션 세대로 차단한다.

### 2.2 `kkdugi_batch_runner_credential` — 등록 및 통신 자격증명

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `credential_id` | ID | `varchar(20)` | PK, 외부 토큰의 조회용 식별 부분 |
| `runner_id` | ID | `varchar(20)` | FK → kkdugi_batch_runner |
| `credential_type` | Code | `varchar(20)` | ENROLLMENT / ACCESS |
| `secret_hash` | Encrypted field | `varchar(2048)` | 충분히 무작위인 토큰의 검증용 hash, 평문 미저장 |
| `expires_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 만료 시각 |
| `consumed_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 등록 토큰 사용 시각, 미사용이면 NULL |
| `revoked_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 폐기 시각, 선택 |
| `last_used_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 마지막 사용 시각, 선택 |

등록 토큰은 원자적으로 한 번만 소비한다. 인증키는 발급 응답에서 한 번 제공하고 runner의 제한된 권한 파일에 보관한다. 교체 시 짧은 유예 기간 동안 두 ACCESS 키를 허용할 수 있다. 등록 응답 유실 시 사용한 토큰을 재사용하지 않고 관리자가 새 토큰을 발급한다.

### 2.3 `kkdugi_batch_program` — 논리 프로그램

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `program_id` | ID | `varchar(20)` | PK |
| `program_cd` | Code | `varchar(20)` | 프로그램 코드 UQ |
| `program_nm` | Text field | `varchar(200)` | 프로그램 이름 |
| `description` | Text field (Long) | `varchar(4000)` | 설명, 선택 |
| `use_yn` | Boolean | `char(1)` | 신규 실행 허용 여부 |
| `input_schema_data` | Clob (JSON 확장) | `jsonb` | 입력 키·타입·필수·기본값·허용 범위, 계약 revision 포함 |
| `output_schema_data` | Clob (JSON 확장) | `jsonb` | 선택적 구조화 결과 규격, 없으면 NULL |
| `config_ver` | Integer (범위 확장) | `bigint` | 수정 버전 |

실행 파일의 머신 경로를 이 테이블에 넣지 않는다. 입력 규격의 호환되지 않는 변경은 설치 승인 및 Job 재검증을 요구한다. 스키마 변경 이전의 Run은 저장된 스냅샷을 유지한다.

### 2.4 `kkdugi_batch_runner_program` — 머신별 프로그램 설치

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `runner_id` | ID | `varchar(20)` | 복합 PK, FK → kkdugi_batch_runner(runner_id) |
| `program_id` | ID | `varchar(20)` | 복합 PK, FK → kkdugi_batch_program(program_id) |
| `program_ver` | Text field (Short) | `varchar(50)` | 설치 버전 |
| `manifest_rev` | Text field | `varchar(200)` | 실행 정의·바이너리 digest·입력 계약을 식별하는 revision |
| `manifest_data` | Clob (JSON 확장) | `jsonb` | 실행 파일, 고정 argv, 작업 디렉터리, 입력 매핑, 비밀 참조 이름. 비밀 값 제외 |
| `reported_stat` | Code | `varchar(20)` | AVAILABLE / MISSING / INVALID, runner 보고값 |
| `approved_rev` | Text field | `varchar(200)` | admin이 승인한 revision, 승인 전 NULL |
| `use_yn` | Boolean | `char(1)` | 관리자가 설정하는 사용 여부 |
| `capacity_cnt` | Integer | `integer` | 해당 runner/program의 동시 실행 상한, 양수 |
| `reported_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 마지막 설치 확인 시각 |

Runner 보고는 `approved_rev`, `use_yn`을 변경하지 못한다. `manifest_rev = approved_rev`이고 파일 확인에 성공해야 배정한다. 기존 실행은 Attempt의 manifest 스냅샷을 사용한다. 파일 교체는 기존 프로세스와 분리된 버전 디렉터리로 수행하고 실행 직전에 revision/digest를 검증한다.

### 2.5 `kkdugi_batch_job` — 실행 정의

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `job_id` | ID | `varchar(20)` | PK |
| `job_cd` | Code | `varchar(20)` | Job 코드 UQ |
| `job_nm` | Text field | `varchar(200)` | Job 이름 |
| `description` | Text field (Long) | `varchar(4000)` | 설명, 선택 |
| `program_id` | ID | `varchar(20)` | 설치 복합 FK의 프로그램 ID |
| `target_runner_id` | ID | `varchar(20)` | 설치 복합 FK의 runner ID |
| `use_yn` | Boolean | `char(1)` | 사용 여부, Y/N |
| `config_ver` | Integer (범위 확장) | `bigint` | 설정 수정 버전, 낙관적 잠금 |
| `default_input_data` | Clob (JSON 확장) | `jsonb` | 기본 업무 입력, 빈 객체 허용 |
| `run_timeout_sec` | Integer | `integer` | 실행 제한 시간(초), 양수 |
| `queue_timeout_sec` | Integer | `integer` | 배정 대기 제한 시간(초), 양수 |
| `queue_limit_cnt` | Integer | `integer` | 대기 요청 개수 상한, 양수 |
| `overlap_policy` | Code | `varchar(20)` | QUEUE / SKIP / ALLOW |
| `parallel_limit_cnt` | Integer | `integer` | ALLOW일 때 Job 동시 실행 상한, 기본 1 |
| `retry_limit_cnt` | Integer | `integer` | 자동 재시도 횟수, 0 이상 |
| `retry_delay_sec` | Integer | `integer` | 재시도 대기 간격(초), 0 이상 |
| `retryable_codes_data` | Clob (JSON 확장) | `jsonb` | 종료가 확인된 실패 중 재시도 가능한 코드 목록 |

`(target_runner_id, program_id)`는 `kkdugi_batch_runner_program(runner_id, program_id)`를 참조한다. Job이 사용 중이어도 설치 비승인/runner 중지 상태면 실행 배정을 막는다. Job 비활성화 시 신규 요청·배정을 차단하고 아직 시작하지 않은 Run은 취소한다. 이미 실행 중인 Attempt는 별도 취소 요청이 없으면 계속한다.

### 2.6 `kkdugi_batch_schedule` — 자동 실행 예약

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `schedule_id` | ID | `varchar(20)` | PK |
| `job_id` | ID | `varchar(20)` | FK → kkdugi_batch_job |
| `schedule_nm` | Text field | `varchar(200)` | 스케줄 이름 |
| `cron_expr` | Text field | `varchar(200)` | 초 포함 6필드 cron 식 |
| `timezone_id` | Text field | `varchar(200)` | IANA 시간대 이름. 엔티티 ID가 아니므로 ID 도메인 미사용 |
| `use_yn` | Boolean | `char(1)` | 사용 여부, Y/N |
| `config_ver` | Integer (범위 확장) | `bigint` | 설정 수정 버전, 낙관적 잠금 |
| `input_data` | Clob (JSON 확장) | `jsonb` | 해당 스케줄의 입력 재정의, 빈 객체 허용 |
| `misfire_policy` | Code | `varchar(20)` | SKIP / FIRE_ONCE |
| `grace_sec` | Integer | `integer` | 정상 스케줄 지연으로 인정할 유예 시간(초), 0 이상 |
| `next_fire_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 다음 예정 시각 |
| `last_fire_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 마지막 처리한 예정 시각, 처리 전 NULL |

FIRE_ONCE는 누락 구간의 가장 최근 예정 시각 한 번만 생성한다. SKIP은 유예를 초과한 구간을 건너뛰고 Event에 범위·사유를 남긴다. 개별 누락 시각마다 수천 개의 Run을 생성하지 않는다. 전체 소급 실행은 후속 기능이다.

### 2.7 `kkdugi_batch_run` — 논리적 실행 요청·결과

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `run_id` | ID | `varchar(20)` | PK |
| `job_id` | ID | `varchar(20)` | FK → kkdugi_batch_job |
| `schedule_id` | ID | `varchar(20)` | FK → kkdugi_batch_schedule, 스케줄 실행만 값 있음 |
| `trigger_type` | Code | `varchar(20)` | MANUAL / SCHEDULE / RERUN, 후속 WORKFLOW 추가 |
| `request_scope` | Text field | `varchar(200)` | 요청 중복 방지 범위, request_key와 복합 UQ |
| `request_key` | Text field | `varchar(200)` | 범위 내 요청 중복 방지 키, request_scope와 복합 UQ |
| `request_hash` | Text field | `varchar(200)` | 같은 key에 다른 요청 본문이 오면 충돌 판정 |
| `parent_run_id` | ID | `varchar(20)` | 재실행 원본 FK → kkdugi_batch_run, 그 외 NULL |
| `requested_by` | ID (기존 사용자 호환) | `varchar(60)` | 요청 actor ID, 기존 사용자 ID 60자와 호환 |
| `requested_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 요청 시각 |
| `scheduled_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 예정 시각, 수동 실행은 NULL |
| `run_stat` | Code | `varchar(20)` | 현재 실행 상태 |
| `reason_cd` | Code | `varchar(20)` | 결과/운영 사유 코드, 선택 |
| `reason_text` | Text field (Long) | `varchar(4000)` | 결과/운영 사유 설명, 선택 |
| `config_snapshot_data` | Clob (JSON 확장) | `jsonb` | Job revision·프로그램 입력 계약·실행 대상·정책·승인 manifest revision 스냅샷 |
| `resolved_input_data` | Clob (JSON 확장) | `jsonb` | 검증·표현식 해석 완료한 최종 입력, 비밀 값 제외 |
| `result_data` | Clob (JSON 확장) | `jsonb` | 성공 시 선택적 구조화 결과, 로그와 분리 |
| `available_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 최초 또는 재시도 배정 가능 시각 |
| `queue_deadline_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 배정 대기 만료 시각 |
| `first_started_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 첫 실행 시작 시각, 시작 전 NULL |
| `finished_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 종료 시각, 종료 전 NULL |
| `cancel_requested_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 취소 요청 시각, 요청 전 NULL |
| `active_attempt_no` | Integer | `integer` | 현재 시도 번호, 아직 배정되지 않았으면 NULL |

Run은 요청의 스냅샷이며 설정 변경으로 수정하지 않는다. `active_attempt_no`는 run의 시도만 가리키도록 복합 FK `(run_id, active_attempt_no)`를 검토한다. 삽입 순서에 맞게 NULL 후 같은 트랜잭션에서 설정한다.

추가 UQ: `(schedule_id, scheduled_dtm)` WHERE schedule_id IS NOT NULL. Schedule의 job과 Run의 job 일치를 복합 FK로 보장한다. MANUAL/RERUN은 요청자 범위의 key, SCHEDULE은 schedule ID+예정 UTC 시각으로 key를 만든다. 보관 정리로 dedupe 범위가 사라지는 시점은 API 보장 기간과 함께 명시해야 한다.

### 2.8 `kkdugi_batch_attempt` — 실제 실행 시도·임대

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `run_id` | ID | `varchar(20)` | 복합 PK, FK → kkdugi_batch_run |
| `attempt_no` | Integer | `integer` | 복합 PK, Run 내 시도 순번, 양수 |
| `runner_id` | ID | `varchar(20)` | 실행한 설치의 복합 FK → kkdugi_batch_runner_program |
| `program_id` | ID | `varchar(20)` | 실행한 설치의 복합 FK → kkdugi_batch_runner_program |
| `runner_session_ver` | Integer (범위 확장) | `bigint` | 배정 세션 세대 |
| `assignment_key` | ID | `varchar(20)` | 재시도에서 재사용하지 않는 배정 ID, UQ. SerialUtils로 생성 |
| `attempt_stat` | Code | `varchar(20)` | ASSIGNED / RUNNING / SUCCEEDED / FAILED / CANCELED / TIMED_OUT / LOST |
| `lease_until_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 배정 임대 만료 시각 |
| `heartbeat_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 해당 배정의 마지막 heartbeat, 최초 수신 전 NULL |
| `assigned_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 배정 시각 |
| `start_permit_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 최초 시작 허가 시각, 허가 전 NULL. 재전송으로 변경하지 않음 |
| `start_deadline_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 시작 허가 만료 시각, 허가 전 NULL. 배정 lease 이하 |
| `started_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 프로세스 시작 시각, 시작 전 NULL |
| `finished_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 종료 시각, 종료 전 NULL |
| `process_ref` | Text field | `varchar(200)` | PID + 시작 시각 등 로컬 프로세스 식별, 시작 전 NULL |
| `manifest_snapshot_data` | Clob (JSON 확장) | `jsonb` | 실제 승인된 프로그램 버전·digest·실행 정의, 비밀 값 제외 |
| `exit_code` | Integer (범위 확장) | `bigint` | 종료 코드, 종료 전 NULL. OS별 32비트 unsigned 값도 수용하도록 bigint 사용 |
| `failure_cd` | Code | `varchar(20)` | 실패 사유 코드, 선택 |
| `failure_text` | Text field (Long) | `varchar(4000)` | 실패 사유 설명, 선택 |
| `result_data` | Clob (JSON 확장) | `jsonb` | 해당 시도의 선택적 구조화 결과, 결과 없으면 NULL |
| `completion_hash` | Text field | `varchar(200)` | 완료 보고 중복/충돌 확인 hash, 완료 전 NULL |
| `slot_held_yn` | Boolean | `char(1)` | 실행 가능성이 남아 runner/program 용량을 점유하는지 여부 |
| `log_stat` | Code | `varchar(20)` | COMPLETE / TRUNCATED / PENDING / LOST |
| `last_log_seq` | Clob (JSON 확장) | `jsonb` | stream별 마지막 수신 순번 JSON 객체. 값은 bigint 범위의 비음수 정수 |

한 Run의 `slot_held_yn = Y`인 Attempt는 최대 하나로 partial UQ를 둔다. LOST도 프로세스가 살아있을 수 있으므로 임의로 슬롯을 해제하지 않는다. lease는 배정 권한의 유효기간이며 프로세스 종료 증거가 아니다.

### 2.9 `kkdugi_batch_attempt_log` — 로그 chunk

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `run_id` | ID | `varchar(20)` | 복합 PK의 일부, (run_id, attempt_no) FK → kkdugi_batch_attempt |
| `attempt_no` | Integer | `integer` | 복합 PK의 일부, (run_id, attempt_no) FK → kkdugi_batch_attempt |
| `stream_cd` | Code | `varchar(20)` | 복합 PK의 일부, STDOUT / STDERR |
| `chunk_seq` | Integer (범위 확장) | `bigint` | 복합 PK의 일부, stream별 chunk 순번, 0 이상 |
| `log_text` | Clob | `text` | 마스킹된 로그 chunk |
| `content_hash` | Text field | `varchar(200)` | 로그 중복/충돌 검증 hash |
| `byte_size` | Integer | `integer` | UTF-8 로그 chunk 바이트 크기, 0 이상 |
| `emitted_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | Runner 로그 발생 시각 |
| `received_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | Admin 로그 수신 시각 |

같은 순번·같은 내용은 성공으로 응답하고 다른 내용은 충돌로 처리한다. stdout/stderr 사이 전체 순서는 보장하지 않는다. runner의 마지막 순번 보고는 stream별로 보관한다(`last_log_seq`는 stream→순번 구조). chunk 크기와 누적량을 제한하며 잘린 로그가 정상적인 전체 로그로 표시되지 않게 한다.

### 2.10 `kkdugi_batch_event` — 상태 전이·운영 감사

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `event_id` | ID | `varchar(20)` | PK |
| `target_type` | Code | `varchar(20)` | RUNNER / PROGRAM / INSTALLATION / JOB / SCHEDULE / RUN / ATTEMPT. Workflow 확장 시 WORKFLOW / WORKFLOW_RUN / WORKFLOW_STEP / APPROVAL 추가 |
| `target_id` | Text field | `varchar(200)` | 다형 대상 참조. 복합키는 정규화한 문자열로 표현하므로 ID(20) 대신 Text field 사용 |
| `run_id` | ID | `varchar(20)` | FK → kkdugi_batch_run, 실행 이벤트가 아니면 NULL |
| `attempt_no` | Integer | `integer` | (run_id, attempt_no) FK → kkdugi_batch_attempt, 시도 이벤트가 아니면 NULL |
| `event_type` | Code | `varchar(20)` | 변경 종류 코드 |
| `from_stat` | Code | `varchar(20)` | 이전 상태 코드, 상태 없는 변경은 NULL |
| `to_stat` | Code | `varchar(20)` | 이후 상태 코드, 상태 없는 변경은 NULL |
| `actor_type` | Code | `varchar(20)` | USER / RUNNER / SYSTEM |
| `actor_id` | ID (기존 사용자 호환) | `varchar(60)` | Actor ID, 기존 사용자 ID 60자와 호환 |
| `occurred_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 이벤트 발생 시각 |
| `detail_data` | Clob (JSON 확장) | `jsonb` | 변경 요약, 원인, 운영자 조치 사유. 인증키·비밀 값 제외 |

수정 불가 이력으로 저장한다. 대상 종류/ID는 다형 참조라 일반 FK를 걸 수 없으며 서비스 검증과 삭제 제한으로 보완한다. 상태 변경과 해당 Event는 같은 트랜잭션에서 저장한다. 이는 메시지 발행용 outbox가 아니며 외부 알림 도입 시 별도 전달 보장 설계를 한다.

### 2.11 `kkdugi_batch_api_request` — API 명령의 멱등 응답

API 설계에서 추가한 테이블이다. Run 생성만 중복 방지해서는 claim 응답 유실, 빈 claim(204), 설정 생성/수정의 재전송을 처리할 수 없어 응답 기록을 별도로 둔다. 상태 변경과 응답 기록은 같은 트랜잭션이다.

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `request_id` | ID | `varchar(20)` | PK, SerialUtils 생성 |
| `subject_type` | Code | `varchar(20)` | USER / RUNNER |
| `subject_id` | ID (기존 사용자 호환) | `varchar(60)` | 인증 주체 ID. 사용자 또는 runner라 직접 FK 대신 서비스 검증 |
| `operation_hash` | Text field | `varchar(200)` | HTTP method+정규화path의SHA-256 hex64, 형식 CHECK |
| `request_key` | Text field | `varchar(200)` | Idempotency-Key |
| `request_hash` | Text field | `varchar(200)` | 본문·세션·프로토콜·생성시각 등의 의미상 동일성 hash |
| `request_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 최초 요청의 key 생성 시각 |
| `http_status` | Integer | `integer` | 재현할 HTTP 상태, 200~299 성공 범위 |
| `response_data` | Clob (JSON 확장) | `jsonb` | 응답 본문, 204면NULL. 토큰 발급 응답은 저장 대상 아님 |
| `location_text` | URL | `varchar(2000)` | 응답 Location, 없으면NULL |
| `expires_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 수신 기준72시간 후. 만료 후 정리 가능 |

UQ `(subject_type, subject_id, operation_hash, request_key)` 및 정리용 `(expires_dtm)` 인덱스. 같은 key 경합은 트랜잭션 잠금/UQ로 직렬화한다. 실패로 롤백한 요청은 성공 응답 기록도 남지 않는다. 일회성 토큰 발급·heartbeat·세션 개설·로그복합키 API는 각자의 별도 멱등 규칙을 사용한다.

만료 정리 후 오래된 key를 새 명령으로 받아들이지 않도록 `X-Request-Created-At`의 신규 요청 허용 창을 함께 검사한다. 자세한 규칙은 [Runner API](runner-api.md#2-멱등성과-타이밍)에 있다. 미해결 Run/Attempt의 보관을 이 테이블 만료와 연결하지 않는다.

## 3. 주요 인덱스·무결성

| 대상 | 제안 |
| --- | --- |
| Schedule 조회 | `(next_fire_dtm, schedule_id)` WHERE use_yn = Y |
| 배정 대기 Run | `(available_dtm, requested_dtm, run_id)` WHERE run_stat IN (QUEUED, RETRY_WAIT) |
| Job 실행 이력 | `(job_id, requested_dtm DESC, run_id)` |
| 동시 실행 확인 | Run `(job_id, run_stat)`, Attempt `(runner_id, program_id)` WHERE slot_held_yn = Y |
| lease 만료 확인 | Attempt `(lease_until_dtm)` WHERE slot_held_yn = Y |
| 로그·이벤트 | 로그 복합 PK, Event `(run_id, occurred_dtm, event_id)`, `(target_type, target_id, occurred_dtm)` |
| 정리 | Run `(finished_dtm)`, 로그 `(received_dtm)`, Event `(occurred_dtm)` |

상태별 timestamp nullability, 양수 timeout/capacity, 비음수 retry 수, trigger별 schedule/parent 필수 조건을 CHECK와 서비스 검증으로 보완한다. runner capacity·Job 동시 실행 상한은 여러 행을 세는 규칙이므로 단순 CHECK로 보장하지 않고 배정 트랜잭션의 잠금으로 검사한다.

## 4. 후속 확장 테이블

| 테이블 | 필요해지는 시점 |
| --- | --- |
| `kkdugi_batch_runner_pool`, `kkdugi_batch_runner_pool_member` | 여러 runner 중 실행 대상을 선택할 때 |
| `kkdugi_batch_job_parameter` | JSON 입력 규격 대신 개별 필드를 검색·관리할 필요가 생길 때 |
| `kkdugi_batch_artifact` | 프로그램 결과 파일·대형 데이터의 저장 위치·digest·만료 관리 |
| `kkdugi_batch_notification_rule`, 전달 outbox | 실패·지연 알림 및 전달 재시도 |
| Workflow 관련 테이블, `kkdugi_batch_workflow_step_approval` | Admin의 진행 판단과 owner 승인 대기·결정 이력. [Workflow 확장 설계](workflow-extension.md) 참조 |

Workflow를 위해 빈 테이블이나 의미가 정해지지 않은 FK를 미리 추가하지 않는다. 실행 단위와 호출 경계를 먼저 유지한다.
