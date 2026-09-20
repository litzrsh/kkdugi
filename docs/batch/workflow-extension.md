# Workflow 확장 설계

- 상태: 2026-09-20 확장 방향 초안. Workflow 엔진은 초기 구현 범위 밖이다.
- 확정 요구: Workflow 진행 여부는 admin이 결정한다. Job 완료 이벤트를 받은 뒤 자동 진행하거나 Workflow owner의 확인을 받아 진행한다. 아래 컬럼·상태·운영 기본값은 이를 위한 설계안이다.
- [전체 설계](README.md) / [실행 모델](execution.md)

## 1. Job을 유지한 채 상위 실행 단위를 추가한다

Job은 계속 **프로그램 하나 + 입력 + 실행 정책**을 뜻한다. Workflow는 Job들을 단계로 연결한 정의이고, WorkflowRun은 그 정의를 한 번 실행한 이력이다.

예시: `파일 수집 Job → 데이터 검증 Job → 매출 집계 Job`.

각 단계는 기존 Run 요청 기능을 사용한다. 따라서 runner는 자신이 받은 작업이 수동·스케줄·Workflow 중 어디서 시작했는지에 상관없이 프로그램 하나를 실행하면 된다. 선행 단계 성공 확인, 분기, 병렬 실행, 전체 상태 집계는 admin의 Workflow 서비스 책임이다.

**완료 이벤트를 받았다는 사실만으로 다음 Job을 실행하지 않는다.** Admin이 해당 Run의 완료를 확정하고, 선행 조건과 다음 단계의 진행 정책을 평가한다. Owner 승인이 필요한 경우 admin에 대기 상태를 저장하고, 승인 후 admin이 실행 요청을 생성한다. Owner는 진행 의사를 결정하고 admin은 권한·상태를 검증해 이를 반영한다.

같은 Job을 단독 실행할 수도 있고 여러 Workflow에서 재사용할 수도 있다. Workflow 때문에 Job 안에 `nextJobId`를 넣거나 runner에 다음 단계 실행 책임을 주지 않는다.

## 2. 지금 설계에 남겨둘 확장 지점

| 지금 둘 경계 | 이후 효과 |
| --- | --- |
| Job 정의와 Run/Attempt 분리 | 동일 Job을 여러 Workflow 실행에서 사용, 실패 시도 이력 보존 |
| 호출 원인과 scope/key가 있는 실행 요청 | Workflow 단계에서 중복 전달돼도 Run 하나만 생성 |
| 최종 입력 및 설정 snapshot | 실행 중 Job 변경이 이미 시작한 Workflow에 섞이지 않음 |
| 선택적인 구조화 결과 계약 | 이전 단계 결과를 다음 단계 입력에 명시적으로 매핑 |
| 실행 조회·완료·취소의 일관된 상태 모델 | Workflow가 기존 실행 관리 기능 재사용 |
| 단독 스케줄과 실행 엔진 분리 | 나중에 Workflow에도 별도 스케줄 연결 가능 |
| Admin의 완료 확정과 다음 단계 진행 판단 분리 | 자동 진행과 owner 승인 대기를 같은 실행 기반 위에 추가 |
| Workflow owner·진행 정책·승인 상태 영속화 | 재시작 후에도 승인 전 실행을 막고 결정 이력을 보존 |

지금 Workflow 테이블이나 의미 없는 nullable `workflow_id`를 Run에 미리 넣을 필요는 없다. 확장 시 단계 실행 연결 테이블이 기존 Run을 FK로 참조하면 된다. `parent_run_id`는 재실행 원본 전용이며 Workflow 부모 의미로 재활용하지 않는다.

## 3. 확장 시 추가할 테이블

| 테이블 | 핵심 데이터·관계 |
| --- | --- |
| `kkdugi_batch_workflow` | ID, 코드, 이름, owner 사용자 FK, 활성 여부, 공개 revision |
| `kkdugi_batch_workflow_revision` | (workflow ID, revision) PK, 변경 불가 공개 정의와 입력 규격 |
| `kkdugi_batch_workflow_step` | (workflow ID, revision, step key) PK, Job FK, 입력 매핑, 실패 정책, 진입 정책 AUTO / OWNER_APPROVAL |
| `kkdugi_batch_workflow_edge` | 같은 revision 내 선행·후행 step 복합 FK, 조건, UQ 연결 |
| `kkdugi_batch_workflow_schedule` | Workflow FK, cron, 시간대, 누락 정책, 다음 예정 시각 |
| `kkdugi_batch_workflow_run` | Workflow revision FK, 실행 시점 owner, 입력·그래프·참조 Job·진입 정책 snapshot, 상태, 시작/종료, 요청 dedupe key |
| `kkdugi_batch_workflow_step_run` | (workflow run ID, step key) PK, 상태, 실행 세대, 해석된 입력·결과, 해석 오류 사유. 승인 대기 동안 Job Run은 아직 없음 |
| `kkdugi_batch_workflow_step_execution` | (workflow run ID, step key, execution no) PK, Run FK/UQ, 단계 수동 재실행 이력 |
| `kkdugi_batch_workflow_step_approval` | 단계·실행 세대별 승인 요청, 지정 owner, 결정·사유·기한. 자동 진행은 승인 행 없이 Event에 판단 기록 |

### Workflow 컬럼의 공통 타입

위 표는 후속 개념 설계다. 상세 컬럼을 만들 때 [domains.xml 기반 규칙](domain-types.md)을 적용한다.

| 데이터 | 참조 도메인 | PostgreSQL 타입 |
| --- | --- | --- |
| Workflow / WorkflowRun / Schedule ID, Job / Run FK | ID | `varchar(20)` |
| Owner / 승인 사용자 FK | ID (기존 사용자 호환) | `varchar(60)` |
| Workflow 코드, step key, 상태, 실패 정책, 진입 정책 | Code | `varchar(20)` |
| 이름, cron, 시간대, 요청 중복 방지 scope/key | Text field | `varchar(200)` |
| 활성 여부 | Boolean | `char(1)`, Y/N |
| 공개 revision, 참조 revision | Integer (범위 확장) | `bigint` |
| 단계 execution no | Integer | `integer`, 양수 |
| 입력 규격·매핑·조건, 정의·그래프·Job snapshot, 입력·결과 | Clob (JSON 확장) | `jsonb` |
| 해석 오류·운영 사유 | Text field (Long) | `varchar(4000)` |
| 예정·시작·종료 시각 | Timestamp (실행 시각 확장) | `timestamptz(6)` |
| 공통 감사 컬럼 | 기존 호환 규칙 | [테이블 설계의 공통 감사 컬럼](tables.md#공통-감사-컬럼) 참조 |

StepRun과 Run 연결을 별도 테이블로 두면 단계 수동 재실행 때 원래 Run을 덮어쓰지 않는다. 일반 자동 재시도는 기존 Run의 Attempt를 사용하며, Workflow 서비스가 같은 실패에 대해 중복으로 자동 재시도하지 않도록 정책 책임을 정한다.

Job revision을 저장하는 정규화 테이블은 이후 필요에 따라 추가할 수 있다. WorkflowRun 생성 시 참조 Job의 실행 정의를 일관된 시점의 snapshot으로 저장하고, 단계 실행 시 그 snapshot을 기존 실행 서비스에 전달하는 내부 기능이 필요하다. 이미 비활성화·폐기된 runner/program 같은 현재의 안전상 실행 차단 조건은 snapshot보다 우선한다.

### Owner와 진행 정책 컬럼

| 테이블·컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `kkdugi_batch_workflow.owner_user_id` | ID (기존 사용자 호환) | `varchar(60)` | NOT NULL, FK → kkdugi_user_base(user_id). Workflow 요청자와 owner는 다를 수 있음 |
| `kkdugi_batch_workflow_run.owner_user_id` | ID (기존 사용자 호환) | `varchar(60)` | NOT NULL, 실행 생성 시 owner 복사, FK → kkdugi_user_base(user_id) |
| `kkdugi_batch_workflow_step.advance_policy` | Code | `varchar(20)` | NOT NULL, AUTO / OWNER_APPROVAL. 다음 단계에 진입할 때 평가 |
| `kkdugi_batch_workflow_step.approval_timeout_sec` | Integer | `integer` | 양수 또는 NULL. NULL이면 명시적인 승인·거절·취소까지 대기 |
| `kkdugi_batch_workflow_step_run.execution_no` | Integer | `integer` | 양수, 현재 단계 실행 세대. 재실행 시 증가, 승인 전에도 할당 |

진입 정책은 대상 단계에 둔다. `Job1 → Job2`의 확인 여부는 Job2 단계의 정책이다. 여러 선행 단계가 합류할 때도 모든 조건을 충족한 후 대상 단계에 대해 한 번 승인받는다. 첫 단계에도 같은 규칙을 적용할 수 있다. 정책과 timeout은 WorkflowRun snapshot에 고정하며, 실행 중 정의를 수정해 기존 승인 대기를 자동 진행으로 바꾸지 않는다.

### `kkdugi_batch_workflow_step_approval` — 진행 승인 요청·결정

아래 컬럼에 [공통 감사 컬럼](tables.md#공통-감사-컬럼)을 적용한다.

| 컬럼 | 참조 도메인 | PostgreSQL 타입 | 의미·제약 |
| --- | --- | --- | --- |
| `approval_id` | ID | `varchar(20)` | PK, SerialUtils로 생성 |
| `workflow_run_id` | ID | `varchar(20)` | NOT NULL, 대상 StepRun 복합 FK의 일부 |
| `step_key` | Code | `varchar(20)` | NOT NULL, (workflow_run_id, step_key) FK → kkdugi_batch_workflow_step_run |
| `execution_no` | Integer | `integer` | NOT NULL, 승인 대상 실행 세대, 양수 |
| `approval_no` | Integer | `integer` | NOT NULL, 같은 실행 세대의 승인 요청 차수, 양수 |
| `approver_user_id` | ID (기존 사용자 호환) | `varchar(60)` | NOT NULL, 지정 owner FK → kkdugi_user_base(user_id) |
| `approval_stat` | Code | `varchar(20)` | NOT NULL, PENDING / APPROVED / REJECTED / EXPIRED / CANCELED |
| `context_data` | Clob (JSON 확장) | `jsonb` | NOT NULL, 선행 Run ID·결과 요약·다음 Job 설정/입력 snapshot 식별 정보. 비밀 값 제외 |
| `requested_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | NOT NULL, 승인 요청 시각 |
| `expires_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 승인 만료 시각, 무기한이면 NULL |
| `decided_by` | ID (기존 사용자 호환) | `varchar(60)` | 결정 사용자 FK → kkdugi_user_base(user_id), 미결정/시스템 만료·취소는 NULL |
| `decided_dtm` | Timestamp (실행 시각 확장) | `timestamptz(6)` | 승인·거절·만료·취소 확정 시각, PENDING이면 NULL |
| `decision_reason` | Text field (Long) | `varchar(4000)` | 결정·만료·취소 사유, 거절 시 필수 |
| `decision_key` | Text field | `varchar(200)` | 결정 요청 재전송 식별자, 미결정/시스템 처리이면 NULL |

UQ `(workflow_run_id, step_key, execution_no, approval_no)`와 PENDING 행에 대한 partial UQ `(workflow_run_id, step_key, execution_no)`를 둔다. 실행 세대는 승인 요청 생성·결정 때 현재 StepRun과 잠금 아래 대조한다. 이전 실행에 대한 승인이 재실행을 승인할 수 없다. 대기 목록에는 `(approver_user_id, requested_dtm)` WHERE approval_stat = 'PENDING', 만료 처리에는 `(expires_dtm)` WHERE approval_stat = 'PENDING' AND expires_dtm IS NOT NULL 인덱스를 둔다.

`kkdugi_batch_event`에 자동 진행 판단, 승인 요청·결정, owner 이관, 오래된 이벤트 무시 등을 기록한다. Workflow 이벤트는 Job Run이 없을 수 있으므로 기존 `run_id`/`attempt_no`는 NULL로 두고 `target_type`/`target_id`로 WorkflowRun·StepRun·Approval을 식별한다. 승인 상태 변경과 관련 Event는 한 트랜잭션으로 저장한다.

## 4. 단계 실행 규칙

1. 초기 Workflow는 DAG(순환 없는 방향 그래프)로 제한한다. 공개 시 순환·없는 Job·입력 매핑 오류를 검증한다.
2. WorkflowRun을 생성하고 owner·참조 Job·진입 정책을 고정한다. StepRun은 선행 조건을 기다리는 WAITING 상태에서 시작한다.
3. Admin이 현재 실행 세대에 연결된 Run의 완료를 확정하고 선행 조건을 충족한 단계를 평가한다. AUTO면 READY, OWNER_APPROVAL이면 WAITING_APPROVAL로 바꾸고 승인 요청을 저장한다. Owner 승인 후에만 READY가 될 수 있다.
4. Admin이 READY 단계를 잠그고 기존 실행 서비스에 요청한다. scope/key는 `workflowRunId + stepKey + executionNo`에서 결정적으로 만든다. 요청 전에 Workflow 취소 여부, 현재 실행 세대, 승인 유효성, 현재 실행 차단 조건을 다시 검사한다.
5. StepRun ↔ Run 연결과 실행 요청은 가능한 한 같은 DB 트랜잭션에 둔다. 경계가 분리되면 같은 key 재요청으로 복구한다.
6. 병렬 분기의 합류는 ALL_SUCCESS를 기본으로 한다. 실패·취소·SKIPPED·UNKNOWN의 후속 처리도 명시적으로 정의한다.
7. UNKNOWN은 단계와 Workflow를 확인 대기로 유지한다. 결과 불명인 선행 단계의 다음 단계를 실행하지 않는다.

완료 이벤트는 진행 판단의 계기이며 저장된 Run 상태가 기준이다. 실패한 Attempt가 자동 재시도 중이면 완료한 단계가 아니다. 중복·순서가 바뀐 완료 이벤트와 이전 실행 세대의 지연 보고가 승인 요청이나 후속 Run을 추가 생성하지 않도록 현재 연결·세대와 UQ를 검증한다. Admin 재시작 후에는 저장된 Run·StepRun·Approval을 대조해 미반영 완료와 READY 단계를 처리한다. 메모리 이벤트 유실이나 알림 전송 실패로 진행 판단이 영구 누락되지 않아야 한다.

여러 Workflow에서 같은 Job을 동시에 요청하면 현재 Job의 중복 실행 정책을 함께 적용한다. Workflow 내부 병렬성만 보고 Job/runner/프로그램 용량 제한을 우회하지 않는다.

### Job1 → Job2 → Job3의 진행 예

Job2의 진입 정책은 OWNER_APPROVAL, Job3는 AUTO로 설정한 예다.

| 시점 | Admin의 처리 | 실행/대기 상태 |
| --- | --- | --- |
| Job1 완료 이벤트 수신 | 배정·결과를 검증하고 Job1의 Run 성공 확정 | Job1 종료, runner 슬롯 해제 |
| Job2 선행 조건 충족 | Job2 진입 정책을 평가하고 owner 승인 요청 저장 | Job2 StepRun은 WAITING_APPROVAL, Job2 Run은 아직 없음 |
| Owner가 진행 승인 | 인증된 owner·기한·실행 세대·취소 여부를 검사하고 APPROVED/READY 저장 | Admin이 Job2 Run 생성·배정 경로로 연결 |
| Job2 완료 이벤트 수신 | 성공 확정 후 Job3의 AUTO 정책 평가 | 별도 승인 없이 admin이 Job3 Run 요청 |
| Job3 완료 | 모든 필수 단계의 성공 확인 | WorkflowRun 성공 |

Job2가 AUTO로 설정되어 있었다면 Job1 성공 후 바로 Job2 Run을 요청한다. Runner는 어느 경우에도 다음 Job을 직접 실행하지 않는다.

### 승인 대기와 결정 규칙

- **승인 대기는 Workflow 상태다.** StepRun WAITING_APPROVAL 동안 해당 단계의 Job Run/Attempt를 미리 생성하지 않고, runner 슬롯·lease·Job timeout도 점유하지 않는다. 병렬로 실행 중인 다른 단계는 계속할 수 있으며 Workflow 전체가 승인만 기다릴 때 WAITING_APPROVAL로 표시한다. UNKNOWN과 사람 승인 대기는 구별한다.
- Owner는 WorkflowRun에 저장된 `owner_user_id`로 결정한다. 승인 요청자는 `approver_user_id`와 일치하는 활성 사용자이고 승인 권한이 있어야 한다. Runner 인증이나 단순 실행 요청자 자격으로 승인할 수 없다.
- 승인 요청에는 선행 결과 요약과 다음 Job·입력·설정 식별 정보를 제시한다. 승인 후 입력이나 실행 세대가 변경되면 과거 승인을 재사용하지 않는다.
- 기본 제안은 기한 없는 대기다. 기한을 설정한 경우 admin DB 시각으로 만료를 검사한다. **거절·만료는 자동 승인으로 바뀌지 않는다.** 해당 단계 진행을 막고 Workflow 중단 사유로 남긴다. 병렬 실행이 있으면 취소 요청과 종료 확인을 거쳐 전체 종결 상태를 확정한다.
- 승인·거절·만료·Workflow 취소는 같은 상태 잠금/조건부 갱신 경로에서 경합한다. 같은 approval ID·decision key·내용의 재요청은 기존 결정을 반환하고, 다른 결정은 충돌로 처리한다. 이미 취소·만료·이전 세대인 요청으로 다음 Job을 만들 수 없다.
- 승인 저장과 READY 변경은 한 트랜잭션으로 수행한다. 이후 Run 생성이 실패하거나 admin이 재시작되어도 READY를 재처리하고 실행 key로 중복을 막는다. 승인 완료는 다음 Job의 성공을 보장하는 상태가 아니다.
- Workflow 정의의 owner 변경은 기존 실행에 자동 반영하지 않는다. 진행 중 owner 이관은 별도 권한·사유를 요구한다. 대기 중 기존 요청을 CANCELED로 닫고 새 owner에게 다음 approval_no로 요청한다. 이전 owner의 지연 응답은 거절하며 이미 시작한 Job은 되돌리지 않는다.
- Owner 비활성화·권한 상실 때도 자동 진행하지 않는다. 기한 정책 또는 권한 있는 관리자의 명시적 owner 이관으로 해결한다. 대기 요청을 조회하는 admin 기능을 기본으로 두고 메일 등 외부 알림은 후속 전달 보장 설계로 분리한다.

## 5. 입력·결과와 실패 정책

예: 수집 단계 결과의 `artifactId`를 검증 단계 입력의 `sourceArtifact`에 연결한다. 결과 필드를 명시적으로 참조하며 shell 코드나 임의 스크립트로 보간하지 않는다. 큰 파일 자체를 Run JSON에 저장하지 않는다.

초기 Workflow 실패 정책은 STOP을 기본으로 제안한다. 진행 중인 다른 분기를 취소할지 끝까지 기다릴지, 실패를 허용할 단계인지, 보상 Job이 필요한지는 추가 정책이다. Workflow 취소는 대기 단계 차단과 실행 중 Run 취소 요청을 수행하며 모든 프로세스가 즉시 멈췄다고 간주하지 않는다.

한 단계 수동 재실행 시 이미 실행한 후속 단계 결과를 재사용할지 함께 다시 실행할지도 명시적으로 정해야 한다. 이 기능은 단순한 Job 재실행보다 영향 범위가 커서 최초 Workflow 버전에서는 전체 재실행만 제공하는 선택도 가능하다.

## 6. 확장의 한계와 별도 결정

현재 구조는 admin이 제어하는 순차·병렬·조건 분기와 Workflow owner 승인 대기를 확장 요구에 포함한다. Workflow 엔진을 구현할 때 위 승인 상태·권한·복구 규칙을 함께 구현한다. 동적 fan-out, 반복, 여러 결재자의 다단계 승인, 보상 트랜잭션은 별도 설계 대상이다. 이런 요구가 커지면 외부 Workflow 엔진 채택 여부를 검토하되 기존 Job/runner 실행 경계는 재사용할 수 있다.
