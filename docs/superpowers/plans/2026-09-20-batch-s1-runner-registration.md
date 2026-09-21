# 배치 관리자 S1 (Runner 등록 계층) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `kkdugi-admin`에 배치 Runner 등록 계층(runner/credential/event/api_request 테이블, 멱등 명령 기반, 관리자 Runner API, Runner 인증·등록·세션·heartbeat)을 라이브러리 분리 가능한 `kkdugi.app.batch` 단위로 구현한다.

**Architecture:** 배치 소유 코드는 `kkdugi.app.batch`(models/enums/mapper/service/exceptions/config) + `kkdugi.api.{admin.AdminBatchRunnerController,BatchAgentController}` + mapper XML + Flyway V13으로 한정하고 `core`에만 의존한다. Runner API(`/api/v1.0/batch-agent/**`)는 배치가 소유한 별도 `SecurityFilterChain`(`@Order(1)`)과 runner 전용 Bearer 토큰(`{credentialId}.{secret}`, SHA-256만 저장)으로 인증한다. 상태를 바꾸는 모든 명령은 runner 행 `SELECT … FOR UPDATE` 아래 한 트랜잭션에서 상태·event·(관리자 명령은) 멱등 응답 기록을 함께 저장한다.

**Tech Stack:** Java 17, Spring Boot 4.0.8, Spring Security(다중 `SecurityFilterChain`), MyBatis 4.1(XML mapper), Flyway, PostgreSQL 17, Jackson 3(`tools.jackson.databind.ObjectMapper`), Lombok, JUnit 5 + AssertJ + MockMvc.

**Spec:** [docs/superpowers/specs/2026-09-20-batch-s1-runner-registration-design.md](../specs/2026-09-20-batch-s1-runner-registration-design.md) (계약 원본: [admin-api.md](../../batch/admin-api.md), [runner-api.md](../../batch/runner-api.md), [tables.md](../../batch/tables.md), 검토: [admin-plan-review.md](../../batch/admin-plan-review.md))

## Global Constraints

- 모든 명령은 `C:\projects\kkdugi\kkdugi-admin`에서 실행한다. 테스트는 `./mvnw.cmd -B -ntp test [-Dtest=클래스명]`, 선행 조건은 저장소 루트의 `docker-compose up -d`(Postgres 127.0.0.1:5432).
- Java 17 / Spring Boot 4.0.8. JSON은 **Jackson 3** `tools.jackson.databind.ObjectMapper`만 import한다(어노테이션은 `com.fasterxml.jackson.annotation.*`).
- **record 금지.** DB 행 모델은 `kkdugi.core.models.BaseModel` 상속 + `@Getter @Setter` + 기본 생성자, 목록 검색 파라미터는 `BaseParams` 상속, 나머지(요청/응답/결과)는 일반 클래스. 요청 클래스에는 다인자 생성자를 두지 않는다(Jackson 생성자 감지 충돌 방지).
- `kkdugi.app.batch`는 `core`만 import한다. `core`, 다른 feature는 `app.batch`를 import하지 않는다. `core.security`는 수정하지 않는다.
- 배치 enum은 `kkdugi.app.batch.enums`에 두고 `CodeEnums`를 구현한다(`getCode()` == `name()`). 컬럼별 `typeHandler=`는 쓰지 않는다.
- 업무 시각은 `timestamptz(6)` ↔ `java.time.Instant`. **만료·유효성 판정과 업무 시각 기록(last_seen, consumed/revoked, 서버 시각 응답, 멱등 창)에는 DB `clock_timestamp()`** 를 쓴다. `now()`는 트랜잭션 시작 시각이라 runner 행 잠금에서 기다리는 동안 이미 만료된 토큰을 유효로 판단할 수 있다([검토 문서](../../batch/admin-implementation-plan-review.md) 4번). 감사 컬럼 `reg_dtm`/`upd_dtm`만 트랜잭션 시각 `(now() AT TIME ZONE 'UTC')`로 채운다.
- **배치 요청 본문은 UTF-8 기준 1 MiB(`kkdugi.batch.json-bytes`) 이하**여야 한다. 초과는 413 `batch.payload.too_large`이며 `BatchBodyLimitFilter`가 Content-Length와 무관하게 실제로 읽은 byte 수로 판정한다. 처리 순서는 인증(401/403) → 본문 한도(413) → 프로토콜 버전(409, Runner API)·메뉴 권한(403, 관리자 API) → 요청 검증(400)이다.
- **배치 요청 DTO의 문자열·정수 필드는 JSON 토큰 타입을 엄격히 검사**한다(`@JsonDeserialize(using = BatchStrictString.class / BatchStrictInteger.class)`). Jackson 기본 변환(`1.9`→1, `"1"`→1, `1`→`"1"`)을 배치 요청에서 허용하지 않는다. 다른 admin API에 영향이 가지 않도록 전역 Jackson 설정은 바꾸지 않는다.
- 작업 시작 전 **베이스라인**(`./mvnw.cmd -B -ntp test`)을 기록한다. 이후 실패는 베이스라인에 없던 것만 이 작업의 회귀로 본다.
- ID는 `BatchIds.next(...)`(= `SerialUtils.next`). 접두사 `BR`(runner) `BC`(credential) `BE`(event) `BQ`(api_request), 형식 `접두사 + yyyyMMddHHmm + %04d`(18자).
- Mapper XML은 `src/main/resources/mapper/postgres/app/batch/`(mapper 패키지를 미러링). 쿼리 첫 줄에 `/* QueryID=<mapper FQCN>.<id> */` 주석, 기존 `AdminAuthorityMapper.xml` 스타일(CDATA)을 따른다.
- 잠금 순서: 멱등 advisory lock → runner 행 `FOR UPDATE` → credential 행. 상태를 바꾸는 runner 작업은 잠금 아래에서 credential 유효성을 다시 확인한다.
- Runner API 규약: 오류는 `{code, message}`, `X-Protocol-Version: 1` 필수(아니면 409 `batch.protocol.unsupported`), bigint(session/version)는 십진 문자열, 시각은 UTC `Z` 소수 6자리 이하, 모든 agent 응답에 `Cache-Control: no-store`.
- 관리자 API 권한: `@RequireAuthority(program = "admin/batch/runner")` — 조회 READ, 생성·수정·폐기·토큰 발급 WRTE, 삭제 DELT. 토큰 발급·폐기는 추가로 `@HasRole(Constants.SYS_ADMIN)`. 등록 토큰 발급 외 관리자 쓰기 명령은 `Idempotency-Key` + `X-Request-Created-At` 필수.
- 작업 트리에 이 작업과 무관한 미커밋 변경(`docs/batch/*`, `kkdugi-runner/*` 등)이 많다. **커밋은 각 태스크가 명시한 경로만 `git add`** 한다. 커밋은 사용자가 허용한 경우에만 수행하고, 허용되지 않았다면 커밋 단계는 건너뛰고 변경을 그대로 둔다. 커밋 메시지 끝에는 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`을 붙인다.

## File Structure

```
kkdugi-admin/src/main/java/kkdugi/app/batch/
├─ enums/       RunnerStatus, CredentialType, ActorType, EventTargetType, EventType, IdempotencySubjectType
├─ exceptions/  BatchException, BatchErrors
├─ config/      BatchProperties, BatchAgentSecurityConfig, BatchAgentAuthenticationFilter,
│               BatchAgentAuthentication, BatchErrorWriter, BatchAgentInterceptor, BatchAgentWebConfig,
│               BatchBodyLimitFilter, BatchBodyLimitConfig
├─ models/      BatchRunner, BatchRunnerParams, BatchRunnerCredential, BatchEvent, BatchApiRequest, BatchHeartbeatState,
│               BatchAgentPrincipal, BatchStrictRequest, BatchStrictString, BatchStrictInteger, BatchRunnerRequest, BatchRevokeRequest,
│               BatchRegistrationRequest/Response, BatchSessionRequest/Response, BatchLimits,
│               BatchHeartbeatRequest/Item/Response/Action, BatchEnrollmentResult,
│               BatchIdempotencyKey, BatchIdempotentResponse
├─ mapper/      BatchRunnerMapper, BatchCredentialMapper, BatchEventMapper, BatchApiRequestMapper
└─ service/     BatchIds, BatchTime, BatchTokenService, BatchEventService, BatchRunnerService,
                BatchEnrollmentService, BatchAgentService, BatchIdempotencyService, BatchRequestKeys
kkdugi-admin/src/main/java/kkdugi/api/   BatchApiSupport, AdminBatchRunnerController, BatchAgentController
kkdugi-admin/src/main/resources/
├─ db/migration/V13__create_batch_runner.sql
├─ mapper/postgres/app/batch/{BatchRunnerMapper,BatchCredentialMapper,BatchEventMapper,BatchApiRequestMapper}.xml
└─ messages/messages{,_en_US,_ko_KR,_jp_JA}.properties      (batch.* 키 추가)
kkdugi-admin/src/test/java/kkdugi/...  (태스크별 테스트) + support/BatchTestData.java
docs/adr/0019-batch-library-boundary.md, docs/api/batch-runner.md, scripts/dev/batch-menu-seed.sql
```

---

### Task 1: V13 스키마, enum, 설정, ID·시각 헬퍼

**Files:**
- Create: `kkdugi-admin/src/main/resources/db/migration/V13__create_batch_runner.sql`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/enums/{RunnerStatus,CredentialType,ActorType,EventTargetType,EventType,IdempotencySubjectType}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchProperties.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/service/{BatchIds,BatchTime}.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/batch/BatchSchemaTest.java`, `kkdugi-admin/src/test/java/kkdugi/app/batch/enums/BatchEnumsTest.java`, `kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchTimeTest.java`

**Interfaces:**
- Produces: 테이블 `kkdugi_batch_runner`, `kkdugi_batch_runner_credential`, `kkdugi_batch_event`, `kkdugi_batch_api_request`; enum 6종(모두 `getCode()==name()`, `static fromCode(String)`); `BatchProperties`(getter: `getEnrollmentTtl():Duration`, `getAccessTokenTtl():Duration`, `getHeartbeatSeconds():int`, `getPollSeconds():int`, `getLeaseSeconds():int`, `getJsonBytes()/getInputBytes()/getResultBytes()/getLogChunkBytes():int`, `getIdempotencyRetention():Duration`, `getRequestSkew():Duration`, `getLastUsedTouchSeconds():int`, `getOnlineSeconds():int`); `BatchIds.{RUNNER,CREDENTIAL,EVENT,API_REQUEST}: SerialConfig`, `BatchIds.next(SerialConfig):String`; `BatchTime.format(Instant):String`.

- [ ] **Step 0: 베이스라인 기록**

Run(코드를 바꾸기 전에): `./mvnw.cmd -B -ntp test` 그리고 `node --test src/test/js/*.test.mjs`
결과에서 실패한 테스트 클래스·메서드 이름을 메모한다(저장소 밖 임시 메모). 기존 실패(예: 이전 runner 검증에서 보고된 일본어 메뉴 시드 관련 실패)는 이 작업과 무관하므로 고치지 않는다. 이후 전체 실행에서는 **베이스라인에 없던 실패만** 회귀로 다룬다.

- [ ] **Step 1: 실패하는 스키마 테스트 작성**

`kkdugi-admin/src/test/java/kkdugi/app/batch/BatchSchemaTest.java`:

```java
package kkdugi.app.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsTheFourS1Tables() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name IN "
                + "('kkdugi_batch_runner','kkdugi_batch_runner_credential','kkdugi_batch_event','kkdugi_batch_api_request')",
                Integer.class);
        assertThat(count).isEqualTo(4);
    }

    @Test
    void runnerStatusIsConstrained() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO kkdugi_batch_runner "
                + "(runner_id, runner_cd, runner_nm, runner_stat, capacity_cnt, reg_id) "
                + "VALUES ('TSCHEMA0000000001', 'tb-schema-1', 'x', 'BOGUS', 1, 'T')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runnerCapacityMustBeBetween1And200() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO kkdugi_batch_runner "
                + "(runner_id, runner_cd, runner_nm, runner_stat, capacity_cnt, reg_id) "
                + "VALUES ('TSCHEMA0000000002', 'tb-schema-2', 'x', 'ACTIVE', 201, 'T')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void apiRequestOperationHashMustBeSha256Hex() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO kkdugi_batch_api_request "
                + "(request_id, subject_type, subject_id, operation_hash, request_key, request_hash, request_dtm, "
                + "http_status, expires_dtm, reg_id) "
                + "VALUES ('TSCHEMA0000000003', 'USER', 'U', 'short', 'k', 'h', now(), 200, now(), 'T')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void apiRequestHttpStatusMustBeSuccess() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO kkdugi_batch_api_request "
                + "(request_id, subject_type, subject_id, operation_hash, request_key, request_hash, request_dtm, "
                + "http_status, expires_dtm, reg_id) "
                + "VALUES ('TSCHEMA0000000004', 'USER', 'U', repeat('a', 64), 'k', 'h', now(), 500, now(), 'T')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchSchemaTest`
Expected: FAIL — `createsTheFourS1Tables`가 `expected: 4 but was: 0`, 나머지는 테이블 없음으로 실패.

- [ ] **Step 3: migration 작성**

`kkdugi-admin/src/main/resources/db/migration/V13__create_batch_runner.sql`:

```sql
-- 배치 S1: Runner 등록 계층. 설계: docs/superpowers/specs/2026-09-20-batch-s1-runner-registration-design.md
-- 감사 컬럼(reg_dtm/upd_dtm)은 UTC 기준 timestamp(6), 업무 시각은 timestamptz(6).
-- kkdugi_batch_event의 run_id/attempt_no는 S3에서 Run/Attempt 테이블과 함께 FK를 추가한다.

CREATE TABLE kkdugi_batch_runner (
    runner_id     VARCHAR(20)    NOT NULL,
    runner_cd     VARCHAR(20)    NOT NULL,
    runner_nm     VARCHAR(200)   NOT NULL,
    runner_stat   VARCHAR(20)    NOT NULL,
    host_nm       VARCHAR(200),
    os_cd         VARCHAR(20),
    agent_ver     VARCHAR(50),
    capacity_cnt  INTEGER        NOT NULL,
    session_ver   BIGINT         NOT NULL DEFAULT 0,
    boot_ref      VARCHAR(50),
    last_seen_dtm TIMESTAMPTZ(6),
    config_ver    BIGINT         NOT NULL DEFAULT 1,
    reg_dtm       TIMESTAMP(6)   NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    reg_id        VARCHAR(60)    NOT NULL,
    upd_dtm       TIMESTAMP(6),
    upd_id        VARCHAR(60),
    CONSTRAINT kkdugi_batch_runner_pk PRIMARY KEY (runner_id),
    CONSTRAINT kkdugi_batch_runner_cd_uq UNIQUE (runner_cd),
    -- kkdugi.app.batch.enums.RunnerStatus와 맞춘다.
    CONSTRAINT kkdugi_batch_runner_stat_chk CHECK (runner_stat IN ('REGISTERING', 'ACTIVE', 'PAUSED', 'REVOKED')),
    CONSTRAINT kkdugi_batch_runner_os_chk CHECK (os_cd IS NULL OR os_cd IN ('LINUX', 'WINDOWS')),
    CONSTRAINT kkdugi_batch_runner_capacity_chk CHECK (capacity_cnt BETWEEN 1 AND 200),
    CONSTRAINT kkdugi_batch_runner_session_chk CHECK (session_ver >= 0),
    CONSTRAINT kkdugi_batch_runner_ver_chk CHECK (config_ver >= 1)
);

COMMENT ON TABLE kkdugi_batch_runner IS '배치 Runner';
COMMENT ON COLUMN kkdugi_batch_runner.runner_id IS 'Runner ID';
COMMENT ON COLUMN kkdugi_batch_runner.runner_cd IS 'Runner 운영 코드';
COMMENT ON COLUMN kkdugi_batch_runner.runner_nm IS 'Runner 표시 이름';
COMMENT ON COLUMN kkdugi_batch_runner.runner_stat IS 'Runner 상태 (REGISTERING/ACTIVE/PAUSED/REVOKED)';
COMMENT ON COLUMN kkdugi_batch_runner.host_nm IS '머신 이름 (등록 전 NULL)';
COMMENT ON COLUMN kkdugi_batch_runner.os_cd IS 'OS 코드 (LINUX/WINDOWS, 등록 전 NULL)';
COMMENT ON COLUMN kkdugi_batch_runner.agent_ver IS 'Runner 버전 (등록 전 NULL)';
COMMENT ON COLUMN kkdugi_batch_runner.capacity_cnt IS '동시 배정 최대 수';
COMMENT ON COLUMN kkdugi_batch_runner.session_ver IS '세션 세대 (개설할 때마다 증가, 감소하지 않음)';
COMMENT ON COLUMN kkdugi_batch_runner.boot_ref IS '현재 runner 기동 UUID (세션 개설 재전송 판별)';
COMMENT ON COLUMN kkdugi_batch_runner.last_seen_dtm IS '마지막 인증된 heartbeat';
COMMENT ON COLUMN kkdugi_batch_runner.config_ver IS '관리 설정 낙관적 잠금 버전 (heartbeat/세션 개설로는 증가하지 않음)';
COMMENT ON COLUMN kkdugi_batch_runner.reg_dtm IS '등록일시 (UTC)';
COMMENT ON COLUMN kkdugi_batch_runner.reg_id IS '등록 actor ID';
COMMENT ON COLUMN kkdugi_batch_runner.upd_dtm IS '수정일시 (UTC)';
COMMENT ON COLUMN kkdugi_batch_runner.upd_id IS '수정 actor ID';

CREATE TABLE kkdugi_batch_runner_credential (
    credential_id   VARCHAR(20)    NOT NULL,
    runner_id       VARCHAR(20)    NOT NULL,
    credential_type VARCHAR(20)    NOT NULL,
    secret_hash     VARCHAR(2048)  NOT NULL,
    expires_dtm     TIMESTAMPTZ(6) NOT NULL,
    consumed_dtm    TIMESTAMPTZ(6),
    revoked_dtm     TIMESTAMPTZ(6),
    last_used_dtm   TIMESTAMPTZ(6),
    reg_dtm         TIMESTAMP(6)   NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    reg_id          VARCHAR(60)    NOT NULL,
    upd_dtm         TIMESTAMP(6),
    upd_id          VARCHAR(60),
    CONSTRAINT kkdugi_batch_runner_credential_pk PRIMARY KEY (credential_id),
    CONSTRAINT kkdugi_batch_runner_credential_fk FOREIGN KEY (runner_id) REFERENCES kkdugi_batch_runner (runner_id),
    CONSTRAINT kkdugi_batch_runner_credential_type_chk CHECK (credential_type IN ('ENROLLMENT', 'ACCESS'))
);

CREATE INDEX kkdugi_batch_runner_credential_ix1 ON kkdugi_batch_runner_credential (runner_id, credential_type);

COMMENT ON TABLE kkdugi_batch_runner_credential IS '배치 Runner 등록/통신 자격증명';
COMMENT ON COLUMN kkdugi_batch_runner_credential.credential_id IS '자격증명 ID (토큰의 조회용 식별 부분)';
COMMENT ON COLUMN kkdugi_batch_runner_credential.runner_id IS 'Runner ID';
COMMENT ON COLUMN kkdugi_batch_runner_credential.credential_type IS '종류 (ENROLLMENT/ACCESS)';
COMMENT ON COLUMN kkdugi_batch_runner_credential.secret_hash IS '토큰 secret의 SHA-256 hex (평문 미저장)';
COMMENT ON COLUMN kkdugi_batch_runner_credential.expires_dtm IS '만료 시각';
COMMENT ON COLUMN kkdugi_batch_runner_credential.consumed_dtm IS '등록 토큰 사용 시각';
COMMENT ON COLUMN kkdugi_batch_runner_credential.revoked_dtm IS '폐기 시각';
COMMENT ON COLUMN kkdugi_batch_runner_credential.last_used_dtm IS '마지막 사용 시각';
COMMENT ON COLUMN kkdugi_batch_runner_credential.reg_dtm IS '등록일시 (UTC)';
COMMENT ON COLUMN kkdugi_batch_runner_credential.reg_id IS '등록 actor ID';
COMMENT ON COLUMN kkdugi_batch_runner_credential.upd_dtm IS '수정일시 (UTC)';
COMMENT ON COLUMN kkdugi_batch_runner_credential.upd_id IS '수정 actor ID';

CREATE TABLE kkdugi_batch_event (
    event_id     VARCHAR(20)    NOT NULL,
    target_type  VARCHAR(20)    NOT NULL,
    target_id    VARCHAR(200)   NOT NULL,
    run_id       VARCHAR(20),
    attempt_no   INTEGER,
    event_type   VARCHAR(20)    NOT NULL,
    from_stat    VARCHAR(20),
    to_stat      VARCHAR(20),
    actor_type   VARCHAR(20)    NOT NULL,
    actor_id     VARCHAR(60)    NOT NULL,
    occurred_dtm TIMESTAMPTZ(6) NOT NULL,
    detail_data  JSONB,
    reg_dtm      TIMESTAMP(6)   NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    reg_id       VARCHAR(60)    NOT NULL,
    upd_dtm      TIMESTAMP(6),
    upd_id       VARCHAR(60),
    CONSTRAINT kkdugi_batch_event_pk PRIMARY KEY (event_id),
    CONSTRAINT kkdugi_batch_event_actor_chk CHECK (actor_type IN ('USER', 'RUNNER', 'SYSTEM'))
);

CREATE INDEX kkdugi_batch_event_ix1 ON kkdugi_batch_event (target_type, target_id, occurred_dtm);
CREATE INDEX kkdugi_batch_event_ix2 ON kkdugi_batch_event (occurred_dtm);

COMMENT ON TABLE kkdugi_batch_event IS '배치 상태 전이·운영 감사 이력 (수정 불가)';
COMMENT ON COLUMN kkdugi_batch_event.event_id IS 'Event ID';
COMMENT ON COLUMN kkdugi_batch_event.target_type IS '대상 종류 (다형 참조)';
COMMENT ON COLUMN kkdugi_batch_event.target_id IS '대상 ID (다형 참조라 FK 없음)';
COMMENT ON COLUMN kkdugi_batch_event.run_id IS 'Run ID (S3에서 FK 추가)';
COMMENT ON COLUMN kkdugi_batch_event.attempt_no IS 'Attempt 번호 (S3에서 FK 추가)';
COMMENT ON COLUMN kkdugi_batch_event.event_type IS '변경 종류';
COMMENT ON COLUMN kkdugi_batch_event.from_stat IS '이전 상태 (상태 없는 변경은 NULL)';
COMMENT ON COLUMN kkdugi_batch_event.to_stat IS '이후 상태 (상태 없는 변경은 NULL)';
COMMENT ON COLUMN kkdugi_batch_event.actor_type IS 'Actor 종류 (USER/RUNNER/SYSTEM)';
COMMENT ON COLUMN kkdugi_batch_event.actor_id IS 'Actor ID';
COMMENT ON COLUMN kkdugi_batch_event.occurred_dtm IS '발생 시각';
COMMENT ON COLUMN kkdugi_batch_event.detail_data IS '변경 요약·사유 (토큰·비밀 값 제외)';
COMMENT ON COLUMN kkdugi_batch_event.reg_dtm IS '등록일시 (UTC)';
COMMENT ON COLUMN kkdugi_batch_event.reg_id IS '등록 actor ID';
COMMENT ON COLUMN kkdugi_batch_event.upd_dtm IS '수정일시 (UTC)';
COMMENT ON COLUMN kkdugi_batch_event.upd_id IS '수정 actor ID';

CREATE TABLE kkdugi_batch_api_request (
    request_id     VARCHAR(20)    NOT NULL,
    subject_type   VARCHAR(20)    NOT NULL,
    subject_id     VARCHAR(60)    NOT NULL,
    operation_hash VARCHAR(200)   NOT NULL,
    request_key    VARCHAR(200)   NOT NULL,
    request_hash   VARCHAR(200)   NOT NULL,
    request_dtm    TIMESTAMPTZ(6) NOT NULL,
    http_status    INTEGER        NOT NULL,
    response_data  JSONB,
    location_text  VARCHAR(2000),
    expires_dtm    TIMESTAMPTZ(6) NOT NULL,
    reg_dtm        TIMESTAMP(6)   NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    reg_id         VARCHAR(60)    NOT NULL,
    upd_dtm        TIMESTAMP(6),
    upd_id         VARCHAR(60),
    CONSTRAINT kkdugi_batch_api_request_pk PRIMARY KEY (request_id),
    CONSTRAINT kkdugi_batch_api_request_uq UNIQUE (subject_type, subject_id, operation_hash, request_key),
    CONSTRAINT kkdugi_batch_api_request_subject_chk CHECK (subject_type IN ('USER', 'RUNNER')),
    CONSTRAINT kkdugi_batch_api_request_op_chk CHECK (operation_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT kkdugi_batch_api_request_status_chk CHECK (http_status BETWEEN 200 AND 299)
);

CREATE INDEX kkdugi_batch_api_request_ix1 ON kkdugi_batch_api_request (expires_dtm);

COMMENT ON TABLE kkdugi_batch_api_request IS '배치 API 명령의 멱등 응답 기록';
COMMENT ON COLUMN kkdugi_batch_api_request.request_id IS '기록 ID';
COMMENT ON COLUMN kkdugi_batch_api_request.subject_type IS '인증 주체 종류 (USER/RUNNER)';
COMMENT ON COLUMN kkdugi_batch_api_request.subject_id IS '인증 주체 ID';
COMMENT ON COLUMN kkdugi_batch_api_request.operation_hash IS 'HTTP method + 정규화 path의 SHA-256 hex';
COMMENT ON COLUMN kkdugi_batch_api_request.request_key IS 'Idempotency-Key';
COMMENT ON COLUMN kkdugi_batch_api_request.request_hash IS '요청 의미상 동일성 hash';
COMMENT ON COLUMN kkdugi_batch_api_request.request_dtm IS '최초 요청의 key 생성 시각';
COMMENT ON COLUMN kkdugi_batch_api_request.http_status IS '재현할 HTTP 상태 (2xx)';
COMMENT ON COLUMN kkdugi_batch_api_request.response_data IS '응답 본문 (본문 없는 응답은 NULL)';
COMMENT ON COLUMN kkdugi_batch_api_request.location_text IS '응답 Location';
COMMENT ON COLUMN kkdugi_batch_api_request.expires_dtm IS '응답 재현 만료 시각 (기본 72시간)';
COMMENT ON COLUMN kkdugi_batch_api_request.reg_dtm IS '등록일시 (UTC)';
COMMENT ON COLUMN kkdugi_batch_api_request.reg_id IS '등록 actor ID';
COMMENT ON COLUMN kkdugi_batch_api_request.upd_dtm IS '수정일시 (UTC)';
COMMENT ON COLUMN kkdugi_batch_api_request.upd_id IS '수정 actor ID';
```

- [ ] **Step 4: 스키마 테스트 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchSchemaTest`
Expected: PASS (5 tests). Flyway가 시작 시 V13을 적용한다.

- [ ] **Step 5: enum 테스트 작성 후 enum 구현**

`kkdugi-admin/src/test/java/kkdugi/app/batch/enums/BatchEnumsTest.java`:

```java
package kkdugi.app.batch.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import kkdugi.core.enums.CodeEnums;

class BatchEnumsTest {

    @Test
    void codeEqualsNameForEveryBatchEnum() {
        for (Class<? extends CodeEnums> type : java.util.List.of(RunnerStatus.class, CredentialType.class,
                ActorType.class, EventTargetType.class, EventType.class, IdempotencySubjectType.class)) {
            for (CodeEnums value : type.getEnumConstants()) {
                assertThat(value.getCode()).isEqualTo(((Enum<?>) value).name());
            }
        }
    }

    @Test
    void runnerStatusFromCode() {
        assertThat(RunnerStatus.fromCode("ACTIVE")).isEqualTo(RunnerStatus.ACTIVE);
        assertThatThrownBy(() -> RunnerStatus.fromCode("BOGUS")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enumeratesContractValues() {
        assertThat(RunnerStatus.values()).extracting(Enum::name)
                .containsExactly("REGISTERING", "ACTIVE", "PAUSED", "REVOKED");
        assertThat(CredentialType.values()).extracting(Enum::name).containsExactly("ENROLLMENT", "ACCESS");
    }
}
```

각 enum은 아래 형태로 만든다(`kkdugi-admin/src/main/java/kkdugi/app/batch/enums/`). `RunnerStatus`:

```java
package kkdugi.app.batch.enums;

import kkdugi.core.enums.CodeEnums;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Runner 상태. 저장 코드는 상수 이름과 같다(kkdugi_batch_runner_stat_chk와 맞춘다). */
@RequiredArgsConstructor
@Getter
public enum RunnerStatus implements CodeEnums {

    REGISTERING("REGISTERING", "batch.runner.status.registering"),
    ACTIVE("ACTIVE", "batch.runner.status.active"),
    PAUSED("PAUSED", "batch.runner.status.paused"),
    REVOKED("REVOKED", "batch.runner.status.revoked");

    private final String code;
    private final String labelCode;

    public static RunnerStatus fromCode(String code) {
        return CodeEnums.fromCode(RunnerStatus.class, code);
    }
}
```

나머지 다섯 개도 같은 골격(`code == name`, `labelCode`는 `batch.<종류>.<소문자>`, `fromCode` 제공)이다. 상수 목록:
- `CredentialType`: `ENROLLMENT`, `ACCESS`
- `ActorType`: `USER`, `RUNNER`, `SYSTEM`
- `EventTargetType`: `RUNNER` (S2에서 PROGRAM/INSTALLATION 추가)
- `EventType`: `CREATED`, `UPDATED`, `DELETED`, `ENROLLMENT_ISSUED`, `REGISTERED`, `SESSION_OPENED`, `REVOKED`
- `IdempotencySubjectType`: `USER`, `RUNNER`

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchEnumsTest`
Expected: PASS (3 tests).

- [ ] **Step 6: 설정·ID·시각 헬퍼 테스트 작성**

`kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchTimeTest.java`:

```java
package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import kkdugi.app.batch.config.BatchProperties;

class BatchTimeTest {

    private static final Pattern RUNNER_TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");

    @Test
    void formatsUtcWithAtMostSixFractionalDigits() {
        assertThat(BatchTime.format(Instant.parse("2026-09-20T02:00:00Z"))).isEqualTo("2026-09-20T02:00:00Z");
        assertThat(BatchTime.format(Instant.parse("2026-09-20T02:00:00.123456789Z")))
                .isEqualTo("2026-09-20T02:00:00.123456Z");
        assertThat(BatchTime.format(Instant.now())).matches(RUNNER_TIMESTAMP);
    }

    @Test
    void propertiesDefaultsMatchTheContract() {
        BatchProperties props = new BatchProperties();
        assertThat(props.getHeartbeatSeconds()).isEqualTo(10);
        assertThat(props.getPollSeconds()).isEqualTo(3);
        assertThat(props.getLeaseSeconds()).isEqualTo(60);
        assertThat(props.getOnlineSeconds()).isEqualTo(30);
        assertThat(props.getEnrollmentTtl().toMinutes()).isEqualTo(10);
        assertThat(props.getAccessTokenTtl().toDays()).isEqualTo(30);
        assertThat(props.getIdempotencyRetention().toHours()).isEqualTo(72);
        assertThat(props.getRequestSkew().toMinutes()).isEqualTo(5);
    }
}
```

- [ ] **Step 7: 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchProperties.java`:

```java
package kkdugi.app.batch.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/** 배치 운영 기본값(application.yml의 kkdugi.batch.*로 조정). 기본값은 runner-api.md의 운영 기본값이다. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kkdugi.batch")
public class BatchProperties {

    private Duration enrollmentTtl = Duration.ofMinutes(10);
    private Duration accessTokenTtl = Duration.ofDays(30);
    private int heartbeatSeconds = 10;
    private int pollSeconds = 3;
    private int leaseSeconds = 60;
    private int jsonBytes = 1_048_576;
    private int inputBytes = 262_144;
    private int resultBytes = 262_144;
    private int logChunkBytes = 32_768;
    private Duration idempotencyRetention = Duration.ofHours(72);
    private Duration requestSkew = Duration.ofMinutes(5);
    /** credential.last_used_dtm을 이 간격(초)보다 자주 갱신하지 않는다. */
    private int lastUsedTouchSeconds = 60;

    /** last_seen이 이 시간(초) 이내면 online. heartbeat 주기의 3배. */
    public int getOnlineSeconds() {
        return heartbeatSeconds * 3;
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchIds.java`:

```java
package kkdugi.app.batch.service;

import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;

/** 배치 ID 채번 설정. 형식은 접두사 2자 + yyyyMMddHHmm + %04d (18자, 20자 도메인 안). */
public final class BatchIds {

    public static final SerialConfig RUNNER = config("KKDUGI_BATCH_RUNNER", "BR");
    public static final SerialConfig CREDENTIAL = config("KKDUGI_BATCH_CREDENTIAL", "BC");
    public static final SerialConfig EVENT = config("KKDUGI_BATCH_EVENT", "BE");
    public static final SerialConfig API_REQUEST = config("KKDUGI_BATCH_API_REQUEST", "BQ");

    private BatchIds() {
    }

    public static String next(SerialConfig config) {
        String id = SerialUtils.next(config);
        if (id == null) {
            throw new IllegalStateException("SerialUtils is not initialized");
        }
        return id;
    }

    private static SerialConfig config(String id, String prefix) {
        return new SerialConfig() {

            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getValueFormatter() {
                return prefix + "%s%04d";
            }
        };
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchTime.java`:

```java
package kkdugi.app.batch.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Runner API/관리자 API의 시각 표기: UTC, 소수 초 최대 6자리. */
public final class BatchTime {

    private BatchTime() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS).toString();
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest="BatchTimeTest,BatchEnumsTest,BatchSchemaTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add kkdugi-admin/src/main/resources/db/migration/V13__create_batch_runner.sql kkdugi-admin/src/main/java/kkdugi/app/batch kkdugi-admin/src/test/java/kkdugi/app/batch
git commit -m "feat(batch): add S1 schema, enums, properties and id helpers" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: 예외, 메시지, 모델, Runner mapper

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/exceptions/{BatchException,BatchErrors}.java`
- Modify: `kkdugi-admin/src/main/resources/messages/{messages,messages_en_US,messages_ko_KR,messages_jp_JA}.properties` (끝에 `batch.*` 추가)
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/models/{BatchRunner,BatchRunnerParams,BatchHeartbeatState}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/mapper/BatchRunnerMapper.java`
- Create: `kkdugi-admin/src/main/resources/mapper/postgres/app/batch/BatchRunnerMapper.xml`
- Create: `kkdugi-admin/src/test/java/kkdugi/support/BatchTestData.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/batch/BatchMessagesTest.java`, `kkdugi-admin/src/test/java/kkdugi/app/batch/mapper/BatchRunnerMapperTest.java`

**Interfaces:**
- Consumes: Task 1의 `RunnerStatus`, `BatchIds`, `BatchProperties`.
- Produces:
  - `BatchException(int status, String code)` — `getStatus():int`, `getCode():String`; 정적 팩토리 `badRequest/unauthorized/forbidden/notFound/conflict/gone/preconditionFailed/tooLarge/preconditionRequired(String code)` (각각 400/401/403/404/409/410/412/413/428).
  - `BatchErrors` 상수: `REQUEST_INVALID="batch.request.invalid"`, `CREDENTIAL_INVALID="batch.credential.invalid"`, `RUNNER_FORBIDDEN="batch.runner.forbidden"`, `RUNNER_NOT_FOUND="batch.runner.not_found"`, `RUNNER_DUPLICATE_CODE="batch.runner.duplicate_code"`, `STATE_CONFLICT="batch.state.conflict"`, `SESSION_STALE="batch.session.stale"`, `PROTOCOL_UNSUPPORTED="batch.protocol.unsupported"`, `PLATFORM_UNSUPPORTED="batch.platform.unsupported"`, `IDEMPOTENCY_CONFLICT="batch.idempotency.conflict"`, `VERSION_CONFLICT="batch.version.conflict"`, `VERSION_REQUIRED="batch.version.required"`, `REQUEST_EXPIRED="batch.request.expired"`, `PAYLOAD_TOO_LARGE="batch.payload.too_large"`.
  - `BatchRunner extends BaseModel`: `id, code, name, status(RunnerStatus), hostname, os, agentVersion, capacity(int), sessionVer(long), bootRef, lastSeenAt(Instant), online(boolean), configVer(long)`; JSON 노출은 `getSession()`/`getVersion()`(십진 문자열). `BatchRunnerParams extends BaseParams`: `code, name, status`. `BatchHeartbeatState`: `status(RunnerStatus), capacity(int)`.
  - `BatchRunnerMapper` (모든 `@Param` 이름은 아래 그대로):
    - `List<BatchRunner> search(code, name, status, offset, limit, onlineSeconds)`
    - `Optional<BatchRunner> findById(id, onlineSeconds)`, `Optional<BatchRunner> lockById(id)`
    - `int countByCode(code)`, `int insert(BatchRunner row)`
    - `int update(id, name, capacity, status(RunnerStatus), expectedVersion(long), updaterId)`
    - `int changeStatus(id, status(RunnerStatus), updaterId)`
    - `int markRegistered(id, hostname, os, agentVersion, updaterId)`
    - `int openSession(id, bootId, agentVersion)`
    - `Optional<BatchHeartbeatState> touch(id, session(long))`
    - `int deleteById(id)`, `Instant now()`
  - `BatchTestData`(test): `CODE_PREFIX="tb-"`, `USER="U_TEST_BATCH"`, `wipe(JdbcTemplate)`, `insertRunner(JdbcTemplate, code, status):String`, `assertError(ThrowingCallable, int status, String code)`.

- [ ] **Step 1: 예외 클래스 작성**

`kkdugi-admin/src/main/java/kkdugi/app/batch/exceptions/BatchException.java`:

```java
package kkdugi.app.batch.exceptions;

/** 배치 API의 모든 오류. HTTP 상태와 {@code batch.*} 오류 코드를 함께 가진다(코드는 {@link BatchErrors}). */
public class BatchException extends RuntimeException {

    private final int status;
    private final String code;

    public BatchException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static BatchException badRequest(String code) {
        return new BatchException(400, code);
    }

    public static BatchException unauthorized(String code) {
        return new BatchException(401, code);
    }

    public static BatchException forbidden(String code) {
        return new BatchException(403, code);
    }

    public static BatchException notFound(String code) {
        return new BatchException(404, code);
    }

    public static BatchException conflict(String code) {
        return new BatchException(409, code);
    }

    public static BatchException gone(String code) {
        return new BatchException(410, code);
    }

    public static BatchException preconditionFailed(String code) {
        return new BatchException(412, code);
    }

    public static BatchException tooLarge(String code) {
        return new BatchException(413, code);
    }

    public static BatchException preconditionRequired(String code) {
        return new BatchException(428, code);
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/exceptions/BatchErrors.java`:

```java
package kkdugi.app.batch.exceptions;

/** 배치 오류 코드. 모든 값은 messages*.properties에 메시지가 있어야 한다(BatchMessagesTest가 검사). */
public final class BatchErrors {

    public static final String REQUEST_INVALID = "batch.request.invalid";
    public static final String CREDENTIAL_INVALID = "batch.credential.invalid";
    public static final String RUNNER_FORBIDDEN = "batch.runner.forbidden";
    public static final String RUNNER_NOT_FOUND = "batch.runner.not_found";
    public static final String RUNNER_DUPLICATE_CODE = "batch.runner.duplicate_code";
    public static final String STATE_CONFLICT = "batch.state.conflict";
    public static final String SESSION_STALE = "batch.session.stale";
    public static final String PROTOCOL_UNSUPPORTED = "batch.protocol.unsupported";
    public static final String PLATFORM_UNSUPPORTED = "batch.platform.unsupported";
    public static final String IDEMPOTENCY_CONFLICT = "batch.idempotency.conflict";
    public static final String VERSION_CONFLICT = "batch.version.conflict";
    public static final String VERSION_REQUIRED = "batch.version.required";
    public static final String REQUEST_EXPIRED = "batch.request.expired";
    public static final String PAYLOAD_TOO_LARGE = "batch.payload.too_large";

    private BatchErrors() {
    }
}
```

- [ ] **Step 2: 메시지 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/app/batch/BatchMessagesTest.java`:

```java
package kkdugi.app.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.core.util.MessageUtils;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchMessagesTest {

    @Test
    void everyBatchErrorCodeHasAMessage() throws IllegalAccessException {
        for (Field field : BatchErrors.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                String code = (String) field.get(null);
                // useCodeAsDefaultMessage=true라 메시지가 없으면 코드 문자열이 그대로 돌아온다.
                assertThat(MessageUtils.getMessage(code)).as(code).isNotEqualTo(code);
            }
        }
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchMessagesTest`
Expected: FAIL (첫 코드 `batch.request.invalid`의 메시지가 코드와 같다).

- [ ] **Step 3: 메시지 추가**

각 파일의 마지막 줄이 개행으로 끝나는지 확인(`tail -c1`)하고 아래를 **파일 끝에 추가**한다. 기존 항목은 수정하지 않는다. 배치 키는 분리 시 함께 옮길 목록이다(ADR-0019에 기록).

`messages.properties`와 `messages_en_US.properties`:

```properties
# batch (kkdugi.app.batch) — 라이브러리 분리 시 함께 옮긴다
batch.request.invalid=The request is invalid
batch.credential.invalid=The credential is invalid, expired or revoked
batch.runner.forbidden=This credential is not allowed to perform the operation
batch.runner.not_found=The runner could not be found
batch.runner.duplicate_code=A runner with the same code already exists
batch.state.conflict=The operation conflicts with the current state
batch.session.stale=The runner session is not current
batch.protocol.unsupported=The protocol version is not supported
batch.platform.unsupported=The runner platform is not supported
batch.idempotency.conflict=The idempotency key was already used with a different request
batch.version.conflict=The resource was modified by someone else
batch.version.required=An If-Match header is required
batch.request.expired=The request is too old to be accepted
batch.payload.too_large=The payload exceeds the allowed size
```

`messages_ko_KR.properties`:

```properties
# batch (kkdugi.app.batch) — 라이브러리 분리 시 함께 옮긴다
batch.request.invalid=요청이 올바르지 않습니다
batch.credential.invalid=자격증명이 올바르지 않거나 만료 또는 폐기되었습니다
batch.runner.forbidden=이 자격증명으로는 해당 작업을 수행할 수 없습니다
batch.runner.not_found=Runner를 찾을 수 없습니다
batch.runner.duplicate_code=같은 코드의 Runner가 이미 있습니다
batch.state.conflict=현재 상태와 충돌하는 작업입니다
batch.session.stale=현재 세션이 아닙니다
batch.protocol.unsupported=지원하지 않는 프로토콜 버전입니다
batch.platform.unsupported=지원하지 않는 Runner 플랫폼입니다
batch.idempotency.conflict=같은 멱등 키가 다른 요청에 이미 사용되었습니다
batch.version.conflict=다른 사용자가 먼저 수정했습니다
batch.version.required=If-Match 헤더가 필요합니다
batch.request.expired=요청이 너무 오래되어 접수할 수 없습니다
batch.payload.too_large=허용 크기를 초과했습니다
```

`messages_jp_JA.properties`:

```properties
# batch (kkdugi.app.batch) — 라이브러리 분리 시 함께 옮긴다
batch.request.invalid=リクエストが正しくありません
batch.credential.invalid=認証情報が無効、期限切れ、または失効しています
batch.runner.forbidden=この認証情報ではこの操作を実行できません
batch.runner.not_found=Runnerが見つかりません
batch.runner.duplicate_code=同じコードのRunnerが既に存在します
batch.state.conflict=現在の状態と競合する操作です
batch.session.stale=現在のセッションではありません
batch.protocol.unsupported=サポートされていないプロトコルバージョンです
batch.platform.unsupported=サポートされていないRunnerプラットフォームです
batch.idempotency.conflict=同じ冪等キーが別のリクエストで既に使用されています
batch.version.conflict=他のユーザーが先に更新しました
batch.version.required=If-Matchヘッダーが必要です
batch.request.expired=リクエストが古すぎるため受け付けられません
batch.payload.too_large=許容サイズを超えています
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchMessagesTest`
Expected: PASS.

- [ ] **Step 4: 모델 작성**

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchRunner.java`:

```java
package kkdugi.app.batch.models;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.core.models.BaseModel;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code kkdugi_batch_runner} 한 행이자 관리자 API의 Runner 응답. bigint인 세션 세대와 설정 버전은
 * JSON에서 십진 문자열({@link #getSession()}, {@link #getVersion()})로 나가고, 부팅 식별자는 노출하지 않는다.
 */
@Getter
@Setter
@JsonIgnoreProperties({ "rownum", "createdAt", "creatorId", "updatedAt", "updaterId" })
public class BatchRunner extends BaseModel {

    private String id;
    private String code;
    private String name;
    private RunnerStatus status;
    private String hostname;
    private String os;
    private String agentVersion;
    private int capacity;
    @JsonIgnore
    private long sessionVer;
    @JsonIgnore
    private String bootRef;
    private Instant lastSeenAt;
    private boolean online;
    @JsonIgnore
    private long configVer;

    public String getSession() {
        return Long.toString(sessionVer);
    }

    public String getVersion() {
        return Long.toString(configVer);
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchRunnerParams.java`:

```java
package kkdugi.app.batch.models;

import kkdugi.core.models.BaseParams;
import lombok.Getter;
import lombok.Setter;

/** Runner 목록 필터. GET 쿼리 파라미터로 바인딩된다. */
@Getter
@Setter
public class BatchRunnerParams extends BaseParams {

    private String code;
    private String name;
    private String status;
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchHeartbeatState.java`:

```java
package kkdugi.app.batch.models;

import kkdugi.app.batch.enums.RunnerStatus;
import lombok.Getter;
import lombok.Setter;

/** heartbeat 갱신 직후 관측한 runner 상태. */
@Getter
@Setter
public class BatchHeartbeatState {

    private RunnerStatus status;
    private int capacity;
}
```

- [ ] **Step 5: 테스트 지원 클래스와 mapper 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/support/BatchTestData.java`:

```java
package kkdugi.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.service.BatchIds;

/** 배치 테스트가 공유하는 데이터 정리·삽입 도우미. 테스트가 만드는 runner 코드는 모두 {@link #CODE_PREFIX}로 시작한다. */
public final class BatchTestData {

    public static final String CODE_PREFIX = "tb-";
    public static final String USER = "U_TEST_BATCH";

    private BatchTestData() {
    }

    public static void wipe(JdbcTemplate jdbc) {
        String runnerIds = "SELECT runner_id FROM kkdugi_batch_runner WHERE runner_cd LIKE '" + CODE_PREFIX + "%'";
        jdbc.update("DELETE FROM kkdugi_batch_event WHERE target_id IN (" + runnerIds + ") OR actor_id = ?", USER);
        jdbc.update("DELETE FROM kkdugi_batch_runner_credential WHERE runner_id IN (" + runnerIds + ")");
        jdbc.update("DELETE FROM kkdugi_batch_runner WHERE runner_cd LIKE '" + CODE_PREFIX + "%'");
        jdbc.update("DELETE FROM kkdugi_batch_api_request WHERE subject_id = ?", USER);
    }

    public static String insertRunner(JdbcTemplate jdbc, String code, String status) {
        String id = BatchIds.next(BatchIds.RUNNER);
        jdbc.update("INSERT INTO kkdugi_batch_runner (runner_id, runner_cd, runner_nm, runner_stat, capacity_cnt, reg_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, code, "Test " + code, status, 1, USER);
        return id;
    }

    public static void assertError(ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BatchException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }
}
```

`kkdugi-admin/src/test/java/kkdugi/app/batch/mapper/BatchRunnerMapperTest.java`:

```java
package kkdugi.app.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.models.BatchHeartbeatState;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.service.BatchIds;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchRunnerMapperTest {

    private static final int ONLINE_SECONDS = 30;

    @Autowired
    private BatchRunnerMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    private BatchRunner insert(String code) {
        BatchRunner row = new BatchRunner();
        row.setId(BatchIds.next(BatchIds.RUNNER));
        row.setCode(code);
        row.setName("Runner " + code);
        row.setStatus(RunnerStatus.REGISTERING);
        row.setCapacity(2);
        row.setCreatorId(BatchTestData.USER);
        assertThat(mapper.insert(row)).isEqualTo(1);
        return row;
    }

    @Test
    void insertsAndFindsWithDefaults() {
        BatchRunner row = insert("tb-map-1");

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();

        assertThat(found.getCode()).isEqualTo("tb-map-1");
        assertThat(found.getStatus()).isEqualTo(RunnerStatus.REGISTERING);
        assertThat(found.getCapacity()).isEqualTo(2);
        assertThat(found.getSessionVer()).isZero();
        assertThat(found.getConfigVer()).isEqualTo(1);
        assertThat(found.getLastSeenAt()).isNull();
        assertThat(found.isOnline()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getCreatorId()).isEqualTo(BatchTestData.USER);
        assertThat(mapper.countByCode("tb-map-1")).isEqualTo(1);
        assertThat(mapper.lockById(row.getId())).isPresent();
    }

    @Test
    void updateIsGuardedByVersion() {
        BatchRunner row = insert("tb-map-2");

        assertThat(mapper.update(row.getId(), "Renamed", 5, RunnerStatus.REGISTERING, 1, BatchTestData.USER)).isEqualTo(1);
        assertThat(mapper.update(row.getId(), "Again", 5, RunnerStatus.REGISTERING, 1, BatchTestData.USER)).isZero();

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();
        assertThat(found.getName()).isEqualTo("Renamed");
        assertThat(found.getCapacity()).isEqualTo(5);
        assertThat(found.getConfigVer()).isEqualTo(2);
        assertThat(found.getUpdaterId()).isEqualTo(BatchTestData.USER);
    }

    @Test
    void searchPagesAndCountsWithWindowTotal() {
        insert("tb-map-a");
        insert("tb-map-b");
        insert("tb-map-c");

        List<BatchRunner> firstPage = mapper.search("tb-map-", null, null, 0, 2, ONLINE_SECONDS);
        List<BatchRunner> filtered = mapper.search("tb-map-", null, "ACTIVE", 0, 10, ONLINE_SECONDS);

        assertThat(firstPage).hasSize(2);
        assertThat(firstPage.get(0).getTotalSize()).isEqualTo(3);
        assertThat(filtered).isEmpty();
    }

    @Test
    void changeStatusAndRegistrationBumpTheVersion() {
        BatchRunner row = insert("tb-map-3");

        assertThat(mapper.markRegistered(row.getId(), "host-1", "LINUX", "0.1.0", row.getId())).isEqualTo(1);

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(found.getHostname()).isEqualTo("host-1");
        assertThat(found.getOs()).isEqualTo("LINUX");
        assertThat(found.getAgentVersion()).isEqualTo("0.1.0");
        assertThat(found.getConfigVer()).isEqualTo(2);

        assertThat(mapper.changeStatus(row.getId(), RunnerStatus.PAUSED, BatchTestData.USER)).isEqualTo(1);
        assertThat(mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow().getConfigVer()).isEqualTo(3);
    }

    @Test
    void openSessionAdvancesGenerationWithoutTouchingConfigVersion() {
        BatchRunner row = insert("tb-map-4");

        assertThat(mapper.openSession(row.getId(), "boot-1", "0.1.0")).isEqualTo(1);

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();
        assertThat(found.getSessionVer()).isEqualTo(1);
        assertThat(found.getBootRef()).isEqualTo("boot-1");
        assertThat(found.getConfigVer()).isEqualTo(1);
    }

    @Test
    void touchOnlyAppliesToActiveOrPausedRunnersInTheCurrentSession() {
        BatchRunner row = insert("tb-map-5");
        assertThat(mapper.touch(row.getId(), 0)).isEmpty(); // REGISTERING

        mapper.changeStatus(row.getId(), RunnerStatus.ACTIVE, BatchTestData.USER);
        BatchHeartbeatState state = mapper.touch(row.getId(), 0).orElseThrow();
        assertThat(state.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(state.getCapacity()).isEqualTo(2);
        assertThat(mapper.touch(row.getId(), 7)).isEmpty(); // 다른 세대

        BatchRunner found = mapper.findById(row.getId(), ONLINE_SECONDS).orElseThrow();
        assertThat(found.getLastSeenAt()).isNotNull();
        assertThat(found.isOnline()).isTrue();
        assertThat(found.getConfigVer()).isEqualTo(2); // touch는 버전을 올리지 않는다
    }

    @Test
    void nowReturnsAnInstantCloseToTheClock() {
        Instant now = mapper.now();
        assertThat(now).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    void deleteRemovesTheRow() {
        BatchRunner row = insert("tb-map-6");
        assertThat(mapper.deleteById(row.getId())).isEqualTo(1);
        assertThat(mapper.findById(row.getId(), ONLINE_SECONDS)).isEmpty();
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchRunnerMapperTest`
Expected: FAIL — 컴파일 오류(`BatchRunnerMapper` 없음).

- [ ] **Step 6: mapper 인터페이스와 XML 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/mapper/BatchRunnerMapper.java`:

```java
package kkdugi.app.batch.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.models.BatchHeartbeatState;
import kkdugi.app.batch.models.BatchRunner;

@Mapper
public interface BatchRunnerMapper {

    List<BatchRunner> search(@Param("code") String code, @Param("name") String name, @Param("status") String status,
            @Param("offset") int offset, @Param("limit") int limit, @Param("onlineSeconds") int onlineSeconds);

    Optional<BatchRunner> findById(@Param("id") String id, @Param("onlineSeconds") int onlineSeconds);

    /** 상태를 바꾸는 모든 작업의 첫 문장. 행 잠금(FOR UPDATE)을 잡는다. */
    Optional<BatchRunner> lockById(@Param("id") String id);

    int countByCode(@Param("code") String code);

    int insert(BatchRunner row);

    /** 낙관적 잠금 갱신. 갱신 행 수가 0이면 버전 불일치다. config_ver를 1 올린다. */
    int update(@Param("id") String id, @Param("name") String name, @Param("capacity") int capacity,
            @Param("status") RunnerStatus status, @Param("expectedVersion") long expectedVersion,
            @Param("updaterId") String updaterId);

    /** 상태만 바꾸고 config_ver를 1 올린다. */
    int changeStatus(@Param("id") String id, @Param("status") RunnerStatus status, @Param("updaterId") String updaterId);

    /** 등록 완료: 호스트 정보를 채우고 ACTIVE로 전환하며 config_ver를 1 올린다. */
    int markRegistered(@Param("id") String id, @Param("hostname") String hostname, @Param("os") String os,
            @Param("agentVersion") String agentVersion, @Param("updaterId") String updaterId);

    /** 세션 세대를 1 올리고 boot_ref를 기록한다. config_ver와 감사 컬럼은 건드리지 않는다. */
    int openSession(@Param("id") String id, @Param("bootId") String bootId, @Param("agentVersion") String agentVersion);

    /** heartbeat: last_seen_dtm만 갱신한다. ACTIVE/PAUSED이고 세션 세대가 일치할 때만 갱신되고 결과를 돌려준다. */
    Optional<BatchHeartbeatState> touch(@Param("id") String id, @Param("session") long session);

    int deleteById(@Param("id") String id);

    /** DB 현재 시각(clock_timestamp). 서버 시각 응답의 기준이다. now()가 아니라 실제 시각이다. */
    Instant now();
}
```

`kkdugi-admin/src/main/resources/mapper/postgres/app/batch/BatchRunnerMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.batch.mapper.BatchRunnerMapper">

  <resultMap id="runnerResultMap" type="kkdugi.app.batch.models.BatchRunner"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="id" column="runner_id"/>
    <result property="code" column="runner_cd"/>
    <result property="name" column="runner_nm"/>
    <result property="status" column="runner_stat"/>
    <result property="hostname" column="host_nm"/>
    <result property="os" column="os_cd"/>
    <result property="agentVersion" column="agent_ver"/>
    <result property="capacity" column="capacity_cnt"/>
    <result property="sessionVer" column="session_ver"/>
    <result property="bootRef" column="boot_ref"/>
    <result property="lastSeenAt" column="last_seen_dtm"/>
    <result property="online" column="online"/>
    <result property="configVer" column="config_ver"/>
    <result property="totalSize" column="total_size"/>
  </resultMap>

  <resultMap id="heartbeatStateResultMap" type="kkdugi.app.batch.models.BatchHeartbeatState">
    <result property="status" column="runner_stat"/>
    <result property="capacity" column="capacity_cnt"/>
  </resultMap>

  <sql id="baseColumns">
<![CDATA[
runner_id, runner_cd, runner_nm, runner_stat, host_nm, os_cd, agent_ver, capacity_cnt, session_ver, boot_ref,
last_seen_dtm, config_ver, reg_dtm, reg_id, upd_dtm, upd_id
]]>
  </sql>

  <select id="search" resultMap="runnerResultMap">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.search */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
, (last_seen_dtm IS NOT NULL AND last_seen_dtm > clock_timestamp() - make_interval(secs => #{onlineSeconds}::double precision)) AS online
, COUNT(*) OVER() AS total_size
FROM kkdugi_batch_runner
]]>
    <where>
      <if test="code != null and code != ''">
<![CDATA[
AND runner_cd LIKE '%' || #{code} || '%'
]]>
      </if>
      <if test="name != null and name != ''">
<![CDATA[
AND runner_nm LIKE '%' || #{name} || '%'
]]>
      </if>
      <if test="status != null and status != ''">
<![CDATA[
AND runner_stat = #{status}
]]>
      </if>
    </where>
<![CDATA[
ORDER BY reg_dtm DESC, runner_id DESC
OFFSET #{offset} LIMIT #{limit}
]]>
  </select>

  <select id="findById" resultMap="runnerResultMap">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.findById */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
, (last_seen_dtm IS NOT NULL AND last_seen_dtm > clock_timestamp() - make_interval(secs => #{onlineSeconds}::double precision)) AS online
FROM kkdugi_batch_runner
WHERE runner_id = #{id}
]]>
  </select>

  <select id="lockById" resultMap="runnerResultMap">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.lockById */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_batch_runner
WHERE runner_id = #{id}
FOR UPDATE
]]>
  </select>

  <select id="countByCode" resultType="int">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.countByCode */
SELECT COUNT(*) FROM kkdugi_batch_runner WHERE runner_cd = #{code}
]]>
  </select>

  <insert id="insert" parameterType="kkdugi.app.batch.models.BatchRunner">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.insert */
INSERT INTO kkdugi_batch_runner
    (runner_id, runner_cd, runner_nm, runner_stat, capacity_cnt, reg_dtm, reg_id)
VALUES
    (#{id}, #{code}, #{name}, #{status}, #{capacity}, (now() AT TIME ZONE 'UTC'), #{creatorId})
]]>
  </insert>

  <update id="update">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.update */
UPDATE kkdugi_batch_runner
SET runner_nm = #{name}, capacity_cnt = #{capacity}, runner_stat = #{status},
    config_ver = config_ver + 1, upd_dtm = (now() AT TIME ZONE 'UTC'), upd_id = #{updaterId}
WHERE runner_id = #{id} AND config_ver = #{expectedVersion}
]]>
  </update>

  <update id="changeStatus">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.changeStatus */
UPDATE kkdugi_batch_runner
SET runner_stat = #{status}, config_ver = config_ver + 1,
    upd_dtm = (now() AT TIME ZONE 'UTC'), upd_id = #{updaterId}
WHERE runner_id = #{id}
]]>
  </update>

  <update id="markRegistered">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.markRegistered */
UPDATE kkdugi_batch_runner
SET runner_stat = 'ACTIVE', host_nm = #{hostname}, os_cd = #{os}, agent_ver = #{agentVersion},
    config_ver = config_ver + 1, upd_dtm = (now() AT TIME ZONE 'UTC'), upd_id = #{updaterId}
WHERE runner_id = #{id}
]]>
  </update>

  <update id="openSession">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.openSession */
UPDATE kkdugi_batch_runner
SET session_ver = session_ver + 1, boot_ref = #{bootId}, agent_ver = #{agentVersion}
WHERE runner_id = #{id}
]]>
  </update>

  <!-- UPDATE ... RETURNING은 조회 결과를 돌려주므로 select로 실행한다. -->
  <select id="touch" resultMap="heartbeatStateResultMap">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.touch */
UPDATE kkdugi_batch_runner
SET last_seen_dtm = clock_timestamp()
WHERE runner_id = #{id} AND session_ver = #{session} AND runner_stat IN ('ACTIVE', 'PAUSED')
RETURNING runner_stat, capacity_cnt
]]>
  </select>

  <delete id="deleteById">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.deleteById */
DELETE FROM kkdugi_batch_runner WHERE runner_id = #{id}
]]>
  </delete>

  <select id="now" resultType="java.time.Instant">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchRunnerMapper.now */
SELECT clock_timestamp()
]]>
  </select>

</mapper>
```

- [ ] **Step 7: mapper 테스트 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchRunnerMapperTest`
Expected: PASS (8 tests).
막히는 지점: `now()`의 `Instant` 매핑 오류가 나면 `resultType`을 `java.sql.Timestamp`로 바꾸고 인터페이스 반환 타입도 함께 바꾼 뒤 `toInstant()`로 변환한다(다른 곳에서 `Instant`가 필요하면 `BatchRunnerMapper.now()` 호출부만 감싸면 된다).

- [ ] **Step 8: Commit**

```bash
git add kkdugi-admin/src/main/java/kkdugi/app/batch kkdugi-admin/src/main/resources/mapper/postgres/app/batch kkdugi-admin/src/main/resources/messages kkdugi-admin/src/test/java/kkdugi/app/batch kkdugi-admin/src/test/java/kkdugi/support/BatchTestData.java
git commit -m "feat(batch): add exceptions, messages, runner model and mapper" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: 토큰·자격증명·event 기반

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/models/{BatchRunnerCredential,BatchEvent}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/mapper/{BatchCredentialMapper,BatchEventMapper}.java`
- Create: `kkdugi-admin/src/main/resources/mapper/postgres/app/batch/{BatchCredentialMapper,BatchEventMapper}.xml`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/service/{BatchTokenService,BatchEventService}.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/batch/service/{BatchTokenServiceTest,BatchEventServiceTest}.java`, `kkdugi-admin/src/test/java/kkdugi/app/batch/mapper/BatchCredentialMapperTest.java`

**Interfaces:**
- Consumes: Task 1의 enum/`BatchIds`, Task 2의 `BatchTestData`.
- Produces:
  - `BatchRunnerCredential extends BaseModel`: `id, runnerId, type(CredentialType), secretHash, expiresAt(Instant), consumedAt, revokedAt, lastUsedAt(Instant), valid(boolean)`. (`creatorId`는 `BaseModel`.)
  - `BatchCredentialMapper`:
    - `int insert(@Param("row") BatchRunnerCredential row, @Param("ttlSeconds") long ttlSeconds)` (`expires_dtm = now() + ttl`, `ttlSeconds`가 음수면 이미 만료)
    - `Optional<BatchRunnerCredential> findForAuth(@Param("id") String id)` (`valid` = 폐기·만료·(등록 토큰이면)소비 여부를 DB 시각으로 계산)
    - `int consumeEnrollment(@Param("id") String id)` (원자 소비, 1이면 성공)
    - `int revokeUnusedEnrollments(@Param("runnerId") String runnerId)`, `int revokeAll(@Param("runnerId") String runnerId)`
    - `int touchLastUsed(@Param("id") String id, @Param("intervalSeconds") int intervalSeconds)` (`last_used_dtm`이 없거나 `intervalSeconds`보다 오래됐을 때만 1)
    - `int deleteByRunnerId(@Param("runnerId") String runnerId)`
  - `BatchEventMapper.int insert(BatchEvent row)`.
  - `BatchTokenService`: `String newSecret()`, `String join(String credentialId, String secret)`, `ParsedToken parse(String token)`(형식이 틀리면 `null`, `ParsedToken.getCredentialId()/getSecret()`), `String hash(String secret)`, `boolean matches(String secret, String storedHash)`, `static String sha256Hex(String value)`.
  - `BatchEventService.void record(EventTargetType, String targetId, EventType, String fromStat, String toStat, ActorType, String actorId, Map<String,Object> detail)` (`detail`은 `null` 가능).

- [ ] **Step 1: 토큰 서비스 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchTokenServiceTest.java`:

```java
package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchTokenServiceTest {

    private final BatchTokenService tokens = new BatchTokenService();

    @Test
    void secretsAreRandomUrlSafeAnd256Bits() {
        String first = tokens.newSecret();
        String second = tokens.newSecret();

        assertThat(first).isNotEqualTo(second).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void joinAndParseRoundTrip() {
        String secret = tokens.newSecret();
        String token = tokens.join("BC202609200000" + "0001", secret);

        BatchTokenService.ParsedToken parsed = tokens.parse(token);

        assertThat(parsed).isNotNull();
        assertThat(parsed.getCredentialId()).isEqualTo("BC2026092000000001");
        assertThat(parsed.getSecret()).isEqualTo(secret);
    }

    @Test
    void parseRejectsMalformedTokens() {
        assertThat(tokens.parse(null)).isNull();
        assertThat(tokens.parse("")).isNull();
        assertThat(tokens.parse("no-dot")).isNull();
        assertThat(tokens.parse("BC1.short")).isNull();
        assertThat(tokens.parse("BC1." + "a".repeat(44))).isNull();
        assertThat(tokens.parse("bad id." + "a".repeat(43))).isNull();
        // 사용자 JWT는 점이 두 개라 secret 자리에 점이 들어가 거절된다.
        assertThat(tokens.parse("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl")).isNull();
        assertThat(tokens.parse("BC1." + "a".repeat(43) + "x".repeat(200))).isNull();
    }

    @Test
    void hashIsSha256HexAndMatchesInConstantTime() {
        String secret = tokens.newSecret();
        String hash = tokens.hash(secret);

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(tokens.matches(secret, hash)).isTrue();
        assertThat(tokens.matches(tokens.newSecret(), hash)).isFalse();
        assertThat(BatchTokenService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchTokenServiceTest`
Expected: FAIL — 컴파일 오류(`BatchTokenService` 없음).

- [ ] **Step 2: 토큰 서비스 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchTokenService.java`:

```java
package kkdugi.app.batch.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Runner 토큰 발급·해석. 토큰은 {@code {credentialId}.{secret}}이고 secret은 256비트 난수라서
 * 느린 password hash가 아니라 SHA-256 hex만 DB에 저장한다(평문은 발급 응답에서 한 번만 나간다).
 */
@Service
public class BatchTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CREDENTIAL_ID = Pattern.compile("[A-Za-z0-9]{1,20}");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int MAX_TOKEN_LENGTH = 200;

    public String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String join(String credentialId, String secret) {
        return credentialId + "." + secret;
    }

    /** 형식이 올바르지 않으면 null. 존재 여부·유효성은 호출자가 DB로 확인한다. */
    public ParsedToken parse(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot < 1) {
            return null;
        }
        String credentialId = token.substring(0, dot);
        String secret = token.substring(dot + 1);
        if (!CREDENTIAL_ID.matcher(credentialId).matches() || !SECRET.matcher(secret).matches()) {
            return null;
        }
        return new ParsedToken(credentialId, secret);
    }

    public String hash(String secret) {
        return sha256Hex(secret);
    }

    public boolean matches(String secret, String storedHash) {
        return storedHash != null && MessageDigest.isEqual(
                hash(secret).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Getter
    @AllArgsConstructor
    public static class ParsedToken {

        private final String credentialId;
        private final String secret;
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchTokenServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 3: 자격증명 mapper 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/app/batch/mapper/BatchCredentialMapperTest.java`:

```java
package kkdugi.app.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.CredentialType;
import kkdugi.app.batch.models.BatchRunnerCredential;
import kkdugi.app.batch.service.BatchIds;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchCredentialMapperTest {

    @Autowired
    private BatchCredentialMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String runnerId;

    @BeforeEach
    void setUp() {
        BatchTestData.wipe(jdbc);
        runnerId = BatchTestData.insertRunner(jdbc, "tb-cred-1", "REGISTERING");
    }

    @AfterEach
    void cleanUp() {
        BatchTestData.wipe(jdbc);
    }

    private BatchRunnerCredential insert(CredentialType type, long ttlSeconds) {
        BatchRunnerCredential row = new BatchRunnerCredential();
        row.setId(BatchIds.next(BatchIds.CREDENTIAL));
        row.setRunnerId(runnerId);
        row.setType(type);
        row.setSecretHash("a".repeat(64));
        row.setCreatorId(BatchTestData.USER);
        assertThat(mapper.insert(row, ttlSeconds)).isEqualTo(1);
        return row;
    }

    @Test
    void freshCredentialIsValidAndExposesItsFields() {
        BatchRunnerCredential row = insert(CredentialType.ENROLLMENT, 600);

        BatchRunnerCredential found = mapper.findForAuth(row.getId()).orElseThrow();

        assertThat(found.isValid()).isTrue();
        assertThat(found.getRunnerId()).isEqualTo(runnerId);
        assertThat(found.getType()).isEqualTo(CredentialType.ENROLLMENT);
        assertThat(found.getSecretHash()).isEqualTo("a".repeat(64));
        assertThat(found.getExpiresAt()).isAfter(java.time.Instant.now());
        assertThat(found.getConsumedAt()).isNull();
        assertThat(mapper.findForAuth("BCNOSUCH")).isEmpty();
    }

    @Test
    void expiredCredentialIsInvalidAndCannotBeConsumed() {
        BatchRunnerCredential row = insert(CredentialType.ENROLLMENT, -1);

        assertThat(mapper.findForAuth(row.getId()).orElseThrow().isValid()).isFalse();
        assertThat(mapper.consumeEnrollment(row.getId())).isZero();
    }

    @Test
    void enrollmentTokenIsConsumedExactlyOnce() {
        BatchRunnerCredential row = insert(CredentialType.ENROLLMENT, 600);

        assertThat(mapper.consumeEnrollment(row.getId())).isEqualTo(1);
        assertThat(mapper.consumeEnrollment(row.getId())).isZero();
        assertThat(mapper.findForAuth(row.getId()).orElseThrow().isValid()).isFalse();
    }

    @Test
    void accessCredentialCannotBeConsumedAsEnrollment() {
        BatchRunnerCredential row = insert(CredentialType.ACCESS, 600);

        assertThat(mapper.consumeEnrollment(row.getId())).isZero();
        assertThat(mapper.findForAuth(row.getId()).orElseThrow().isValid()).isTrue();
    }

    @Test
    void revokeUnusedEnrollmentsSkipsConsumedAndAccessCredentials() {
        BatchRunnerCredential unused = insert(CredentialType.ENROLLMENT, 600);
        BatchRunnerCredential consumed = insert(CredentialType.ENROLLMENT, 600);
        mapper.consumeEnrollment(consumed.getId());
        BatchRunnerCredential access = insert(CredentialType.ACCESS, 600);

        assertThat(mapper.revokeUnusedEnrollments(runnerId)).isEqualTo(1);

        assertThat(mapper.findForAuth(unused.getId()).orElseThrow().isValid()).isFalse();
        assertThat(mapper.findForAuth(access.getId()).orElseThrow().isValid()).isTrue();
    }

    @Test
    void revokeAllInvalidatesEveryOpenCredential() {
        BatchRunnerCredential enrollment = insert(CredentialType.ENROLLMENT, 600);
        BatchRunnerCredential access = insert(CredentialType.ACCESS, 600);

        assertThat(mapper.revokeAll(runnerId)).isEqualTo(2);
        assertThat(mapper.revokeAll(runnerId)).isZero();

        assertThat(mapper.findForAuth(enrollment.getId()).orElseThrow().isValid()).isFalse();
        assertThat(mapper.findForAuth(access.getId()).orElseThrow().isValid()).isFalse();
    }

    @Test
    void lastUsedIsThrottled() {
        BatchRunnerCredential row = insert(CredentialType.ACCESS, 600);

        assertThat(mapper.touchLastUsed(row.getId(), 60)).isEqualTo(1);
        assertThat(mapper.touchLastUsed(row.getId(), 60)).isZero();
        assertThat(mapper.findForAuth(row.getId()).orElseThrow().getLastUsedAt()).isNotNull();
    }

    @Test
    void deleteByRunnerIdRemovesCredentials() {
        insert(CredentialType.ACCESS, 600);

        assertThat(mapper.deleteByRunnerId(runnerId)).isEqualTo(1);
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchCredentialMapperTest`
Expected: FAIL — 컴파일 오류.

- [ ] **Step 4: 자격증명·event 모델과 mapper 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchRunnerCredential.java`:

```java
package kkdugi.app.batch.models;

import java.time.Instant;

import kkdugi.app.batch.enums.CredentialType;
import kkdugi.core.models.BaseModel;
import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_batch_runner_credential} 한 행. {@code valid}는 조회 시 DB 시각으로 계산한 값이다. */
@Getter
@Setter
public class BatchRunnerCredential extends BaseModel {

    private String id;
    private String runnerId;
    private CredentialType type;
    private String secretHash;
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant revokedAt;
    private Instant lastUsedAt;
    private boolean valid;
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchEvent.java`:

```java
package kkdugi.app.batch.models;

import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.core.models.BaseModel;
import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_batch_event} 한 행(수정 불가 이력). {@code detailData}는 JSON 문자열이다. */
@Getter
@Setter
public class BatchEvent extends BaseModel {

    private String id;
    private EventTargetType targetType;
    private String targetId;
    private EventType eventType;
    private String fromStat;
    private String toStat;
    private ActorType actorType;
    private String actorId;
    private String detailData;
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/mapper/BatchCredentialMapper.java`:

```java
package kkdugi.app.batch.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.batch.models.BatchRunnerCredential;

@Mapper
public interface BatchCredentialMapper {

    /** expires_dtm = DB now() + ttlSeconds. */
    int insert(@Param("row") BatchRunnerCredential row, @Param("ttlSeconds") long ttlSeconds);

    Optional<BatchRunnerCredential> findForAuth(@Param("id") String id);

    /** 미사용·미폐기·미만료 등록 토큰을 원자적으로 소비한다. 1이면 성공. */
    int consumeEnrollment(@Param("id") String id);

    int revokeUnusedEnrollments(@Param("runnerId") String runnerId);

    int revokeAll(@Param("runnerId") String runnerId);

    /** last_used_dtm이 없거나 intervalSeconds보다 오래됐을 때만 갱신한다. */
    int touchLastUsed(@Param("id") String id, @Param("intervalSeconds") int intervalSeconds);

    int deleteByRunnerId(@Param("runnerId") String runnerId);
}
```

`kkdugi-admin/src/main/resources/mapper/postgres/app/batch/BatchCredentialMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.batch.mapper.BatchCredentialMapper">

  <resultMap id="credentialResultMap" type="kkdugi.app.batch.models.BatchRunnerCredential"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="id" column="credential_id"/>
    <result property="runnerId" column="runner_id"/>
    <result property="type" column="credential_type"/>
    <result property="secretHash" column="secret_hash"/>
    <result property="expiresAt" column="expires_dtm"/>
    <result property="consumedAt" column="consumed_dtm"/>
    <result property="revokedAt" column="revoked_dtm"/>
    <result property="lastUsedAt" column="last_used_dtm"/>
    <result property="valid" column="valid"/>
  </resultMap>

  <insert id="insert">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchCredentialMapper.insert */
INSERT INTO kkdugi_batch_runner_credential
    (credential_id, runner_id, credential_type, secret_hash, expires_dtm, reg_dtm, reg_id)
VALUES
    (#{row.id}, #{row.runnerId}, #{row.type}, #{row.secretHash},
     clock_timestamp() + make_interval(secs => #{ttlSeconds}::double precision), (now() AT TIME ZONE 'UTC'), #{row.creatorId})
]]>
  </insert>

  <select id="findForAuth" resultMap="credentialResultMap">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchCredentialMapper.findForAuth */
SELECT credential_id, runner_id, credential_type, secret_hash, expires_dtm, consumed_dtm, revoked_dtm, last_used_dtm,
       reg_dtm, reg_id, upd_dtm, upd_id,
       (revoked_dtm IS NULL AND expires_dtm > clock_timestamp()
        AND (credential_type <> 'ENROLLMENT' OR consumed_dtm IS NULL)) AS valid
FROM kkdugi_batch_runner_credential
WHERE credential_id = #{id}
]]>
  </select>

  <update id="consumeEnrollment">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchCredentialMapper.consumeEnrollment */
UPDATE kkdugi_batch_runner_credential
SET consumed_dtm = clock_timestamp(), upd_dtm = (now() AT TIME ZONE 'UTC'), upd_id = runner_id
WHERE credential_id = #{id} AND credential_type = 'ENROLLMENT'
  AND consumed_dtm IS NULL AND revoked_dtm IS NULL AND expires_dtm > clock_timestamp()
]]>
  </update>

  <update id="revokeUnusedEnrollments">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchCredentialMapper.revokeUnusedEnrollments */
UPDATE kkdugi_batch_runner_credential
SET revoked_dtm = clock_timestamp(), upd_dtm = (now() AT TIME ZONE 'UTC')
WHERE runner_id = #{runnerId} AND credential_type = 'ENROLLMENT'
  AND consumed_dtm IS NULL AND revoked_dtm IS NULL
]]>
  </update>

  <update id="revokeAll">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchCredentialMapper.revokeAll */
UPDATE kkdugi_batch_runner_credential
SET revoked_dtm = clock_timestamp(), upd_dtm = (now() AT TIME ZONE 'UTC')
WHERE runner_id = #{runnerId} AND revoked_dtm IS NULL
]]>
  </update>

  <update id="touchLastUsed">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchCredentialMapper.touchLastUsed */
UPDATE kkdugi_batch_runner_credential
SET last_used_dtm = clock_timestamp()
WHERE credential_id = #{id}
  AND (last_used_dtm IS NULL OR last_used_dtm < clock_timestamp() - make_interval(secs => #{intervalSeconds}::double precision))
]]>
  </update>

  <delete id="deleteByRunnerId">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchCredentialMapper.deleteByRunnerId */
DELETE FROM kkdugi_batch_runner_credential WHERE runner_id = #{runnerId}
]]>
  </delete>

</mapper>
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/mapper/BatchEventMapper.java`:

```java
package kkdugi.app.batch.mapper;

import org.apache.ibatis.annotations.Mapper;

import kkdugi.app.batch.models.BatchEvent;

@Mapper
public interface BatchEventMapper {

    int insert(BatchEvent row);
}
```

`kkdugi-admin/src/main/resources/mapper/postgres/app/batch/BatchEventMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.batch.mapper.BatchEventMapper">

  <insert id="insert" parameterType="kkdugi.app.batch.models.BatchEvent">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchEventMapper.insert */
INSERT INTO kkdugi_batch_event
    (event_id, target_type, target_id, event_type, from_stat, to_stat, actor_type, actor_id,
     occurred_dtm, detail_data, reg_dtm, reg_id)
VALUES
    (#{id}, #{targetType}, #{targetId}, #{eventType}, #{fromStat,jdbcType=VARCHAR}, #{toStat,jdbcType=VARCHAR},
     #{actorType}, #{actorId}, clock_timestamp(), CAST(#{detailData,jdbcType=VARCHAR} AS jsonb),
     (now() AT TIME ZONE 'UTC'), #{actorId})
]]>
  </insert>

</mapper>
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchCredentialMapperTest`
Expected: PASS (8 tests).

- [ ] **Step 5: event 서비스 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchEventServiceTest.java`:

```java
package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchEventServiceTest {

    @Autowired
    private BatchEventService events;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    @Test
    void recordsStateTransitionWithDetail() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-evt-1", "REGISTERING");

        events.record(EventTargetType.RUNNER, runnerId, EventType.UPDATED, "REGISTERING", "ACTIVE",
                ActorType.USER, BatchTestData.USER, Map.of("reason", "test", "capacity", 3));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM kkdugi_batch_event WHERE target_id = ?", runnerId);
        assertThat(row.get("event_id").toString()).startsWith("BE").hasSize(18);
        assertThat(row.get("target_type")).isEqualTo("RUNNER");
        assertThat(row.get("event_type")).isEqualTo("UPDATED");
        assertThat(row.get("from_stat")).isEqualTo("REGISTERING");
        assertThat(row.get("to_stat")).isEqualTo("ACTIVE");
        assertThat(row.get("actor_type")).isEqualTo("USER");
        assertThat(row.get("actor_id")).isEqualTo(BatchTestData.USER);
        assertThat(row.get("occurred_dtm")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT detail_data ->> 'reason' FROM kkdugi_batch_event WHERE target_id = ?",
                String.class, runnerId)).isEqualTo("test");
    }

    @Test
    void allowsEventsWithoutStatesOrDetail() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-evt-2", "ACTIVE");

        events.record(EventTargetType.RUNNER, runnerId, EventType.SESSION_OPENED, null, null,
                ActorType.RUNNER, runnerId, null);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM kkdugi_batch_event WHERE target_id = ?", runnerId);
        assertThat(row.get("from_stat")).isNull();
        assertThat(row.get("detail_data")).isNull();
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchEventServiceTest`
Expected: FAIL — 컴파일 오류(`BatchEventService` 없음).

- [ ] **Step 6: event 서비스 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchEventService.java`:

```java
package kkdugi.app.batch.service;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.app.batch.mapper.BatchEventMapper;
import kkdugi.app.batch.models.BatchEvent;
import tools.jackson.databind.ObjectMapper;

/**
 * 상태 전이·운영 감사 이력 기록. 호출자의 트랜잭션에 참여하므로 상태 변경과 event가 함께 커밋/롤백된다.
 * {@code detail}에는 토큰·비밀 값을 넣지 않는다(credential ID까지만).
 */
@Service
public class BatchEventService {

    private final BatchEventMapper mapper;
    private final ObjectMapper objectMapper;

    public BatchEventService(BatchEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(EventTargetType targetType, String targetId, EventType eventType, String fromStat,
            String toStat, ActorType actorType, String actorId, Map<String, Object> detail) {
        BatchEvent row = new BatchEvent();
        row.setId(BatchIds.next(BatchIds.EVENT));
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setEventType(eventType);
        row.setFromStat(fromStat);
        row.setToStat(toStat);
        row.setActorType(actorType);
        row.setActorId(actorId);
        row.setDetailData(detail == null || detail.isEmpty() ? null : objectMapper.writeValueAsString(detail));
        mapper.insert(row);
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest="BatchEventServiceTest,BatchCredentialMapperTest,BatchTokenServiceTest"`
Expected: PASS.
막히는 지점: `objectMapper.writeValueAsString`이 checked 예외로 컴파일 오류가 나면 Jackson 3가 아니라 2를 import한 것이다 — 반드시 `tools.jackson.databind.ObjectMapper`인지 확인한다.

- [ ] **Step 7: Commit**

```bash
git add kkdugi-admin/src/main/java/kkdugi/app/batch kkdugi-admin/src/main/resources/mapper/postgres/app/batch kkdugi-admin/src/test/java/kkdugi/app/batch
git commit -m "feat(batch): add token, credential and event foundations" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Runner 관리 서비스 (생성·조회·수정·삭제)

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/models/{BatchStrictRequest,BatchStrictString,BatchStrictInteger,BatchRunnerRequest}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchRunnerService.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/batch/models/BatchStrictJsonTest.java`, `kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchRunnerServiceTest.java`

**Interfaces:**
- Consumes: `BatchRunnerMapper`(Task 2), `BatchCredentialMapper`(Task 3), `BatchEventService`(Task 3), `BatchProperties`, `BatchIds`, `BatchException`/`BatchErrors`.
- Produces:
  - `BatchStrictRequest`(abstract): 알 수 없는 JSON 필드를 만나면 `IllegalArgumentException` → 컨트롤러에서 400.
  - `BatchStrictString`/`BatchStrictInteger`(`ValueDeserializer`): 각각 JSON 문자열/JSON 정수 토큰만 받는다(그 외 타입은 역직렬화 실패 → 400, `null`은 그대로 `null`). 모든 배치 요청 DTO의 `String`/`Integer` 필드에 `@JsonDeserialize(using = …)`로 붙인다.
  - `BatchRunnerRequest extends BatchStrictRequest`: `String code, String name, Integer capacity, String status` (getter/setter).
  - `BatchRunnerService`:
    - `Page<BatchRunner> search(BatchRunnerParams params)`
    - `BatchRunner get(String id)` (없으면 404 `RUNNER_NOT_FOUND`)
    - `BatchRunner create(BatchRunnerRequest request, String actorId)`
    - `BatchRunner update(String id, BatchRunnerRequest request, long expectedVersion, String actorId)`
    - `void delete(String id, String actorId)`

- [ ] **Step 1: 요청 클래스 작성**

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchStrictRequest.java`:

```java
package kkdugi.app.batch.models;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * 배치 API 요청 본문의 공통 부모. 계약상 알 수 없는 요청 필드는 400이다. Spring Boot는 전역으로
 * FAIL_ON_UNKNOWN_PROPERTIES를 끄므로 any-setter에서 거절한다(역직렬화 실패 → HttpMessageNotReadableException).
 */
public abstract class BatchStrictRequest {

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown field: " + name);
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchRunnerRequest.java`:

```java
package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Runner 생성/수정 본문. 생성은 code/name/capacity만 받고(status는 무시하지 않고 거절), 수정은 status를 선택으로 받는다.
 * 문자열·정수 필드는 JSON 토큰 타입을 엄격히 검사한다(1.9, "1" 같은 값을 capacity=1로 바꾸지 않는다).
 */
@Getter
@Setter
public class BatchRunnerRequest extends BatchStrictRequest {

    @JsonDeserialize(using = BatchStrictString.class)
    private String code;
    @JsonDeserialize(using = BatchStrictString.class)
    private String name;
    @JsonDeserialize(using = BatchStrictInteger.class)
    private Integer capacity;
    @JsonDeserialize(using = BatchStrictString.class)
    private String status;
}
```

Jackson은 기본적으로 `1.9`→`1`, `"1"`→`1`, `1`→`"1"`로 조용히 변환한다(저장소의 Jackson 3.1.5에서 재현 확인; `@JsonFormat(lenient = OptBoolean.FALSE)`는 효과가 없다). 그래서 배치 요청 DTO의 필드마다 아래 두 deserializer를 붙인다. 전역 설정은 바꾸지 않는다.

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchStrictString.java`:

```java
package kkdugi.app.batch.models;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** JSON 문자열 토큰만 받는다(숫자·불리언·객체는 거절). {@code null}은 이 deserializer까지 오지 않고 그대로 null이다. */
public class BatchStrictString extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            return (String) ctxt.handleUnexpectedToken(String.class, p);
        }
        return p.getString();
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchStrictInteger.java`:

```java
package kkdugi.app.batch.models;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** JSON 정수 토큰만 받는다(실수·문자열·불리언·배열은 거절, int 범위를 넘는 정수도 거절). */
public class BatchStrictInteger extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) {
        if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
        }
        return p.getIntValue();
    }
}
```

`kkdugi-admin/src/test/java/kkdugi/app/batch/models/BatchStrictJsonTest.java` (운영 `ObjectMapper` 그대로, 원문 JSON부터 DTO까지 검증한다. 구현과 함께 작성하므로 첫 실행부터 통과해야 하며, `@JsonDeserialize`를 지우면 실패하는 것을 확인한다):

```java
package kkdugi.app.batch.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import kkdugi.KkdugiAdminApplication;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchStrictJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    private void rejects(String json) {
        assertThatThrownBy(() -> objectMapper.readValue(json, BatchRunnerRequest.class)).as(json)
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void capacityMustBeAJsonInteger() {
        assertThat(objectMapper.readValue("{\"capacity\":2}", BatchRunnerRequest.class).getCapacity()).isEqualTo(2);
        for (String bad : List.of("1.9", "1.0", "\"1\"", "true", "[1]", "{}", "1e2", "99999999999")) {
            rejects("{\"capacity\":" + bad + "}");
        }
    }

    @Test
    void textFieldsMustBeJsonStrings() {
        assertThat(objectMapper.readValue("{\"code\":\"c\",\"name\":\"n\",\"status\":\"ACTIVE\"}", BatchRunnerRequest.class).getName())
                .isEqualTo("n");
        for (String field : List.of("code", "name", "status")) {
            for (String bad : List.of("1", "true", "{}", "[]")) {
                rejects("{\"" + field + "\":" + bad + "}");
            }
        }
    }

    @Test
    void nullsStayNullSoServiceValidationReportsThem() {
        BatchRunnerRequest request = objectMapper.readValue("{\"code\":null,\"name\":null,\"capacity\":null}", BatchRunnerRequest.class);
        assertThat(request.getCode()).isNull();
        assertThat(request.getCapacity()).isNull();
    }

    @Test
    void unknownFieldsStillFail() {
        rejects("{\"code\":\"c\",\"extra\":1}");
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchStrictJsonTest`
Expected: PASS (4 tests). 컴파일 오류가 나면 Jackson 3 API 이름(`tools.jackson.databind.ValueDeserializer`, `tools.jackson.databind.annotation.JsonDeserialize`, `tools.jackson.core.JsonParser#getString/getIntValue/currentToken`)을 저장소의 3.1.5 jar와 대조한다.

- [ ] **Step 2: 서비스 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchRunnerServiceTest.java`:

```java
package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static kkdugi.support.BatchTestData.assertError;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerParams;
import kkdugi.app.batch.models.BatchRunnerRequest;
import kkdugi.core.models.Page;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchRunnerServiceTest {

    private static final String ACTOR = BatchTestData.USER;

    @Autowired
    private BatchRunnerService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    private static BatchRunnerRequest request(String code, String name, Integer capacity, String status) {
        BatchRunnerRequest request = new BatchRunnerRequest();
        request.setCode(code);
        request.setName(name);
        request.setCapacity(capacity);
        request.setStatus(status);
        return request;
    }

    private BatchRunner create(String code) {
        return service.create(request(code, "Runner " + code, 2, null), ACTOR);
    }

    @Test
    void createStartsRegisteringWithVersionOneAndWritesAnEvent() {
        BatchRunner created = create("tb-svc-1");

        assertThat(created.getId()).startsWith("BR").hasSize(18);
        assertThat(created.getStatus()).isEqualTo(RunnerStatus.REGISTERING);
        assertThat(created.getVersion()).isEqualTo("1");
        assertThat(created.getSession()).isEqualTo("0");
        assertThat(created.getCapacity()).isEqualTo(2);
        assertThat(created.getCreatorId()).isEqualTo(ACTOR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                + "AND event_type = 'CREATED' AND actor_id = ?", Integer.class, created.getId(), ACTOR)).isEqualTo(1);
    }

    @Test
    void createValidatesFields() {
        assertError(() -> service.create(request(null, "n", 1, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("bad code!", "n", 1, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("x".repeat(21), "n", 1, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", " ", 1, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", "n", null, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", "n", 0, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", "n", 201, null), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.create(request("tb-svc-v", "n", 1, "ACTIVE"), ACTOR), 400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void createRejectsDuplicateCode() {
        create("tb-svc-2");

        assertError(() -> create("tb-svc-2"), 409, BatchErrors.RUNNER_DUPLICATE_CODE);
    }

    @Test
    void getReturnsTheRunnerOrNotFound() {
        BatchRunner created = create("tb-svc-3");

        assertThat(service.get(created.getId()).getCode()).isEqualTo("tb-svc-3");
        assertError(() -> service.get("BRNOSUCH"), 404, BatchErrors.RUNNER_NOT_FOUND);
    }

    @Test
    void searchNormalizesPagingAndFilters() {
        create("tb-svc-a");
        create("tb-svc-b");
        BatchRunnerParams params = new BatchRunnerParams();
        params.setCode("tb-svc-");

        Page<BatchRunner> page = service.search(params);

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getTotalItems()).isEqualTo(2);
        assertThat(page.getContents()).extracting(BatchRunner::getCode).containsExactly("tb-svc-b", "tb-svc-a");

        params.setStatus("BOGUS");
        assertError(() -> service.search(params), 400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void updateRenamesAndBumpsTheVersion() {
        BatchRunner created = create("tb-svc-4");

        BatchRunner updated = service.update(created.getId(), request("tb-svc-4", "Renamed", 5, null), 1, ACTOR);

        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getCapacity()).isEqualTo(5);
        assertThat(updated.getVersion()).isEqualTo("2");
        assertThat(updated.getStatus()).isEqualTo(RunnerStatus.REGISTERING);
        assertThat(updated.getUpdaterId()).isEqualTo(ACTOR);
    }

    @Test
    void updateRejectsStaleVersionMissingRunnerAndCodeChange() {
        BatchRunner created = create("tb-svc-5");
        service.update(created.getId(), request("tb-svc-5", "v2", 2, null), 1, ACTOR);

        assertError(() -> service.update(created.getId(), request("tb-svc-5", "stale", 2, null), 1, ACTOR),
                412, BatchErrors.VERSION_CONFLICT);
        assertError(() -> service.update("BRNOSUCH", request("x", "n", 1, null), 1, ACTOR),
                404, BatchErrors.RUNNER_NOT_FOUND);
        assertError(() -> service.update(created.getId(), request("tb-other", "n", 2, null), 2, ACTOR),
                400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void statusChangesOnlyBetweenActiveAndPaused() {
        BatchRunner created = create("tb-svc-6");
        // REGISTERING에서는 status를 바꿀 수 없다.
        assertError(() -> service.update(created.getId(), request("tb-svc-6", "n", 2, "ACTIVE"), 1, ACTOR),
                409, BatchErrors.STATE_CONFLICT);

        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'ACTIVE' WHERE runner_id = ?", created.getId());

        BatchRunner paused = service.update(created.getId(), request("tb-svc-6", "n", 2, "PAUSED"), 1, ACTOR);
        assertThat(paused.getStatus()).isEqualTo(RunnerStatus.PAUSED);
        assertError(() -> service.update(created.getId(), request("tb-svc-6", "n", 2, "REVOKED"), 2, ACTOR),
                400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.update(created.getId(), request("tb-svc-6", "n", 2, "REGISTERING"), 2, ACTOR),
                400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void revokedRunnerStatusCannotBeChanged() {
        BatchRunner created = create("tb-svc-7");
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'REVOKED' WHERE runner_id = ?", created.getId());

        assertError(() -> service.update(created.getId(), request("tb-svc-7", "n", 2, "ACTIVE"), 1, ACTOR),
                409, BatchErrors.STATE_CONFLICT);
    }

    @Test
    void deleteIsAllowedOnlyWhenRegisteringOrRevoked() {
        BatchRunner registering = create("tb-svc-8");
        service.delete(registering.getId(), ACTOR);
        assertError(() -> service.get(registering.getId()), 404, BatchErrors.RUNNER_NOT_FOUND);
        // 감사 이력은 남는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                + "AND event_type = 'DELETED'", Integer.class, registering.getId())).isEqualTo(1);

        BatchRunner active = create("tb-svc-9");
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'ACTIVE' WHERE runner_id = ?", active.getId());
        assertError(() -> service.delete(active.getId(), ACTOR), 409, BatchErrors.STATE_CONFLICT);

        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'REVOKED' WHERE runner_id = ?", active.getId());
        service.delete(active.getId(), ACTOR);
        assertError(() -> service.delete("BRNOSUCH", ACTOR), 404, BatchErrors.RUNNER_NOT_FOUND);
    }

    @Test
    void deleteRemovesTheRunnersCredentials() {
        BatchRunner created = create("tb-svc-10");
        jdbc.update("INSERT INTO kkdugi_batch_runner_credential (credential_id, runner_id, credential_type, "
                + "secret_hash, expires_dtm, reg_id) VALUES (?, ?, 'ENROLLMENT', repeat('a', 64), now(), ?)",
                BatchIds.next(BatchIds.CREDENTIAL), created.getId(), ACTOR);

        service.delete(created.getId(), ACTOR);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential WHERE runner_id = ?",
                Integer.class, created.getId())).isZero();
    }

    @Test
    void failedCreateLeavesNoEvent() {
        create("tb-svc-11");
        Integer before = jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE actor_id = ?",
                Integer.class, ACTOR);

        assertError(() -> create("tb-svc-11"), 409, BatchErrors.RUNNER_DUPLICATE_CODE);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE actor_id = ?",
                Integer.class, ACTOR)).isEqualTo(before);
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchRunnerServiceTest`
Expected: FAIL — 컴파일 오류(`BatchRunnerService` 없음).

- [ ] **Step 3: 서비스 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchRunnerService.java`:

```java
package kkdugi.app.batch.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.batch.config.BatchProperties;
import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.mapper.BatchCredentialMapper;
import kkdugi.app.batch.mapper.BatchRunnerMapper;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerParams;
import kkdugi.app.batch.models.BatchRunnerRequest;
import kkdugi.core.models.Page;

/**
 * Runner 설정 관리(관리자 API). 상태를 바꾸는 모든 메서드는 runner 행 잠금 아래에서 동작하고
 * event를 같은 트랜잭션에 남긴다. 멱등 키 처리는 호출자({@link BatchIdempotencyService})의 몫이다.
 */
@Service
public class BatchRunnerService {

    static final int MAX_CODE_LENGTH = 20;
    static final int MAX_NAME_LENGTH = 200;
    static final int MIN_CAPACITY = 1;
    static final int MAX_CAPACITY = 200;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,19}");

    private final BatchRunnerMapper runnerMapper;
    private final BatchCredentialMapper credentialMapper;
    private final BatchEventService events;
    private final BatchProperties properties;

    public BatchRunnerService(BatchRunnerMapper runnerMapper, BatchCredentialMapper credentialMapper,
            BatchEventService events, BatchProperties properties) {
        this.runnerMapper = runnerMapper;
        this.credentialMapper = credentialMapper;
        this.events = events;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Page<BatchRunner> search(BatchRunnerParams params) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());
        String status = params.getStatus();
        if (status != null && !status.isBlank()) {
            try {
                RunnerStatus.fromCode(status);
            } catch (IllegalArgumentException e) {
                throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
            }
        }
        return Page.of(runnerMapper.search(params.getCode(), params.getName(), status, params.getOffset(),
                params.getLimit(), properties.getOnlineSeconds()), params);
    }

    @Transactional(readOnly = true)
    public BatchRunner get(String id) {
        return runnerMapper.findById(id, properties.getOnlineSeconds())
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
    }

    @Transactional
    public BatchRunner create(BatchRunnerRequest request, String actorId) {
        String code = requireCode(request.getCode());
        String name = requireName(request.getName());
        int capacity = requireCapacity(request.getCapacity());
        if (request.getStatus() != null) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        if (runnerMapper.countByCode(code) > 0) {
            throw BatchException.conflict(BatchErrors.RUNNER_DUPLICATE_CODE);
        }
        BatchRunner row = new BatchRunner();
        row.setId(BatchIds.next(BatchIds.RUNNER));
        row.setCode(code);
        row.setName(name);
        row.setStatus(RunnerStatus.REGISTERING);
        row.setCapacity(capacity);
        row.setCreatorId(actorId);
        try {
            runnerMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw BatchException.conflict(BatchErrors.RUNNER_DUPLICATE_CODE);
        }
        events.record(EventTargetType.RUNNER, row.getId(), EventType.CREATED, null, RunnerStatus.REGISTERING.getCode(),
                ActorType.USER, actorId, detail("code", code, "capacity", capacity));
        return get(row.getId());
    }

    @Transactional
    public BatchRunner update(String id, BatchRunnerRequest request, long expectedVersion, String actorId) {
        BatchRunner current = runnerMapper.lockById(id)
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
        if (current.getConfigVer() != expectedVersion) {
            throw BatchException.preconditionFailed(BatchErrors.VERSION_CONFLICT);
        }
        String code = requireCode(request.getCode());
        if (!code.equals(current.getCode())) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID); // code는 생성 후 불변
        }
        String name = requireName(request.getName());
        int capacity = requireCapacity(request.getCapacity());

        RunnerStatus target = current.getStatus();
        if (request.getStatus() != null) {
            RunnerStatus requested;
            try {
                requested = RunnerStatus.fromCode(request.getStatus());
            } catch (IllegalArgumentException e) {
                throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
            }
            if (requested != RunnerStatus.ACTIVE && requested != RunnerStatus.PAUSED) {
                throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
            }
            if (current.getStatus() != RunnerStatus.ACTIVE && current.getStatus() != RunnerStatus.PAUSED) {
                throw BatchException.conflict(BatchErrors.STATE_CONFLICT); // 등록 전/폐기 후에는 상태를 바꿀 수 없다
            }
            target = requested;
        }
        // S3: 이 위치에서 capacity를 점유 슬롯보다 작게 줄이는 요청을 409로 막는다.
        if (runnerMapper.update(id, name, capacity, target, expectedVersion, actorId) != 1) {
            throw BatchException.preconditionFailed(BatchErrors.VERSION_CONFLICT);
        }
        events.record(EventTargetType.RUNNER, id, EventType.UPDATED, current.getStatus().getCode(), target.getCode(),
                ActorType.USER, actorId, detail("name", name, "capacity", capacity));
        return get(id);
    }

    @Transactional
    public void delete(String id, String actorId) {
        BatchRunner current = runnerMapper.lockById(id)
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
        if (current.getStatus() != RunnerStatus.REGISTERING && current.getStatus() != RunnerStatus.REVOKED) {
            throw BatchException.conflict(BatchErrors.STATE_CONFLICT); // 먼저 폐기해야 한다
        }
        // S3: attempt가 이 runner를 참조하면 FK 위반을 409 STATE_CONFLICT로 변환한다.
        credentialMapper.deleteByRunnerId(id);
        runnerMapper.deleteById(id);
        events.record(EventTargetType.RUNNER, id, EventType.DELETED, current.getStatus().getCode(), null,
                ActorType.USER, actorId, detail("code", current.getCode()));
    }

    private static String requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        return code;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME_LENGTH) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        return name.strip();
    }

    private static int requireCapacity(Integer capacity) {
        if (capacity == null || capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        return capacity;
    }

    private static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            detail.put((String) keyValues[i], keyValues[i + 1]);
        }
        return detail;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchRunnerServiceTest`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add kkdugi-admin/src/main/java/kkdugi/app/batch kkdugi-admin/src/test/java/kkdugi/app/batch
git commit -m "feat(batch): add runner management service" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: 등록 토큰 발급과 폐기 서비스

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/models/{BatchEnrollmentResult,BatchRevokeRequest}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchEnrollmentService.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchEnrollmentServiceTest.java`

**Interfaces:**
- Consumes: `BatchRunnerMapper`, `BatchCredentialMapper`, `BatchTokenService`, `BatchEventService`, `BatchProperties`, `BatchIds`(앞선 태스크).
- Produces:
  - `BatchEnrollmentResult`(`getCredentialId()`, `getEnrollmentToken()`, `getExpiresAt():String`), `BatchRevokeRequest extends BatchStrictRequest`(`getReason()/setReason(String)`).
  - `BatchEnrollmentService`: `BatchEnrollmentResult issue(String runnerId, String actorId)`, `BatchRunner revoke(String runnerId, String reason, String actorId)`.

- [ ] **Step 1: 결과·요청 클래스 작성**

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchEnrollmentResult.java`:

```java
package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 등록 토큰 발급 응답. 평문 토큰은 이 응답에서 한 번만 나가며 저장·재현되지 않는다. */
@Getter
@AllArgsConstructor
public class BatchEnrollmentResult {

    private final String credentialId;
    private final String enrollmentToken;
    private final String expiresAt;
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchRevokeRequest.java`:

```java
package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

@Getter
@Setter
public class BatchRevokeRequest extends BatchStrictRequest {

    @JsonDeserialize(using = BatchStrictString.class)
    private String reason;
}
```

- [ ] **Step 2: 서비스 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchEnrollmentServiceTest.java`:

```java
package kkdugi.app.batch.service;

import static kkdugi.support.BatchTestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.models.BatchEnrollmentResult;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchEnrollmentServiceTest {

    private static final String ACTOR = BatchTestData.USER;

    @Autowired
    private BatchEnrollmentService service;

    @Autowired
    private BatchTokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    private void insertAccessCredential(String runnerId) {
        jdbc.update("INSERT INTO kkdugi_batch_runner_credential (credential_id, runner_id, credential_type, "
                + "secret_hash, expires_dtm, reg_id) VALUES (?, ?, 'ACCESS', repeat('b', 64), now() + interval '1 day', ?)",
                BatchIds.next(BatchIds.CREDENTIAL), runnerId, ACTOR);
    }

    @Test
    void issueCreatesAOneTimeTokenThatExpiresInTenMinutes() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-1", "REGISTERING");

        BatchEnrollmentResult result = service.issue(runnerId, ACTOR);

        BatchTokenService.ParsedToken parsed = tokens.parse(result.getEnrollmentToken());
        assertThat(parsed).isNotNull();
        assertThat(parsed.getCredentialId()).isEqualTo(result.getCredentialId());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT credential_type, secret_hash FROM kkdugi_batch_runner_credential WHERE credential_id = ?",
                result.getCredentialId());
        assertThat(row.get("credential_type")).isEqualTo("ENROLLMENT");
        assertThat(row.get("secret_hash")).isEqualTo(tokens.hash(parsed.getSecret()));
        Long seconds = jdbc.queryForObject("SELECT extract(epoch FROM (expires_dtm - now()))::bigint "
                + "FROM kkdugi_batch_runner_credential WHERE credential_id = ?", Long.class, result.getCredentialId());
        assertThat(seconds).isBetween(590L, 600L);
        assertThat(Instant.parse(result.getExpiresAt())).isAfter(Instant.now());
    }

    @Test
    void issueRecordsAnEventWithoutTheSecret() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-2", "REGISTERING");

        BatchEnrollmentResult result = service.issue(runnerId, ACTOR);

        Map<String, Object> event = jdbc.queryForMap("SELECT event_type, actor_type, actor_id, detail_data::text AS detail "
                + "FROM kkdugi_batch_event WHERE target_id = ?", runnerId);
        assertThat(event.get("event_type")).isEqualTo("ENROLLMENT_ISSUED");
        assertThat(event.get("actor_type")).isEqualTo("USER");
        assertThat(event.get("actor_id")).isEqualTo(ACTOR);
        assertThat(event.get("detail").toString()).contains(result.getCredentialId());
        assertThat(event.get("detail").toString()).doesNotContain(tokens.parse(result.getEnrollmentToken()).getSecret());
    }

    @Test
    void issuingAgainRevokesThePreviousUnusedToken() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-3", "REGISTERING");
        BatchEnrollmentResult first = service.issue(runnerId, ACTOR);

        BatchEnrollmentResult second = service.issue(runnerId, ACTOR);

        assertThat(jdbc.queryForObject("SELECT revoked_dtm IS NOT NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, first.getCredentialId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT revoked_dtm IS NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, second.getCredentialId())).isTrue();
    }

    @Test
    void issueIsRejectedForActiveOrPausedRunnersAndUnknownIds() {
        String active = BatchTestData.insertRunner(jdbc, "tb-enr-4", "ACTIVE");
        String paused = BatchTestData.insertRunner(jdbc, "tb-enr-5", "PAUSED");

        assertError(() -> service.issue(active, ACTOR), 409, BatchErrors.STATE_CONFLICT);
        assertError(() -> service.issue(paused, ACTOR), 409, BatchErrors.STATE_CONFLICT);
        assertError(() -> service.issue("BRNOSUCH", ACTOR), 404, BatchErrors.RUNNER_NOT_FOUND);
    }

    @Test
    void issueForARevokedRunnerReturnsItToRegisteringAndKeepsTheSessionGeneration() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-6", "REVOKED");
        jdbc.update("UPDATE kkdugi_batch_runner SET session_ver = 3 WHERE runner_id = ?", runnerId);

        service.issue(runnerId, ACTOR);

        Map<String, Object> runner = jdbc.queryForMap(
                "SELECT runner_stat, session_ver, config_ver FROM kkdugi_batch_runner WHERE runner_id = ?", runnerId);
        assertThat(runner.get("runner_stat")).isEqualTo("REGISTERING");
        assertThat(((Number) runner.get("session_ver")).longValue()).isEqualTo(3);
        assertThat(((Number) runner.get("config_ver")).longValue()).isEqualTo(2);
    }

    @Test
    void revokeInvalidatesEveryCredentialAndRecordsTheReason() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-7", "ACTIVE");
        insertAccessCredential(runnerId);
        jdbc.update("INSERT INTO kkdugi_batch_runner_credential (credential_id, runner_id, credential_type, "
                + "secret_hash, expires_dtm, reg_id) VALUES (?, ?, 'ENROLLMENT', repeat('c', 64), "
                + "now() + interval '10 minute', ?)", BatchIds.next(BatchIds.CREDENTIAL), runnerId, ACTOR);

        BatchRunner revoked = service.revoke(runnerId, "machine retired", ACTOR);

        assertThat(revoked.getStatus()).isEqualTo(RunnerStatus.REVOKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND revoked_dtm IS NULL", Integer.class, runnerId)).isZero();
        assertThat(jdbc.queryForObject("SELECT detail_data ->> 'reason' FROM kkdugi_batch_event "
                + "WHERE target_id = ? AND event_type = 'REVOKED'", String.class, runnerId)).isEqualTo("machine retired");
    }

    @Test
    void revokingAnAlreadyRevokedRunnerIsANoOp() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-8", "ACTIVE");
        service.revoke(runnerId, "first", ACTOR);

        BatchRunner again = service.revoke(runnerId, "second", ACTOR);

        assertThat(again.getStatus()).isEqualTo(RunnerStatus.REVOKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                + "AND event_type = 'REVOKED'", Integer.class, runnerId)).isEqualTo(1);
    }

    @Test
    void revokeValidatesTheReasonAndTheRunner() {
        String runnerId = BatchTestData.insertRunner(jdbc, "tb-enr-9", "ACTIVE");

        assertError(() -> service.revoke(runnerId, null, ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.revoke(runnerId, "  ", ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.revoke(runnerId, "x".repeat(4001), ACTOR), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> service.revoke("BRNOSUCH", "reason", ACTOR), 404, BatchErrors.RUNNER_NOT_FOUND);
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchEnrollmentServiceTest`
Expected: FAIL — 컴파일 오류(`BatchEnrollmentService` 없음).

- [ ] **Step 3: 서비스 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchEnrollmentService.java`:

```java
package kkdugi.app.batch.service;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.batch.config.BatchProperties;
import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.CredentialType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.mapper.BatchCredentialMapper;
import kkdugi.app.batch.mapper.BatchRunnerMapper;
import kkdugi.app.batch.models.BatchEnrollmentResult;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerCredential;

/**
 * 등록 토큰 발급과 자격증명 폐기. 모든 메서드는 runner 행을 먼저 잠근다 —
 * 등록/세션 개설/폐기와 같은 잠금을 공유해야 상태 전이가 직렬화된다.
 */
@Service
public class BatchEnrollmentService {

    private static final int MAX_REASON_LENGTH = 4000;

    private final BatchRunnerMapper runnerMapper;
    private final BatchCredentialMapper credentialMapper;
    private final BatchTokenService tokens;
    private final BatchEventService events;
    private final BatchProperties properties;

    public BatchEnrollmentService(BatchRunnerMapper runnerMapper, BatchCredentialMapper credentialMapper,
            BatchTokenService tokens, BatchEventService events, BatchProperties properties) {
        this.runnerMapper = runnerMapper;
        this.credentialMapper = credentialMapper;
        this.tokens = tokens;
        this.events = events;
        this.properties = properties;
    }

    @Transactional
    public BatchEnrollmentResult issue(String runnerId, String actorId) {
        BatchRunner runner = runnerMapper.lockById(runnerId)
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
        if (runner.getStatus() != RunnerStatus.REGISTERING && runner.getStatus() != RunnerStatus.REVOKED) {
            throw BatchException.conflict(BatchErrors.STATE_CONFLICT); // 재등록은 먼저 폐기해야 한다
        }
        // S3: REVOKED runner의 재등록 토큰 발급에 미해결 실행 검사를 연결한다.
        credentialMapper.revokeUnusedEnrollments(runnerId);

        String credentialId = BatchIds.next(BatchIds.CREDENTIAL);
        String secret = tokens.newSecret();
        BatchRunnerCredential row = new BatchRunnerCredential();
        row.setId(credentialId);
        row.setRunnerId(runnerId);
        row.setType(CredentialType.ENROLLMENT);
        row.setSecretHash(tokens.hash(secret));
        row.setCreatorId(actorId);
        credentialMapper.insert(row, properties.getEnrollmentTtl().toSeconds());

        if (runner.getStatus() == RunnerStatus.REVOKED) {
            runnerMapper.changeStatus(runnerId, RunnerStatus.REGISTERING, actorId);
        }
        events.record(EventTargetType.RUNNER, runnerId, EventType.ENROLLMENT_ISSUED, runner.getStatus().getCode(),
                RunnerStatus.REGISTERING.getCode(), ActorType.USER, actorId, Map.of("credentialId", credentialId));

        Instant expiresAt = credentialMapper.findForAuth(credentialId).orElseThrow().getExpiresAt();
        return new BatchEnrollmentResult(credentialId, tokens.join(credentialId, secret), BatchTime.format(expiresAt));
    }

    @Transactional
    public BatchRunner revoke(String runnerId, String reason, String actorId) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        BatchRunner runner = runnerMapper.lockById(runnerId)
                .orElseThrow(() -> BatchException.notFound(BatchErrors.RUNNER_NOT_FOUND));
        if (runner.getStatus() != RunnerStatus.REVOKED) {
            credentialMapper.revokeAll(runnerId);
            runnerMapper.changeStatus(runnerId, RunnerStatus.REVOKED, actorId);
            // 폐기는 실행 프로세스의 종료를 뜻하지 않는다(S3 이후 미해결 Attempt는 별도 복구 대상).
            events.record(EventTargetType.RUNNER, runnerId, EventType.REVOKED, runner.getStatus().getCode(),
                    RunnerStatus.REVOKED.getCode(), ActorType.USER, actorId, Map.of("reason", reason));
        }
        return runnerMapper.findById(runnerId, properties.getOnlineSeconds()).orElseThrow();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchEnrollmentServiceTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add kkdugi-admin/src/main/java/kkdugi/app/batch kkdugi-admin/src/test/java/kkdugi/app/batch
git commit -m "feat(batch): add enrollment token issuing and revocation" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: 멱등 명령 기반 (`api_request`)

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/models/{BatchApiRequest,BatchIdempotencyKey,BatchIdempotentResponse}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/mapper/BatchApiRequestMapper.java`
- Create: `kkdugi-admin/src/main/resources/mapper/postgres/app/batch/BatchApiRequestMapper.xml`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/service/{BatchIdempotencyService,BatchRequestKeys}.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/batch/service/{BatchIdempotencyServiceTest,BatchRequestKeysTest}.java`

**Interfaces:**
- Consumes: `IdempotencySubjectType`, `BatchIds.API_REQUEST`, `BatchProperties`, `BatchTokenService.sha256Hex`, `BatchException`/`BatchErrors`.
- Produces:
  - `BatchIdempotencyKey`(`@Getter @Setter`): `subjectType(IdempotencySubjectType), subjectId, operationHash, requestKey, requestHash, createdAt(Instant)`.
  - `BatchIdempotentResponse`(`@Getter @AllArgsConstructor`): `int status, String body(JSON 문자열, 본문 없으면 null), String location`.
  - `BatchIdempotentResponse BatchIdempotencyService.execute(BatchIdempotencyKey key, Supplier<BatchIdempotentResponse> command)` — `@Transactional`. 순서: advisory lock → 기존 기록(만료 전) 조회(같은 hash면 재현, 다르면 409 `IDEMPOTENCY_CONFLICT`) → 없으면 생성 시각 창 검사(410 `REQUEST_EXPIRED`) → 명령 실행 → 만료 기록 정리 → 응답 기록 저장. 명령이 예외를 던지면 기록도 롤백된다.
  - `BatchRequestKeys.BatchIdempotencyKey forUser(String userId, String method, String path, String keyHeader, String createdAtHeader, Object body, String ifMatch)` — 헤더 검증(`Idempotency-Key`는 `[\x21-\x7e]{1,200}`, `X-Request-Created-At`은 UTC `Z` 소수 6자리 이하; 위반은 400 `REQUEST_INVALID`), `operationHash = sha256(method + " " + path)`, `requestHash = sha256(정규화 본문 + "\n" + ifMatch + "\n" + createdAt 원문)`.

- [ ] **Step 1: 요청 키 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchRequestKeysTest.java`:

```java
package kkdugi.app.batch.service;

import static kkdugi.support.BatchTestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.IdempotencySubjectType;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.models.BatchIdempotencyKey;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchRequestKeysTest {

    private static final String CREATED_AT = "2026-09-20T02:00:00.123456Z";

    @Autowired
    private BatchRequestKeys keys;

    private BatchIdempotencyKey key(Object body, String ifMatch) {
        return keys.forUser("U1", "PUT", "/api/v1.0/admin/batch/runners/BR1", "key-1", CREATED_AT, body, ifMatch);
    }

    @Test
    void buildsTheKeyFromTheRequest() {
        BatchIdempotencyKey key = key(Map.of("a", 1), "\"3\"");

        assertThat(key.getSubjectType()).isEqualTo(IdempotencySubjectType.USER);
        assertThat(key.getSubjectId()).isEqualTo("U1");
        assertThat(key.getRequestKey()).isEqualTo("key-1");
        assertThat(key.getCreatedAt()).isEqualTo(Instant.parse(CREATED_AT));
        assertThat(key.getOperationHash()).isEqualTo(BatchTokenService.sha256Hex("PUT /api/v1.0/admin/batch/runners/BR1"));
        assertThat(key.getRequestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void requestHashIgnoresJsonKeyOrderButNotValues() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "n");
        first.put("nested", new LinkedHashMap<>(Map.of("x", 1, "y", 2)));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("nested", new LinkedHashMap<>(Map.of("y", 2, "x", 1)));
        reordered.put("name", "n");
        Map<String, Object> different = new LinkedHashMap<>(first);
        different.put("name", "other");

        assertThat(key(first, null).getRequestHash()).isEqualTo(key(reordered, null).getRequestHash());
        assertThat(key(first, null).getRequestHash()).isNotEqualTo(key(different, null).getRequestHash());
    }

    @Test
    void ifMatchAndCreatedAtParticipateInTheHash() {
        assertThat(key(Map.of("a", 1), "\"1\"").getRequestHash()).isNotEqualTo(key(Map.of("a", 1), "\"2\"").getRequestHash());
        assertThat(key(Map.of("a", 1), null).getRequestHash()).isNotEqualTo(key(Map.of("a", 1), "\"1\"").getRequestHash());
        assertThat(keys.forUser("U1", "PUT", "/p", "k", "2026-09-20T02:00:01Z", null, null).getRequestHash())
                .isNotEqualTo(keys.forUser("U1", "PUT", "/p", "k", "2026-09-20T02:00:02Z", null, null).getRequestHash());
    }

    @Test
    void nullBodiesAreAllowedForBodylessCommands() {
        assertThat(keys.forUser("U1", "DELETE", "/p", "k", CREATED_AT, null, null).getRequestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsInvalidHeaders() {
        assertError(() -> keys.forUser("U1", "POST", "/p", null, CREATED_AT, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "", CREATED_AT, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "has space", CREATED_AT, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k".repeat(201), CREATED_AT, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k", null, null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k", "2026-09-20T02:00:00+09:00", null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k", "2026-09-20T02:00:00.1234567Z", null, null), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> keys.forUser("U1", "POST", "/p", "k", "2026-13-40T02:00:00Z", null, null), 400, BatchErrors.REQUEST_INVALID);
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchRequestKeysTest`
Expected: FAIL — 컴파일 오류.

- [ ] **Step 2: 모델과 `BatchRequestKeys` 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchIdempotencyKey.java`:

```java
package kkdugi.app.batch.models;

import java.time.Instant;

import kkdugi.app.batch.enums.IdempotencySubjectType;
import lombok.Getter;
import lombok.Setter;

/** 멱등 범위 {@code (subjectType, subjectId, operationHash, requestKey)}와 요청 동일성 hash·key 생성 시각. */
@Getter
@Setter
public class BatchIdempotencyKey {

    private IdempotencySubjectType subjectType;
    private String subjectId;
    private String operationHash;
    private String requestKey;
    private String requestHash;
    private Instant createdAt;
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchIdempotentResponse.java`:

```java
package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 명령의 성공 응답(2xx). 재전송 시 그대로 재현된다. {@code body}는 JSON 문자열이고 본문이 없으면 null. */
@Getter
@AllArgsConstructor
public class BatchIdempotentResponse {

    private final int status;
    private final String body;
    private final String location;
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchApiRequest.java`:

```java
package kkdugi.app.batch.models;

import java.time.Instant;

import kkdugi.app.batch.enums.IdempotencySubjectType;
import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_batch_api_request} 한 행. {@code responseText}는 jsonb를 text로 읽은 값이다. */
@Getter
@Setter
public class BatchApiRequest {

    private String id;
    private IdempotencySubjectType subjectType;
    private String subjectId;
    private String operationHash;
    private String requestKey;
    private String requestHash;
    private Instant requestedAt;
    private int httpStatus;
    private String responseText;
    private String location;
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchRequestKeys.java`:

```java
package kkdugi.app.batch.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import kkdugi.app.batch.enums.IdempotencySubjectType;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchIdempotencyKey;
import tools.jackson.databind.ObjectMapper;

/** 요청 헤더·본문에서 멱등 key를 만든다. 본문은 키 순서와 무관하게 정규화해서 hash한다. */
@Component
public class BatchRequestKeys {

    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7e]{1,200}");
    private static final Pattern CREATED_AT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");

    private final ObjectMapper objectMapper;

    public BatchRequestKeys(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BatchIdempotencyKey forUser(String userId, String method, String path, String keyHeader,
            String createdAtHeader, Object body, String ifMatch) {
        if (keyHeader == null || !KEY.matcher(keyHeader).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        if (createdAtHeader == null || !CREATED_AT.matcher(createdAtHeader).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(createdAtHeader);
        } catch (DateTimeParseException e) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        BatchIdempotencyKey key = new BatchIdempotencyKey();
        key.setSubjectType(IdempotencySubjectType.USER);
        key.setSubjectId(userId);
        key.setOperationHash(BatchTokenService.sha256Hex(method + " " + path));
        key.setRequestKey(keyHeader);
        key.setRequestHash(BatchTokenService.sha256Hex(
                canonical(body) + "\n" + (ifMatch == null ? "" : ifMatch) + "\n" + createdAtHeader));
        key.setCreatedAt(createdAt);
        return key;
    }

    String canonical(Object body) {
        StringBuilder out = new StringBuilder();
        append(out, body == null ? null : objectMapper.convertValue(body, Object.class));
        return out.toString();
    }

    private void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(objectMapper.writeValueAsString(entry.getKey())).append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            out.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (value instanceof CharSequence text) {
            out.append(objectMapper.writeValueAsString(text.toString()));
        } else {
            out.append(value);
        }
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchRequestKeysTest`
Expected: PASS (5 tests).

- [ ] **Step 3: 멱등 서비스 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchIdempotencyServiceTest.java`:

```java
package kkdugi.app.batch.service;

import static kkdugi.support.BatchTestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.enums.IdempotencySubjectType;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchIdempotencyKey;
import kkdugi.app.batch.models.BatchIdempotentResponse;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchIdempotencyServiceTest {

    @Autowired
    private BatchIdempotencyService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    private static BatchIdempotencyKey key(String requestKey, String requestHash, Instant createdAt) {
        BatchIdempotencyKey key = new BatchIdempotencyKey();
        key.setSubjectType(IdempotencySubjectType.USER);
        key.setSubjectId(BatchTestData.USER);
        key.setOperationHash(BatchTokenService.sha256Hex("POST /test"));
        key.setRequestKey(requestKey);
        key.setRequestHash(requestHash);
        key.setCreatedAt(createdAt);
        return key;
    }

    private static BatchIdempotentResponse created(String body) {
        return new BatchIdempotentResponse(201, body, "/test/1");
    }

    private int recordCount() {
        return jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_api_request WHERE subject_id = ?",
                Integer.class, BatchTestData.USER);
    }

    @Test
    void firstCallExecutesAndReplayReturnsTheStoredResponseWithoutReexecuting() {
        AtomicInteger calls = new AtomicInteger();
        BatchIdempotencyKey key = key("k-replay", "h1", Instant.now());

        BatchIdempotentResponse first = service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{\"n\":1}");
        });
        BatchIdempotentResponse replay = service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{\"n\":2}");
        });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getLocation()).isEqualTo("/test/1");
        assertThat(replay.getBody()).isEqualToIgnoringWhitespace("{\"n\":1}");
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void bodylessResponsesAreReplayedToo() {
        BatchIdempotencyKey key = key("k-204", "h1", Instant.now());

        service.execute(key, () -> new BatchIdempotentResponse(204, null, null));
        BatchIdempotentResponse replay = service.execute(key, () -> created("{}"));

        assertThat(replay.getStatus()).isEqualTo(204);
        assertThat(replay.getBody()).isNull();
        assertThat(replay.getLocation()).isNull();
    }

    @Test
    void sameKeyWithADifferentRequestConflicts() {
        service.execute(key("k-conflict", "h1", Instant.now()), () -> created("{}"));

        assertError(() -> service.execute(key("k-conflict", "h2", Instant.now()), () -> created("{}")),
                409, BatchErrors.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void aNewKeyWithAStaleCreationTimeIsRejectedAsGone() {
        Instant stale = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant future = Instant.now().plus(10, ChronoUnit.MINUTES);

        assertError(() -> service.execute(key("k-stale", "h1", stale), () -> created("{}")),
                410, BatchErrors.REQUEST_EXPIRED);
        assertError(() -> service.execute(key("k-future", "h1", future), () -> created("{}")),
                410, BatchErrors.REQUEST_EXPIRED);
        assertThat(recordCount()).isZero();
    }

    @Test
    void aFailedCommandLeavesNoRecordSoTheRetryExecutes() {
        BatchIdempotencyKey key = key("k-fail", "h1", Instant.now());
        AtomicInteger calls = new AtomicInteger();

        assertError(() -> service.execute(key, () -> {
            calls.incrementAndGet();
            throw BatchException.conflict(BatchErrors.STATE_CONFLICT);
        }), 409, BatchErrors.STATE_CONFLICT);
        assertThat(recordCount()).isZero();

        BatchIdempotentResponse retry = service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{\"ok\":true}");
        });

        assertThat(calls.get()).isEqualTo(2);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyExecuteOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BatchIdempotencyKey key = key("k-race", "h1", Instant.now());
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BatchIdempotentResponse>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.execute(key, () -> {
                    calls.incrementAndGet();
                    sleep(150); // 다른 요청이 같은 key로 진입할 시간을 준다
                    return created("{\"n\":1}");
                });
            }));
        }
        start.countDown();
        for (Future<BatchIdempotentResponse> result : results) {
            assertThat(result.get().getStatus()).isEqualTo(201);
        }
        pool.shutdown();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void anExpiredRecordIsReplacedByANewExecution() {
        AtomicInteger calls = new AtomicInteger();
        BatchIdempotencyKey key = key("k-expired", "h1", Instant.now());
        service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{}");
        });
        jdbc.update("UPDATE kkdugi_batch_api_request SET expires_dtm = now() - interval '1 second' WHERE subject_id = ?",
                BatchTestData.USER);

        service.execute(key, () -> {
            calls.incrementAndGet();
            return created("{}");
        });

        assertThat(calls.get()).isEqualTo(2);
        assertThat(recordCount()).isEqualTo(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchIdempotencyServiceTest`
Expected: FAIL — 컴파일 오류(`BatchIdempotencyService` 없음).

- [ ] **Step 4: mapper와 서비스 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/mapper/BatchApiRequestMapper.java`:

```java
package kkdugi.app.batch.mapper;

import java.time.Instant;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.batch.enums.IdempotencySubjectType;
import kkdugi.app.batch.models.BatchApiRequest;

@Mapper
public interface BatchApiRequestMapper {

    /** 트랜잭션 범위 advisory lock. 같은 lockKey의 요청을 직렬화한다. 항상 1을 돌려준다. */
    int lock(@Param("lockKey") String lockKey);

    /** 만료되지 않은 기록만 찾는다. */
    Optional<BatchApiRequest> findActive(@Param("subjectType") IdempotencySubjectType subjectType,
            @Param("subjectId") String subjectId, @Param("operationHash") String operationHash,
            @Param("requestKey") String requestKey);

    /** 같은 key의 만료된 기록을 지운다(재삽입 시 UQ 충돌 방지). */
    int deleteExpired(@Param("subjectType") IdempotencySubjectType subjectType, @Param("subjectId") String subjectId,
            @Param("operationHash") String operationHash, @Param("requestKey") String requestKey);

    int insert(@Param("row") BatchApiRequest row, @Param("retentionSeconds") long retentionSeconds);

    /** key 생성 시각이 DB 시각과 skewSeconds 이내인지. */
    boolean isFresh(@Param("createdAt") Instant createdAt, @Param("skewSeconds") long skewSeconds);
}
```

`kkdugi-admin/src/main/resources/mapper/postgres/app/batch/BatchApiRequestMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.batch.mapper.BatchApiRequestMapper">

  <resultMap id="apiRequestResultMap" type="kkdugi.app.batch.models.BatchApiRequest">
    <id property="id" column="request_id"/>
    <result property="subjectType" column="subject_type"/>
    <result property="subjectId" column="subject_id"/>
    <result property="operationHash" column="operation_hash"/>
    <result property="requestKey" column="request_key"/>
    <result property="requestHash" column="request_hash"/>
    <result property="requestedAt" column="request_dtm"/>
    <result property="httpStatus" column="http_status"/>
    <result property="responseText" column="response_text"/>
    <result property="location" column="location_text"/>
  </resultMap>

  <select id="lock" resultType="int">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchApiRequestMapper.lock */
SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))) AS locked
]]>
  </select>

  <select id="findActive" resultMap="apiRequestResultMap">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchApiRequestMapper.findActive */
SELECT request_id, subject_type, subject_id, operation_hash, request_key, request_hash, request_dtm, http_status,
       response_data::text AS response_text, location_text
FROM kkdugi_batch_api_request
WHERE subject_type = #{subjectType} AND subject_id = #{subjectId}
  AND operation_hash = #{operationHash} AND request_key = #{requestKey}
  AND expires_dtm > clock_timestamp()
]]>
  </select>

  <delete id="deleteExpired">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchApiRequestMapper.deleteExpired */
DELETE FROM kkdugi_batch_api_request
WHERE subject_type = #{subjectType} AND subject_id = #{subjectId}
  AND operation_hash = #{operationHash} AND request_key = #{requestKey}
  AND expires_dtm <= clock_timestamp()
]]>
  </delete>

  <insert id="insert">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchApiRequestMapper.insert */
INSERT INTO kkdugi_batch_api_request
    (request_id, subject_type, subject_id, operation_hash, request_key, request_hash, request_dtm, http_status,
     response_data, location_text, expires_dtm, reg_dtm, reg_id)
VALUES
    (#{row.id}, #{row.subjectType}, #{row.subjectId}, #{row.operationHash}, #{row.requestKey}, #{row.requestHash},
     #{row.requestedAt}, #{row.httpStatus}, CAST(#{row.responseText,jdbcType=VARCHAR} AS jsonb),
     #{row.location,jdbcType=VARCHAR}, clock_timestamp() + make_interval(secs => #{retentionSeconds}::double precision),
     (now() AT TIME ZONE 'UTC'), #{row.subjectId})
]]>
  </insert>

  <select id="isFresh" resultType="boolean">
<![CDATA[
/* QueryID=kkdugi.app.batch.mapper.BatchApiRequestMapper.isFresh */
SELECT abs(extract(epoch FROM (clock_timestamp() - CAST(#{createdAt} AS timestamptz)))) <= #{skewSeconds}
]]>
  </select>

</mapper>
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchIdempotencyService.java`:

```java
package kkdugi.app.batch.service;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.batch.config.BatchProperties;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.mapper.BatchApiRequestMapper;
import kkdugi.app.batch.models.BatchApiRequest;
import kkdugi.app.batch.models.BatchIdempotencyKey;
import kkdugi.app.batch.models.BatchIdempotentResponse;

/**
 * 관리자 명령의 멱등 실행. 명령·event·응답 기록이 한 트랜잭션이라 명령이 실패(롤백)하면 기록도 남지 않는다.
 * 호출 전에 인증·현재 권한 검사가 끝나 있어야 한다(재현 응답도 현재 권한으로 보호된다).
 */
@Service
public class BatchIdempotencyService {

    private final BatchApiRequestMapper mapper;
    private final BatchProperties properties;

    public BatchIdempotencyService(BatchApiRequestMapper mapper, BatchProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional
    public BatchIdempotentResponse execute(BatchIdempotencyKey key, Supplier<BatchIdempotentResponse> command) {
        mapper.lock(String.join("|", key.getSubjectType().getCode(), key.getSubjectId(), key.getOperationHash(),
                key.getRequestKey()));

        Optional<BatchApiRequest> existing = mapper.findActive(key.getSubjectType(), key.getSubjectId(),
                key.getOperationHash(), key.getRequestKey());
        if (existing.isPresent()) {
            BatchApiRequest saved = existing.get();
            if (!saved.getRequestHash().equals(key.getRequestHash())) {
                throw BatchException.conflict(BatchErrors.IDEMPOTENCY_CONFLICT);
            }
            return new BatchIdempotentResponse(saved.getHttpStatus(), saved.getResponseText(), saved.getLocation());
        }
        // 기록이 정리된 오래된 key를 새 명령으로 받아들이지 않도록 신규 key의 생성 시각 창을 검사한다.
        if (!mapper.isFresh(key.getCreatedAt(), properties.getRequestSkew().toSeconds())) {
            throw BatchException.gone(BatchErrors.REQUEST_EXPIRED);
        }

        BatchIdempotentResponse response = command.get();

        mapper.deleteExpired(key.getSubjectType(), key.getSubjectId(), key.getOperationHash(), key.getRequestKey());
        BatchApiRequest row = new BatchApiRequest();
        row.setId(BatchIds.next(BatchIds.API_REQUEST));
        row.setSubjectType(key.getSubjectType());
        row.setSubjectId(key.getSubjectId());
        row.setOperationHash(key.getOperationHash());
        row.setRequestKey(key.getRequestKey());
        row.setRequestHash(key.getRequestHash());
        row.setRequestedAt(key.getCreatedAt());
        row.setHttpStatus(response.getStatus());
        row.setResponseText(response.getBody());
        row.setLocation(response.getLocation());
        mapper.insert(row, properties.getIdempotencyRetention().toSeconds());
        return response;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest="BatchIdempotencyServiceTest,BatchRequestKeysTest"`
Expected: PASS (7 + 5 tests).
막히는 지점: `isFresh`의 `CAST(#{createdAt} AS timestamptz)`가 타입 오류를 내면 `#{createdAt}` 대신 `CAST(#{createdAt,jdbcType=TIMESTAMP} AS timestamptz)`로 명시한다.

- [ ] **Step 6: Commit**

```bash
git add kkdugi-admin/src/main/java/kkdugi/app/batch kkdugi-admin/src/main/resources/mapper/postgres/app/batch kkdugi-admin/src/test/java/kkdugi/app/batch
git commit -m "feat(batch): add idempotent command execution" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: Runner 등록·세션·heartbeat 서비스

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/models/{BatchRegistrationRequest,BatchRegistrationResponse,BatchSessionRequest,BatchSessionResponse,BatchLimits,BatchHeartbeatRequest,BatchHeartbeatItem,BatchHeartbeatResponse,BatchHeartbeatAction}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchAgentService.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/batch/models/BatchAgentJsonTest.java`, `kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchAgentServiceTest.java`

**Interfaces:**
- Consumes: `BatchRunnerMapper`, `BatchCredentialMapper`, `BatchTokenService`, `BatchEventService`, `BatchEnrollmentService.issue`(테스트 준비), `BatchProperties`, `BatchTime`, `BatchIds`.
- Produces `BatchAgentService`(모두 `@Transactional`):
  - `BatchRegistrationResponse register(String runnerId, String credentialId, BatchRegistrationRequest request)` — `credentialId`는 방금 인증된 ENROLLMENT 자격증명.
  - `BatchSessionResponse openSession(String runnerId, String credentialId, BatchSessionRequest request)` — `credentialId`는 ACCESS 자격증명.
  - `BatchHeartbeatResponse heartbeat(String runnerId, String sessionHeader, BatchHeartbeatRequest request)`.
  - 응답 클래스 getter: `BatchRegistrationResponse(runnerId, credentialId, accessToken, tokenExpiresAt, session)`, `BatchSessionResponse(runnerId, session, serverTime, heartbeatSeconds, pollSeconds, leaseSeconds, capacity, limits:BatchLimits)`, `BatchLimits(jsonBytes, inputBytes, resultBytes, logChunkBytes)`, `BatchHeartbeatResponse(serverTime, acceptingAssignments, assignments:List<BatchHeartbeatAction>)`, `BatchHeartbeatAction(id, action, leaseUntil, stopReason, graceSeconds)`. 요청 클래스는 모두 `BatchStrictRequest` 상속 + getter/setter.

- [ ] **Step 1: 요청·응답 클래스 작성**

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/` 아래에 다음 파일을 만든다.

`BatchRegistrationRequest.java`:

```java
package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

@Getter
@Setter
public class BatchRegistrationRequest extends BatchStrictRequest {

    @JsonDeserialize(using = BatchStrictString.class)
    private String runnerCode;
    @JsonDeserialize(using = BatchStrictString.class)
    private String agentVersion;
    @JsonDeserialize(using = BatchStrictString.class)
    private String hostname;
    @JsonDeserialize(using = BatchStrictString.class)
    private String os;
    @JsonDeserialize(using = BatchStrictString.class)
    private String architecture;
}
```

`BatchRegistrationResponse.java`:

```java
package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 등록 응답. {@code accessToken}은 이 응답에서 한 번만 나간다. */
@Getter
@AllArgsConstructor
public class BatchRegistrationResponse {

    private final String runnerId;
    private final String credentialId;
    private final String accessToken;
    private final String tokenExpiresAt;
    private final String session;
}
```

`BatchSessionRequest.java`:

```java
package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

@Getter
@Setter
public class BatchSessionRequest extends BatchStrictRequest {

    @JsonDeserialize(using = BatchStrictString.class)
    private String bootId;
    /** bigint 세대는 JSON 십진 문자열이다. 숫자 토큰(예: 1)은 거절한다. */
    @JsonDeserialize(using = BatchStrictString.class)
    private String expectedSession;
    @JsonDeserialize(using = BatchStrictString.class)
    private String agentVersion;
}
```

`BatchLimits.java`:

```java
package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BatchLimits {

    private final int jsonBytes;
    private final int inputBytes;
    private final int resultBytes;
    private final int logChunkBytes;
}
```

`BatchSessionResponse.java`:

```java
package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BatchSessionResponse {

    private final String runnerId;
    private final String session;
    private final String serverTime;
    private final int heartbeatSeconds;
    private final int pollSeconds;
    private final int leaseSeconds;
    private final int capacity;
    private final BatchLimits limits;
}
```

`BatchHeartbeatItem.java`:

```java
package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

@Getter
@Setter
public class BatchHeartbeatItem extends BatchStrictRequest {

    @JsonDeserialize(using = BatchStrictString.class)
    private String id;
    @JsonDeserialize(using = BatchStrictString.class)
    private String phase;
}
```

`BatchHeartbeatRequest.java`:

```java
package kkdugi.app.batch.models;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

@Getter
@Setter
public class BatchHeartbeatRequest extends BatchStrictRequest {

    @JsonDeserialize(using = BatchStrictString.class)
    private String observedAt;
    @JsonDeserialize(using = BatchStrictString.class)
    private String mode;
    @JsonDeserialize(using = BatchStrictInteger.class)
    private Integer freeSlots;
    private List<BatchHeartbeatItem> assignments;
}
```

`BatchHeartbeatAction.java`:

```java
package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 배정 항목별 지시. {@code null} 필드도 JSON에 그대로 나간다(계약: leaseUntil=null이면 갱신하지 않음). */
@Getter
@AllArgsConstructor
public class BatchHeartbeatAction {

    private final String id;
    private final String action;
    private final String leaseUntil;
    private final String stopReason;
    private final Integer graceSeconds;
}
```

`BatchHeartbeatResponse.java`:

```java
package kkdugi.app.batch.models;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BatchHeartbeatResponse {

    private final String serverTime;
    private final boolean acceptingAssignments;
    private final List<BatchHeartbeatAction> assignments;
}
```

`kkdugi-admin/src/test/java/kkdugi/app/batch/models/BatchAgentJsonTest.java` (Runner API·폐기 요청 DTO의 JSON 타입 엄격성. 구현과 함께 작성하므로 첫 실행부터 통과해야 한다):

```java
package kkdugi.app.batch.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import kkdugi.KkdugiAdminApplication;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchAgentJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    private void rejects(String json, Class<?> type) {
        assertThatThrownBy(() -> objectMapper.readValue(json, type)).as(json).isInstanceOf(JacksonException.class);
    }

    @Test
    void sessionRequestRequiresStringTokens() {
        rejects("{\"expectedSession\":1}", BatchSessionRequest.class);
        rejects("{\"bootId\":123}", BatchSessionRequest.class);
        rejects("{\"agentVersion\":true}", BatchSessionRequest.class);
        BatchSessionRequest ok = objectMapper.readValue(
                "{\"bootId\":\"b\",\"expectedSession\":\"0\",\"agentVersion\":\"v\"}", BatchSessionRequest.class);
        assertThat(ok.getExpectedSession()).isEqualTo("0");
    }

    @Test
    void heartbeatRequiresAnIntegerFreeSlotsAndStringFields() {
        for (String bad : List.of("\"1\"", "1.5", "true")) {
            rejects("{\"freeSlots\":" + bad + "}", BatchHeartbeatRequest.class);
        }
        rejects("{\"mode\":1}", BatchHeartbeatRequest.class);
        rejects("{\"observedAt\":20260920}", BatchHeartbeatRequest.class);
        rejects("{\"assignments\":[{\"id\":5}]}", BatchHeartbeatRequest.class);
        rejects("{\"assignments\":[{\"phase\":true}]}", BatchHeartbeatRequest.class);
        assertThat(objectMapper.readValue("{\"freeSlots\":2}", BatchHeartbeatRequest.class).getFreeSlots()).isEqualTo(2);
    }

    @Test
    void registrationAndRevokeRequestsRequireStrings() {
        for (String field : List.of("runnerCode", "agentVersion", "hostname", "os", "architecture")) {
            rejects("{\"" + field + "\":5}", BatchRegistrationRequest.class);
        }
        rejects("{\"reason\":5}", BatchRevokeRequest.class);
    }

    @Test
    void unknownFieldsFail() {
        rejects("{\"bootId\":\"b\",\"extra\":1}", BatchSessionRequest.class);
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchAgentJsonTest`
Expected: PASS (4 tests).

- [ ] **Step 2: 서비스 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchAgentServiceTest.java`:

```java
package kkdugi.app.batch.service;

import static kkdugi.support.BatchTestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.models.BatchHeartbeatItem;
import kkdugi.app.batch.models.BatchHeartbeatRequest;
import kkdugi.app.batch.models.BatchHeartbeatResponse;
import kkdugi.app.batch.models.BatchRegistrationRequest;
import kkdugi.app.batch.models.BatchRegistrationResponse;
import kkdugi.app.batch.models.BatchSessionRequest;
import kkdugi.app.batch.models.BatchSessionResponse;
import kkdugi.support.BatchTestData;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchAgentServiceTest {

    private static final String ACTOR = BatchTestData.USER;

    @Autowired
    private BatchAgentService agent;

    @Autowired
    private BatchEnrollmentService enrollment;

    @Autowired
    private BatchTokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void wipe() {
        BatchTestData.wipe(jdbc);
    }

    /** REGISTERING runner와 미사용 등록 토큰을 준비하고 {runnerId, enrollmentCredentialId, token}을 돌려준다. */
    private String[] registrable(String code) {
        String runnerId = BatchTestData.insertRunner(jdbc, code, "REGISTERING");
        String token = enrollment.issue(runnerId, ACTOR).getEnrollmentToken();
        return new String[] { runnerId, tokens.parse(token).getCredentialId(), token };
    }

    private static BatchRegistrationRequest registration(String code) {
        BatchRegistrationRequest request = new BatchRegistrationRequest();
        request.setRunnerCode(code);
        request.setAgentVersion("0.1.0");
        request.setHostname("host-1");
        request.setOs("LINUX");
        request.setArchitecture("AMD64");
        return request;
    }

    /** 등록까지 마친 runner의 {runnerId, accessCredentialId}. */
    private String[] registered(String code) {
        String[] ids = registrable(code);
        BatchRegistrationResponse response = agent.register(ids[0], ids[1], registration(code));
        return new String[] { ids[0], response.getCredentialId() };
    }

    private static BatchSessionRequest session(String bootId, String expected) {
        BatchSessionRequest request = new BatchSessionRequest();
        request.setBootId(bootId);
        request.setExpectedSession(expected);
        request.setAgentVersion("0.1.0");
        return request;
    }

    private static BatchHeartbeatRequest heartbeat(int freeSlots, String... assignmentIds) {
        BatchHeartbeatRequest request = new BatchHeartbeatRequest();
        request.setObservedAt("2026-09-20T02:00:00Z");
        request.setMode("ACCEPTING");
        request.setFreeSlots(freeSlots);
        List<BatchHeartbeatItem> items = new ArrayList<>();
        for (String id : assignmentIds) {
            BatchHeartbeatItem item = new BatchHeartbeatItem();
            item.setId(id);
            item.setPhase("RUNNING");
            items.add(item);
        }
        request.setAssignments(items);
        return request;
    }

    private Map<String, Object> runnerRow(String runnerId) {
        return jdbc.queryForMap("SELECT runner_stat, host_nm, os_cd, agent_ver, session_ver, config_ver, boot_ref, "
                + "last_seen_dtm IS NOT NULL AS seen FROM kkdugi_batch_runner WHERE runner_id = ?", runnerId);
    }

    // ---- 등록 ----------------------------------------------------------

    @Test
    void registrationActivatesTheRunnerAndIssuesAnAccessToken() {
        String[] ids = registrable("tb-agt-1");

        BatchRegistrationResponse response = agent.register(ids[0], ids[1], registration("tb-agt-1"));

        assertThat(response.getRunnerId()).isEqualTo(ids[0]);
        assertThat(response.getSession()).isEqualTo("0");
        assertThat(Instant.parse(response.getTokenExpiresAt())).isAfter(Instant.now().plusSeconds(29L * 24 * 3600));
        BatchTokenService.ParsedToken access = tokens.parse(response.getAccessToken());
        assertThat(access).isNotNull();
        assertThat(access.getCredentialId()).isEqualTo(response.getCredentialId());
        assertThat(jdbc.queryForObject("SELECT secret_hash FROM kkdugi_batch_runner_credential WHERE credential_id = ?",
                String.class, response.getCredentialId())).isEqualTo(tokens.hash(access.getSecret()));
        Map<String, Object> runner = runnerRow(ids[0]);
        assertThat(runner.get("runner_stat")).isEqualTo("ACTIVE");
        assertThat(runner.get("host_nm")).isEqualTo("host-1");
        assertThat(runner.get("os_cd")).isEqualTo("LINUX");
        assertThat(runner.get("agent_ver")).isEqualTo("0.1.0");
        assertThat(((Number) runner.get("config_ver")).longValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT consumed_dtm IS NOT NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, ids[1])).isTrue();
    }

    @Test
    void registrationEventCarriesCredentialIdsAndArchitectureButNoToken() {
        String[] ids = registrable("tb-agt-2");

        BatchRegistrationResponse response = agent.register(ids[0], ids[1], registration("tb-agt-2"));

        Map<String, Object> event = jdbc.queryForMap("SELECT actor_type, actor_id, from_stat, to_stat, "
                + "detail_data::text AS detail FROM kkdugi_batch_event WHERE target_id = ? AND event_type = 'REGISTERED'",
                ids[0]);
        assertThat(event.get("actor_type")).isEqualTo("RUNNER");
        assertThat(event.get("actor_id")).isEqualTo(ids[0]);
        assertThat(event.get("from_stat")).isEqualTo("REGISTERING");
        assertThat(event.get("to_stat")).isEqualTo("ACTIVE");
        String detail = event.get("detail").toString();
        assertThat(detail).contains(response.getCredentialId()).contains("AMD64");
        assertThat(detail).doesNotContain(tokens.parse(response.getAccessToken()).getSecret());
    }

    @Test
    void aMismatchedRunnerCodeIsForbiddenAndDoesNotBurnTheToken() {
        String[] ids = registrable("tb-agt-3");

        assertError(() -> agent.register(ids[0], ids[1], registration("tb-other")), 403, BatchErrors.RUNNER_FORBIDDEN);

        assertThat(jdbc.queryForObject("SELECT consumed_dtm IS NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, ids[1])).isTrue();
        assertThat(runnerRow(ids[0]).get("runner_stat")).isEqualTo("REGISTERING");
    }

    @Test
    void anUnsupportedPlatformIsRejectedWithoutBurningTheToken() {
        String[] ids = registrable("tb-agt-4");
        BatchRegistrationRequest request = registration("tb-agt-4");
        request.setOs("SOLARIS");

        assertError(() -> agent.register(ids[0], ids[1], request), 409, BatchErrors.PLATFORM_UNSUPPORTED);

        assertThat(jdbc.queryForObject("SELECT consumed_dtm IS NULL FROM kkdugi_batch_runner_credential "
                + "WHERE credential_id = ?", Boolean.class, ids[1])).isTrue();
    }

    @Test
    void registrationValidatesRequiredFields() {
        String[] ids = registrable("tb-agt-5");
        BatchRegistrationRequest missingHost = registration("tb-agt-5");
        missingHost.setHostname(" ");
        BatchRegistrationRequest longVersion = registration("tb-agt-5");
        longVersion.setAgentVersion("v".repeat(51));

        assertError(() -> agent.register(ids[0], ids[1], missingHost), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.register(ids[0], ids[1], longVersion), 400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void theTokenCannotBeUsedTwiceOrOnANonRegisteringRunner() {
        String[] ids = registrable("tb-agt-6");
        agent.register(ids[0], ids[1], registration("tb-agt-6"));

        assertError(() -> agent.register(ids[0], ids[1], registration("tb-agt-6")), 409, BatchErrors.STATE_CONFLICT);
    }

    @Test
    void anExpiredEnrollmentTokenIsRejected() {
        String[] ids = registrable("tb-agt-7");
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET expires_dtm = now() - interval '1 second' "
                + "WHERE credential_id = ?", ids[1]);

        assertError(() -> agent.register(ids[0], ids[1], registration("tb-agt-7")), 401, BatchErrors.CREDENTIAL_INVALID);
        assertThat(runnerRow(ids[0]).get("runner_stat")).isEqualTo("REGISTERING");
    }

    // ---- 세션 ----------------------------------------------------------

    @Test
    void openingTheFirstSessionReturnsGenerationOneWithTheServerSettings() {
        String[] ids = registered("tb-agt-8");
        String bootId = UUID.randomUUID().toString();

        BatchSessionResponse response = agent.openSession(ids[0], ids[1], session(bootId, "0"));

        assertThat(response.getRunnerId()).isEqualTo(ids[0]);
        assertThat(response.getSession()).isEqualTo("1");
        assertThat(Instant.parse(response.getServerTime())).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(response.getHeartbeatSeconds()).isEqualTo(10);
        assertThat(response.getPollSeconds()).isEqualTo(3);
        assertThat(response.getLeaseSeconds()).isEqualTo(60);
        assertThat(response.getCapacity()).isEqualTo(1);
        assertThat(response.getLimits().getJsonBytes()).isEqualTo(1_048_576);
        assertThat(response.getLimits().getInputBytes()).isEqualTo(262_144);
        assertThat(response.getLimits().getResultBytes()).isEqualTo(262_144);
        assertThat(response.getLimits().getLogChunkBytes()).isEqualTo(32_768);
        Map<String, Object> runner = runnerRow(ids[0]);
        assertThat(runner.get("boot_ref")).isEqualTo(bootId);
        assertThat(((Number) runner.get("config_ver")).longValue()).isEqualTo(2); // 세션 개설은 버전을 올리지 않는다
    }

    @Test
    void resendingTheSameBootIdReturnsTheSameGenerationWithoutAnotherEvent() {
        String[] ids = registered("tb-agt-9");
        String bootId = UUID.randomUUID().toString();
        agent.openSession(ids[0], ids[1], session(bootId, "0"));

        BatchSessionResponse again = agent.openSession(ids[0], ids[1], session(bootId, "0"));

        assertThat(again.getSession()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                + "AND event_type = 'SESSION_OPENED'", Integer.class, ids[0])).isEqualTo(1);
    }

    @Test
    void aNewBootIdMustPresentTheCurrentGeneration() {
        String[] ids = registered("tb-agt-10");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));

        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0")),
                409, BatchErrors.SESSION_STALE);
        BatchSessionResponse next = agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "1"));

        assertThat(next.getSession()).isEqualTo("2");
    }

    @Test
    void sessionRequestValidation() {
        String[] ids = registered("tb-agt-11");

        assertError(() -> agent.openSession(ids[0], ids[1], session("not-a-uuid", "0")), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "-1")), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "01")), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "9999999999999999999")),
                400, BatchErrors.REQUEST_INVALID);
    }

    @Test
    void aRevokedCredentialCannotOpenASession() {
        String[] ids = registered("tb-agt-12");
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET revoked_dtm = now() WHERE credential_id = ?", ids[1]);

        assertError(() -> agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0")),
                401, BatchErrors.CREDENTIAL_INVALID);
    }

    // ---- heartbeat -----------------------------------------------------

    @Test
    void heartbeatRecordsLastSeenWithoutTouchingConfigOrStatus() {
        String[] ids = registered("tb-agt-13");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        assertThat(runnerRow(ids[0]).get("seen")).isEqualTo(false);

        BatchHeartbeatResponse response = agent.heartbeat(ids[0], "1", heartbeat(1));

        assertThat(response.isAcceptingAssignments()).isTrue();
        assertThat(response.getAssignments()).isEmpty();
        assertThat(Instant.parse(response.getServerTime())).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        Map<String, Object> runner = runnerRow(ids[0]);
        assertThat(runner.get("seen")).isEqualTo(true);
        assertThat(runner.get("runner_stat")).isEqualTo("ACTIVE");
        assertThat(((Number) runner.get("config_ver")).longValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ?", Integer.class, ids[0]))
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE target_id = ? "
                        + "AND event_type IN ('ENROLLMENT_ISSUED', 'REGISTERED', 'SESSION_OPENED')", Integer.class, ids[0]));
    }

    @Test
    void aPausedRunnerStopsAcceptingAssignments() {
        String[] ids = registered("tb-agt-14");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'PAUSED' WHERE runner_id = ?", ids[0]);

        assertThat(agent.heartbeat(ids[0], "1", heartbeat(1)).isAcceptingAssignments()).isFalse();
    }

    @Test
    void unknownAssignmentsAreToldToReconcile() {
        String[] ids = registered("tb-agt-15");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));

        BatchHeartbeatResponse response = agent.heartbeat(ids[0], "1", heartbeat(0, "BA0000000000000001"));

        assertThat(response.getAssignments()).hasSize(1);
        assertThat(response.getAssignments().get(0).getId()).isEqualTo("BA0000000000000001");
        assertThat(response.getAssignments().get(0).getAction()).isEqualTo("RECONCILE");
        assertThat(response.getAssignments().get(0).getLeaseUntil()).isNull();
    }

    @Test
    void heartbeatRequiresTheCurrentSession() {
        String[] ids = registered("tb-agt-16");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));

        assertError(() -> agent.heartbeat(ids[0], "0", heartbeat(1)), 409, BatchErrors.SESSION_STALE);
        assertError(() -> agent.heartbeat(ids[0], "2", heartbeat(1)), 409, BatchErrors.SESSION_STALE);
        assertError(() -> agent.heartbeat(ids[0], null, heartbeat(1)), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "abc", heartbeat(1)), 400, BatchErrors.REQUEST_INVALID);
        assertThat(runnerRow(ids[0]).get("seen")).isEqualTo(false);
    }

    @Test
    void invalidHeartbeatBodiesAreRejectedAndRollBackTheTouch() {
        String[] ids = registered("tb-agt-17");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        BatchHeartbeatRequest tooManySlots = heartbeat(2); // capacity 1
        BatchHeartbeatRequest badMode = heartbeat(1);
        badMode.setMode("SLEEPING");
        BatchHeartbeatRequest badTime = heartbeat(1);
        badTime.setObservedAt("yesterday");
        BatchHeartbeatRequest noAssignments = heartbeat(1);
        noAssignments.setAssignments(null);
        BatchHeartbeatRequest badPhase = heartbeat(1, "BA1");
        badPhase.getAssignments().get(0).setPhase("DANCING");
        BatchHeartbeatRequest missingPhase = heartbeat(1, "BA1");
        missingPhase.getAssignments().get(0).setPhase(null);
        BatchHeartbeatRequest nullItem = heartbeat(1);
        nullItem.getAssignments().add(null);

        assertError(() -> agent.heartbeat(ids[0], "1", tooManySlots), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", badMode), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", badTime), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", noAssignments), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", badPhase), 400, BatchErrors.REQUEST_INVALID);
        assertError(() -> agent.heartbeat(ids[0], "1", missingPhase), 400, BatchErrors.REQUEST_INVALID); // NPE가 아니라 400
        assertError(() -> agent.heartbeat(ids[0], "1", nullItem), 400, BatchErrors.REQUEST_INVALID);
        assertThat(runnerRow(ids[0]).get("seen")).isEqualTo(false);
    }

    @Test
    void tooManyHeartbeatItemsAreRejectedAsTooLarge() {
        String[] ids = registered("tb-agt-18");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        String[] many = new String[201];
        for (int i = 0; i < many.length; i++) {
            many[i] = "BA" + i;
        }

        assertError(() -> agent.heartbeat(ids[0], "1", heartbeat(1, many)), 413, BatchErrors.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aRevokedRunnerCannotHeartbeat() {
        String[] ids = registered("tb-agt-19");
        agent.openSession(ids[0], ids[1], session(UUID.randomUUID().toString(), "0"));
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'REVOKED' WHERE runner_id = ?", ids[0]);

        assertError(() -> agent.heartbeat(ids[0], "1", heartbeat(1)), 401, BatchErrors.CREDENTIAL_INVALID);
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchAgentServiceTest`
Expected: FAIL — 컴파일 오류(`BatchAgentService` 없음).

- [ ] **Step 3: 서비스 구현**

`kkdugi-admin/src/main/java/kkdugi/app/batch/service/BatchAgentService.java`:

```java
package kkdugi.app.batch.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.batch.config.BatchProperties;
import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.CredentialType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.mapper.BatchCredentialMapper;
import kkdugi.app.batch.mapper.BatchRunnerMapper;
import kkdugi.app.batch.models.BatchHeartbeatAction;
import kkdugi.app.batch.models.BatchHeartbeatItem;
import kkdugi.app.batch.models.BatchHeartbeatRequest;
import kkdugi.app.batch.models.BatchHeartbeatResponse;
import kkdugi.app.batch.models.BatchHeartbeatState;
import kkdugi.app.batch.models.BatchLimits;
import kkdugi.app.batch.models.BatchRegistrationRequest;
import kkdugi.app.batch.models.BatchRegistrationResponse;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerCredential;
import kkdugi.app.batch.models.BatchSessionRequest;
import kkdugi.app.batch.models.BatchSessionResponse;

/**
 * Runner가 호출하는 등록·세션·heartbeat. 인증(토큰 → runnerId/credentialId)은 보안 필터가 끝낸 뒤 호출된다.
 * 상태를 바꾸는 작업(등록, 세션 개설)은 runner 행을 잠근 뒤 credential 유효성과 상태를 다시 확인한다.
 */
@Service
public class BatchAgentService {

    private static final Set<String> OS = Set.of("LINUX", "WINDOWS");
    private static final Set<String> ARCHITECTURE = Set.of("AMD64", "ARM64");
    private static final Set<String> MODES = Set.of("ACCEPTING", "DRAINING", "DEGRADED");
    private static final Set<String> PHASES =
            Set.of("ASSIGNED", "STARTING", "RUNNING", "STOPPING", "FINISHED", "UNKNOWN");
    private static final Pattern BOOT_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final int MAX_HEARTBEAT_ITEMS = 200;
    private static final int MAX_ASSIGNMENT_ID_LENGTH = 20;

    private final BatchRunnerMapper runnerMapper;
    private final BatchCredentialMapper credentialMapper;
    private final BatchTokenService tokens;
    private final BatchEventService events;
    private final BatchProperties properties;

    public BatchAgentService(BatchRunnerMapper runnerMapper, BatchCredentialMapper credentialMapper,
            BatchTokenService tokens, BatchEventService events, BatchProperties properties) {
        this.runnerMapper = runnerMapper;
        this.credentialMapper = credentialMapper;
        this.tokens = tokens;
        this.events = events;
        this.properties = properties;
    }

    @Transactional
    public BatchRegistrationResponse register(String runnerId, String credentialId, BatchRegistrationRequest request) {
        String runnerCode = requireText(request.getRunnerCode(), 20);
        String agentVersion = requireText(request.getAgentVersion(), 50);
        String hostname = requireText(request.getHostname(), 200);
        String os = requireText(request.getOs(), 20);
        String architecture = requireText(request.getArchitecture(), 20);

        BatchRunner runner = runnerMapper.lockById(runnerId)
                .orElseThrow(() -> BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID));
        if (runner.getStatus() != RunnerStatus.REGISTERING) {
            throw BatchException.conflict(BatchErrors.STATE_CONFLICT);
        }
        if (!runner.getCode().equals(runnerCode)) {
            throw BatchException.forbidden(BatchErrors.RUNNER_FORBIDDEN);
        }
        if (!OS.contains(os) || !ARCHITECTURE.contains(architecture)) {
            throw BatchException.conflict(BatchErrors.PLATFORM_UNSUPPORTED);
        }
        // 원자 소비: 동시에 같은 토큰으로 온 요청 중 하나만 1을 받는다. 실패는 트랜잭션 전체를 되돌린다.
        if (credentialMapper.consumeEnrollment(credentialId) != 1) {
            throw BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID);
        }

        String accessId = BatchIds.next(BatchIds.CREDENTIAL);
        String secret = tokens.newSecret();
        BatchRunnerCredential access = new BatchRunnerCredential();
        access.setId(accessId);
        access.setRunnerId(runnerId);
        access.setType(CredentialType.ACCESS);
        access.setSecretHash(tokens.hash(secret));
        access.setCreatorId(runnerId);
        credentialMapper.insert(access, properties.getAccessTokenTtl().toSeconds());
        runnerMapper.markRegistered(runnerId, hostname, os, agentVersion, runnerId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("enrollmentCredentialId", credentialId);
        detail.put("accessCredentialId", accessId);
        detail.put("architecture", architecture);
        events.record(EventTargetType.RUNNER, runnerId, EventType.REGISTERED, RunnerStatus.REGISTERING.getCode(),
                RunnerStatus.ACTIVE.getCode(), ActorType.RUNNER, runnerId, detail);

        Instant expiresAt = credentialMapper.findForAuth(accessId).orElseThrow().getExpiresAt();
        return new BatchRegistrationResponse(runnerId, accessId, tokens.join(accessId, secret),
                BatchTime.format(expiresAt), Long.toString(runner.getSessionVer()));
    }

    @Transactional
    public BatchSessionResponse openSession(String runnerId, String credentialId, BatchSessionRequest request) {
        String bootId = request.getBootId();
        if (bootId == null || !BOOT_ID.matcher(bootId).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        long expectedSession = parseSession(request.getExpectedSession());
        String agentVersion = requireText(request.getAgentVersion(), 50);

        BatchRunner runner = runnerMapper.lockById(runnerId)
                .orElseThrow(() -> BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID));
        // 잠금 아래에서 자격증명을 다시 확인한다: 폐기와 겹쳐도 폐기 이후의 세션 개설은 성공하지 못한다.
        credentialMapper.findForAuth(credentialId)
                .filter(c -> c.isValid() && c.getType() == CredentialType.ACCESS && runnerId.equals(c.getRunnerId()))
                .orElseThrow(() -> BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID));
        if (runner.getStatus() != RunnerStatus.ACTIVE && runner.getStatus() != RunnerStatus.PAUSED) {
            throw BatchException.forbidden(BatchErrors.RUNNER_FORBIDDEN);
        }

        if (!bootId.equals(runner.getBootRef())) { // 같은 bootId의 재전송이면 현재 세대를 그대로 돌려준다
            if (runner.getSessionVer() != expectedSession) {
                throw BatchException.conflict(BatchErrors.SESSION_STALE);
            }
            runnerMapper.openSession(runnerId, bootId, agentVersion);
            events.record(EventTargetType.RUNNER, runnerId, EventType.SESSION_OPENED, null, null, ActorType.RUNNER,
                    runnerId, Map.of("session", Long.toString(runner.getSessionVer() + 1)));
        }

        BatchRunner current = runnerMapper.findById(runnerId, properties.getOnlineSeconds()).orElseThrow();
        return new BatchSessionResponse(runnerId, Long.toString(current.getSessionVer()),
                BatchTime.format(runnerMapper.now()), properties.getHeartbeatSeconds(), properties.getPollSeconds(),
                properties.getLeaseSeconds(), current.getCapacity(),
                new BatchLimits(properties.getJsonBytes(), properties.getInputBytes(), properties.getResultBytes(),
                        properties.getLogChunkBytes()));
    }

    @Transactional
    public BatchHeartbeatResponse heartbeat(String runnerId, String sessionHeader, BatchHeartbeatRequest request) {
        long session = parseSession(sessionHeader);
        validate(request);

        BatchHeartbeatState state = runnerMapper.touch(runnerId, session).orElse(null);
        if (state == null) {
            BatchRunner runner = runnerMapper.findById(runnerId, properties.getOnlineSeconds())
                    .orElseThrow(() -> BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID));
            if (runner.getStatus() != RunnerStatus.ACTIVE && runner.getStatus() != RunnerStatus.PAUSED) {
                throw BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID);
            }
            throw BatchException.conflict(BatchErrors.SESSION_STALE);
        }
        if (request.getFreeSlots() > state.getCapacity()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID); // 예외로 touch도 롤백된다
        }

        // S1 한계: 배정(Attempt) 테이블이 없으므로 보고된 assignment id는 모두 admin이 모르는 값이다.
        // S3에서 실제 조회로 대체한다(빈 배열을 고정 반환하는 임시 구현으로 남기지 않는다).
        List<BatchHeartbeatAction> actions = request.getAssignments().stream()
                .map(item -> new BatchHeartbeatAction(item.getId(), "RECONCILE", null, null, null)).toList();
        return new BatchHeartbeatResponse(BatchTime.format(runnerMapper.now()),
                state.getStatus() == RunnerStatus.ACTIVE, actions);
    }

    private void validate(BatchHeartbeatRequest request) {
        if (request.getObservedAt() == null || request.getMode() == null || request.getFreeSlots() == null
                || request.getFreeSlots() < 0 || request.getAssignments() == null
                || !MODES.contains(request.getMode())) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        try {
            Instant.parse(request.getObservedAt());
        } catch (DateTimeParseException e) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        if (request.getAssignments().size() > MAX_HEARTBEAT_ITEMS) {
            throw BatchException.tooLarge(BatchErrors.PAYLOAD_TOO_LARGE);
        }
        for (BatchHeartbeatItem item : request.getAssignments()) {
            if (item == null || item.getId() == null || item.getId().isBlank()
                    || item.getId().length() > MAX_ASSIGNMENT_ID_LENGTH
                    || item.getPhase() == null || !PHASES.contains(item.getPhase())) { // Set.of(...).contains(null)은 NPE
                throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
            }
        }
    }

    private static long parseSession(String value) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID); // bigint 범위를 넘는 값
        }
    }

    private static String requireText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        return value;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchAgentServiceTest`
Expected: PASS (19 tests).

- [ ] **Step 5: Commit**

```bash
git add kkdugi-admin/src/main/java/kkdugi/app/batch kkdugi-admin/src/test/java/kkdugi/app/batch
git commit -m "feat(batch): add runner registration, session and heartbeat service" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: Runner API 보안 체인과 컨트롤러

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchAgentPrincipal.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/batch/config/{BatchAgentAuthentication,BatchAgentAuthenticationFilter,BatchErrorWriter,BatchAgentInterceptor,BatchAgentSecurityConfig,BatchAgentWebConfig,BatchBodyLimitFilter,BatchBodyLimitConfig}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/api/{BatchApiSupport,BatchAgentController}.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/api/BatchAgentApiTest.java`, `kkdugi-admin/src/test/java/kkdugi/app/batch/config/BatchBodyLimitFilterTest.java`

**Interfaces:**
- Consumes: `BatchAgentService`(Task 7), `BatchTokenService`, `BatchCredentialMapper`, `BatchProperties`, `BatchException`/`BatchErrors`, `BatchIdempotentResponse`.
- Produces:
  - `BatchAgentPrincipal`(`getRunnerId()`, `getCredentialId()`, `getType():CredentialType`), `BatchAgentSecurityConfig.AGENT_PATH = "/api/v1.0/batch-agent"`.
  - `BatchApiSupport`(abstract, 컨트롤러 부모): `@ExceptionHandler`로 `BatchException` → `{code,message}`(상태는 예외의 status), 본문 파싱/바인딩 오류 → 400 `REQUEST_INVALID`; `protected static ResponseEntity<String> json(BatchIdempotentResponse)`.
  - 엔드포인트: `POST /api/v1.0/batch-agent/{registrations,sessions,heartbeat}`.
  - `BatchBodyLimitFilter(int limitBytes, BatchErrorWriter errors)`: 실제로 읽은 byte 수가 한도를 넘으면 413 `batch.payload.too_large`(다운스트림 미호출), 한도 이하면 본문을 다시 읽을 수 있게 감싸서 통과시킨다. `BatchBodyLimitConfig`가 `FilterRegistrationBean`(빈 이름 `batchBodyLimitFilterRegistration`, 순서 -90 = Spring Security 체인 뒤, 경로 `/api/v1.0/batch-agent/*`, `/api/v1.0/admin/batch/*`)으로 등록한다. `BatchErrorWriter.write(HttpServletResponse, int, String)`는 public.

- [ ] **Step 1: API 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/api/BatchAgentApiTest.java`:

```java
package kkdugi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.Filter;
import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.service.BatchEnrollmentService;
import kkdugi.support.BatchTestData;

/** 실제 보안 필터 체인(사용자 체인 + 배치 Runner 체인)을 통과하는 Runner API 계약 테스트. */
@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchAgentApiTest {

    private static final String BASE = "/api/v1.0/batch-agent";
    private static final String REGISTER_BODY =
            "{\"runnerCode\":\"tb-api-1\",\"agentVersion\":\"0.1.0\",\"hostname\":\"host-1\",\"os\":\"LINUX\",\"architecture\":\"AMD64\"}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BatchEnrollmentService enrollment;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BatchTestData.wipe(jdbc);
        // MockMvc는 서블릿 필터 빈을 자동 등록하지 않는다. 운영과 같은 순서(보안 체인 → 본문 한도)로 직접 등록한다.
        Filter bodyLimit = context.getBean("batchBodyLimitFilterRegistration", FilterRegistrationBean.class).getFilter();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(bodyLimit).build();
    }

    @AfterEach
    void cleanUp() {
        BatchTestData.wipe(jdbc);
    }

    private static MockHttpServletRequestBuilder agentPost(String path, String bearer, String body) {
        MockHttpServletRequestBuilder builder = post(BASE + path).header("X-Protocol-Version", "1")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return bearer == null ? builder : builder.header("Authorization", "Bearer " + bearer);
    }

    private String enrollmentToken(String code) {
        String runnerId = BatchTestData.insertRunner(jdbc, code, "REGISTERING");
        return enrollment.issue(runnerId, BatchTestData.USER).getEnrollmentToken();
    }

    private String registerAndGetAccessToken(String code) throws Exception {
        String token = enrollmentToken(code);
        MvcResult result = mvc.perform(agentPost("/registrations", token, REGISTER_BODY.replace("tb-api-1", code)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void aRunnerRegistersOpensASessionAndSendsHeartbeats() throws Exception {
        String enrollmentToken = enrollmentToken("tb-api-1");

        MvcResult registered = mvc.perform(agentPost("/registrations", enrollmentToken, REGISTER_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.session").value("0"))
                .andExpect(jsonPath("$.runnerId").exists())
                .andExpect(jsonPath("$.credentialId").exists())
                .andExpect(jsonPath("$.tokenExpiresAt").exists())
                .andReturn();
        String accessToken = JsonPath.read(registered.getResponse().getContentAsString(), "$.accessToken");

        mvc.perform(agentPost("/sessions", accessToken,
                "{\"bootId\":\"" + UUID.randomUUID() + "\",\"expectedSession\":\"0\",\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.session").value("1"))
                .andExpect(jsonPath("$.heartbeatSeconds").value(10))
                .andExpect(jsonPath("$.pollSeconds").value(3))
                .andExpect(jsonPath("$.leaseSeconds").value(60))
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpect(jsonPath("$.limits.jsonBytes").value(1048576))
                .andExpect(jsonPath("$.limits.logChunkBytes").value(32768))
                .andExpect(jsonPath("$.serverTime").exists());

        mvc.perform(agentPost("/heartbeat", accessToken,
                "{\"observedAt\":\"2026-09-20T02:00:00Z\",\"mode\":\"ACCEPTING\",\"freeSlots\":1,\"assignments\":[]}")
                .header("X-Runner-Session", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingAssignments").value(true))
                .andExpect(jsonPath("$.assignments").isEmpty());
    }

    @Test
    void requestsWithoutACredentialAreUnauthorized() throws Exception {
        mvc.perform(agentPost("/sessions", null, "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("batch.credential.invalid"))
                .andExpect(jsonPath("$.message").exists());
        mvc.perform(agentPost("/heartbeat", "garbage", "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("batch.credential.invalid"));
    }

    @Test
    void aUserStyleJwtIsNotAValidRunnerCredential() throws Exception {
        mvc.perform(agentPost("/sessions", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl", "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("batch.credential.invalid"));
    }

    @Test
    void credentialTypesAreNotInterchangeable() throws Exception {
        String enrollmentToken = enrollmentToken("tb-api-1");
        // 등록 토큰은 등록에만 쓸 수 있다.
        mvc.perform(agentPost("/sessions", enrollmentToken, "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("batch.runner.forbidden"));
        // ACCESS 토큰은 등록에 쓸 수 없다.
        String accessToken = registerAndGetAccessToken("tb-api-2");
        mvc.perform(agentPost("/registrations", accessToken, REGISTER_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("batch.runner.forbidden"));
    }

    @Test
    void theProtocolVersionHeaderIsRequired() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-3");

        mvc.perform(post(BASE + "/heartbeat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.protocol.unsupported"));
        mvc.perform(post(BASE + "/heartbeat").header("Authorization", "Bearer " + accessToken)
                .header("X-Protocol-Version", "2").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.protocol.unsupported"));
    }

    @Test
    void unknownRequestFieldsAndMalformedBodiesAreBadRequests() throws Exception {
        String token = enrollmentToken("tb-api-1");

        mvc.perform(agentPost("/registrations", token, REGISTER_BODY.replace("}", ",\"extra\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        mvc.perform(agentPost("/registrations", token, "{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    @Test
    void domainErrorsUseTheContractStatusAndCode() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-4");

        // 세션 개설 전에는 세대 0이라 heartbeat의 세션 헤더 1은 낡은 세션이다.
        mvc.perform(agentPost("/heartbeat", accessToken,
                "{\"observedAt\":\"2026-09-20T02:00:00Z\",\"mode\":\"ACCEPTING\",\"freeSlots\":0,\"assignments\":[]}")
                .header("X-Runner-Session", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.session.stale"));
        mvc.perform(agentPost("/sessions", accessToken,
                "{\"bootId\":\"nope\",\"expectedSession\":\"0\",\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    @Test
    void aRevokedAccessTokenStopsWorking() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-5");
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET revoked_dtm = now() WHERE credential_type = 'ACCESS' "
                + "AND runner_id IN (SELECT runner_id FROM kkdugi_batch_runner WHERE runner_cd = 'tb-api-5')");

        mvc.perform(agentPost("/sessions", accessToken,
                "{\"bootId\":\"" + UUID.randomUUID() + "\",\"expectedSession\":\"0\",\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("batch.credential.invalid"));
    }

    @Test
    void aRunnerTokenDoesNotAuthenticateOnTheUserApi() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-6");

        mvc.perform(get("/api/v1.0/admin/authority/BA0000000000000001").header("Authorization", "Bearer " + accessToken)
                .header("X-Menu-Id", "M_TEST_API"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.err.unauthorized"));
    }

    private static String paddedRegisterBody(int totalBytes) {
        return REGISTER_BODY + " ".repeat(totalBytes - REGISTER_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void bodiesOverOneMibAreRejectedAfterAuthentication() throws Exception {
        String token = enrollmentToken("tb-api-1");

        mvc.perform(agentPost("/registrations", token, paddedRegisterBody(1_048_577)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("batch.payload.too_large"));
        // 인증이 먼저다: 자격증명이 없으면 크기와 무관하게 401이다.
        mvc.perform(agentPost("/registrations", null, paddedRegisterBody(1_048_577)))
                .andExpect(status().isUnauthorized());
        // 거절된 요청은 등록 토큰을 소비하지 않는다.
        assertThat(jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_cd = 'tb-api-1'",
                String.class)).isEqualTo("REGISTERING");
    }

    @Test
    void aBodyOfExactlyOneMibIsAccepted() throws Exception {
        String token = enrollmentToken("tb-api-1");

        mvc.perform(agentPost("/registrations", token, paddedRegisterBody(1_048_576)))
                .andExpect(status().isCreated());
    }

    @Test
    void wrongJsonTypesAreBadRequests() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-8");
        String boot = "\"bootId\":\"" + UUID.randomUUID() + "\",\"agentVersion\":\"0.1.0\"";
        // 세션 세대는 십진 문자열이어야 한다. 숫자 토큰은 "0"으로 바뀌지 않고 거절된다.
        mvc.perform(agentPost("/sessions", accessToken, "{" + boot + ",\"expectedSession\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        String heartbeat = "\"observedAt\":\"2026-09-20T02:00:00Z\",\"mode\":\"ACCEPTING\",\"assignments\":[]";
        for (String freeSlots : new String[] { "\"1\"", "1.5", "true" }) {
            mvc.perform(agentPost("/heartbeat", accessToken, "{" + heartbeat + ",\"freeSlots\":" + freeSlots + "}")
                    .header("X-Runner-Session", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        }
        // phase가 없는 항목은 500이 아니라 400이다.
        mvc.perform(agentPost("/heartbeat", accessToken,
                "{\"observedAt\":\"2026-09-20T02:00:00Z\",\"mode\":\"ACCEPTING\",\"freeSlots\":0,\"assignments\":[{\"id\":\"BA1\"}]}")
                .header("X-Runner-Session", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    @Test
    void successfulCallsRecordCredentialUsage() throws Exception {
        String accessToken = registerAndGetAccessToken("tb-api-7");

        mvc.perform(agentPost("/sessions", accessToken,
                "{\"bootId\":\"" + UUID.randomUUID() + "\",\"expectedSession\":\"0\",\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential c "
                + "JOIN kkdugi_batch_runner r ON r.runner_id = c.runner_id "
                + "WHERE r.runner_cd = 'tb-api-7' AND c.credential_type = 'ACCESS' AND c.last_used_dtm IS NOT NULL",
                Integer.class)).isEqualTo(1);
    }
}
```

`kkdugi-admin/src/test/java/kkdugi/app/batch/config/BatchBodyLimitFilterTest.java` (필터 단위 테스트. 한도를 16 byte로 낮춰 경계를 검사한다):

```java
package kkdugi.app.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import tools.jackson.databind.json.JsonMapper;

class BatchBodyLimitFilterTest {

    private static final int LIMIT = 16;

    private final BatchBodyLimitFilter filter = new BatchBodyLimitFilter(LIMIT, new BatchErrorWriter(JsonMapper.builder().build()));

    /** declared가 null이면 실제 본문 길이를 Content-Length로 쓰고, 아니면 그 값을 선언한 것처럼 보이게 한다(-1은 Content-Length 없음). */
    private MockHttpServletResponse run(byte[] body, Long declared, AtomicReference<byte[]> seen) throws Exception {
        MockHttpServletRequest mock = new MockHttpServletRequest("POST", "/api/v1.0/batch-agent/heartbeat");
        mock.setContent(body);
        HttpServletRequest request = declared == null ? mock : new HttpServletRequestWrapper(mock) {
            @Override
            public long getContentLengthLong() {
                return declared;
            }

            @Override
            public int getContentLength() {
                return (int) (long) declared;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> seen.set(req.getInputStream().readAllBytes());
        filter.doFilter(request, response, chain);
        return response;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aBodyOfExactlyTheLimitPassesAndTheDownstreamReadsItAgain() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();
        byte[] body = bytes("{\"a\":\"12345678\"}"); // 16 bytes
        assertThat(body).hasSize(LIMIT);

        MockHttpServletResponse response = run(body, null, seen);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(body);
    }

    @Test
    void oneByteOverTheLimitIsRejectedWithoutCallingDownstream() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();

        MockHttpServletResponse response = run(bytes("{\"a\":\"123456789\"}"), null, seen); // 17 bytes

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("batch.payload.too_large");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(seen.get()).isNull();
    }

    @Test
    void multiByteUtf8IsCountedInBytesNotCharacters() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();

        assertThat(run(bytes("가나다라마"), null, seen).getStatus()).isEqualTo(200); // 15 bytes
        seen.set(null);
        assertThat(run(bytes("가나다라마바"), null, seen).getStatus()).isEqualTo(413); // 6자지만 18 bytes
        assertThat(seen.get()).isNull();
    }

    @Test
    void leadingWhitespaceCountsTowardTheLimit() throws Exception {
        assertThat(run(bytes(" ".repeat(LIMIT + 1) + "{}"), null, new AtomicReference<>()).getStatus()).isEqualTo(413);
    }

    @Test
    void theLimitAppliesEvenWithoutAContentLength() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();

        assertThat(run(new byte[LIMIT], -1L, seen).getStatus()).isEqualTo(200);
        seen.set(null);
        assertThat(run(new byte[LIMIT + 1], -1L, seen).getStatus()).isEqualTo(413);
        assertThat(seen.get()).isNull();
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRejectedBeforeReading() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();

        assertThat(run(new byte[0], 1000L, seen).getStatus()).isEqualTo(413);
        assertThat(seen.get()).isNull();
    }

    @Test
    void theDownstreamCanReadTheBodyThroughAReaderToo() throws Exception {
        MockHttpServletRequest mock = new MockHttpServletRequest("POST", "/x");
        mock.setContent(bytes("{\"k\":\"가\"}"));
        mock.setCharacterEncoding("UTF-8");
        AtomicReference<String> text = new AtomicReference<>();

        filter.doFilter(mock, new MockHttpServletResponse(), (req, res) -> text.set(req.getReader().readLine()));

        assertThat(text.get()).isEqualTo("{\"k\":\"가\"}");
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchBodyLimitFilterTest`
Expected: FAIL — 컴파일 오류(`BatchBodyLimitFilter` 없음). 아래 Step 3에서 구현한다.

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchAgentApiTest`
Expected: FAIL — 컴파일 오류는 없지만 `/api/v1.0/batch-agent/**`가 없어 대부분 404 또는 401(`auth.err.unauthorized`)로 실패.

- [ ] **Step 2: 주체·인증 토큰·오류 작성기 작성**

`kkdugi-admin/src/main/java/kkdugi/app/batch/models/BatchAgentPrincipal.java`:

```java
package kkdugi.app.batch.models;

import kkdugi.app.batch.enums.CredentialType;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 인증된 runner. runner ID는 토큰(자격증명)에서 결정되며 경로나 본문으로 바꿀 수 없다. */
@Getter
@AllArgsConstructor
public class BatchAgentPrincipal {

    private final String runnerId;
    private final String credentialId;
    private final CredentialType type;
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchAgentAuthentication.java`:

```java
package kkdugi.app.batch.config;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import kkdugi.app.batch.models.BatchAgentPrincipal;

/** 자격증명 종류가 권한({@code BATCH_ENROLLMENT}/{@code BATCH_ACCESS})이 되어 endpoint별 종류 검사를 한다. */
public class BatchAgentAuthentication extends AbstractAuthenticationToken {

    private final BatchAgentPrincipal principal;

    public BatchAgentAuthentication(BatchAgentPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("BATCH_" + principal.getType().name())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchErrorWriter.java`:

```java
package kkdugi.app.batch.config;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.core.exceptions.ExceptionMessage;
import tools.jackson.databind.ObjectMapper;

/** Runner 체인의 401/403과 본문 한도 필터의 413을 계약의 {@code {code, message}} JSON으로 쓴다. */
public class BatchErrorWriter {

    private final ObjectMapper objectMapper;

    public BatchErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void unauthorized(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, BatchErrors.CREDENTIAL_INVALID);
    }

    public void forbidden(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, BatchErrors.RUNNER_FORBIDDEN);
    }

    public void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), new ExceptionMessage(code));
    }
}
```

- [ ] **Step 3: 인증 필터·인터셉터·설정 작성**

`kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchAgentAuthenticationFilter.java`:

```java
package kkdugi.app.batch.config;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.app.batch.mapper.BatchCredentialMapper;
import kkdugi.app.batch.models.BatchAgentPrincipal;
import kkdugi.app.batch.service.BatchTokenService;

/**
 * {@code Authorization: Bearer {credentialId}.{secret}}을 검증한다. 검증에 실패하면 인증 없이 통과시키고
 * 뒤의 인가 단계가 401을 낸다. <b>@Component로 등록하지 않는다</b> — Spring Boot가 Filter 빈을 전역 서블릿
 * 필터로도 등록해서, Runner 체인 밖의 모든 요청에서 실행되기 때문이다({@link BatchAgentSecurityConfig}가 직접 생성).
 */
public class BatchAgentAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final BatchTokenService tokens;
    private final BatchCredentialMapper credentialMapper;
    private final BatchProperties properties;

    public BatchAgentAuthenticationFilter(BatchTokenService tokens, BatchCredentialMapper credentialMapper,
            BatchProperties properties) {
        this.tokens = tokens;
        this.credentialMapper = credentialMapper;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            BatchTokenService.ParsedToken parsed = tokens.parse(header.substring(PREFIX.length()).trim());
            if (parsed != null) {
                credentialMapper.findForAuth(parsed.getCredentialId())
                        .filter(c -> c.isValid() && tokens.matches(parsed.getSecret(), c.getSecretHash()))
                        .ifPresent(c -> {
                            credentialMapper.touchLastUsed(c.getId(), properties.getLastUsedTouchSeconds());
                            SecurityContextHolder.getContext().setAuthentication(new BatchAgentAuthentication(
                                    new BatchAgentPrincipal(c.getRunnerId(), c.getId(), c.getType())));
                        });
            }
        }
        chain.doFilter(request, response);
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchAgentInterceptor.java`:

```java
package kkdugi.app.batch.config;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;

/** 모든 Runner API 응답에 {@code no-store}를 붙이고 {@code X-Protocol-Version: 1}을 요구한다. */
public class BatchAgentInterceptor implements HandlerInterceptor {

    static final String PROTOCOL_HEADER = "X-Protocol-Version";
    static final String SUPPORTED_PROTOCOL = "1";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store");
        if (!SUPPORTED_PROTOCOL.equals(request.getHeader(PROTOCOL_HEADER))) {
            throw BatchException.conflict(BatchErrors.PROTOCOL_UNSUPPORTED);
        }
        return true;
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchAgentWebConfig.java`:

```java
package kkdugi.app.batch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class BatchAgentWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BatchAgentInterceptor()).addPathPatterns(BatchAgentSecurityConfig.AGENT_PATH + "/**");
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchAgentSecurityConfig.java`:

```java
package kkdugi.app.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import kkdugi.app.batch.mapper.BatchCredentialMapper;
import kkdugi.app.batch.service.BatchTokenService;
import tools.jackson.databind.ObjectMapper;

/**
 * Runner API 전용 보안 체인. core.security의 사용자 JWT 체인은 {@code /api/**} 전체를 잡으므로
 * 이 체인이 더 높은 순위({@code @Order(1)})로 {@code /api/v1.0/batch-agent/**}만 먼저 가져간다.
 * core는 배치를 모른다 — 라이브러리로 분리할 때 이 클래스가 함께 나간다.
 */
@Configuration
public class BatchAgentSecurityConfig {

    public static final String AGENT_PATH = "/api/v1.0/batch-agent";

    @Bean
    @Order(1)
    SecurityFilterChain batchAgentFilterChain(HttpSecurity http, BatchTokenService tokens,
            BatchCredentialMapper credentialMapper, BatchProperties properties, ObjectMapper objectMapper)
            throws Exception {
        BatchAgentAuthenticationFilter filter = new BatchAgentAuthenticationFilter(tokens, credentialMapper, properties);
        BatchErrorWriter errors = new BatchErrorWriter(objectMapper);
        return http
                .securityMatcher(AGENT_PATH + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(errors::unauthorized)
                        .accessDeniedHandler(errors::forbidden))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, AGENT_PATH + "/registrations").hasAuthority("BATCH_ENROLLMENT")
                        .anyRequest().hasAuthority("BATCH_ACCESS"))
                .build();
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchBodyLimitFilter.java`:

```java
package kkdugi.app.batch.config;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import kkdugi.app.batch.exceptions.BatchErrors;

/**
 * 배치 API 요청 본문을 {@code limitBytes}(UTF-8 byte) 이하로 제한한다(계약: 1 MiB 초과는 413). Content-Length를 믿지 않는다 —
 * 선언이 한도를 넘으면 읽기 전에 거절하지만, 선언이 없거나(chunked) 거짓이어도 실제로 읽은 byte 수로 판정한다.
 * 한도 이하의 본문은 메모리에 담아 하위 필터·컨트롤러가 다시 읽을 수 있게 한다. 한도 이상을 메모리에 올리지 않는다.
 * <b>인증 이후</b>에 실행되도록 Spring Security 필터 체인 뒤에 등록한다({@link BatchBodyLimitConfig}).
 */
public class BatchBodyLimitFilter extends OncePerRequestFilter {

    private final int limitBytes;
    private final BatchErrorWriter errors;

    public BatchBodyLimitFilter(int limitBytes, BatchErrorWriter errors) {
        this.limitBytes = limitBytes;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > limitBytes) {
            errors.write(response, 413, BatchErrors.PAYLOAD_TOO_LARGE);
            return;
        }
        byte[] body = readAtMost(request.getInputStream());
        if (body == null) {
            errors.write(response, 413, BatchErrors.PAYLOAD_TOO_LARGE);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    /** 한도를 넘는 순간 읽기를 멈추고 null을 돌려준다. */
    private byte[] readAtMost(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limitBytes) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("non-blocking reads are not supported");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return in.read(target, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = StandardCharsets.UTF_8;
            String encoding = getCharacterEncoding();
            if (encoding != null) {
                try {
                    charset = Charset.forName(encoding);
                } catch (IllegalArgumentException e) {
                    // 알 수 없는 인코딩 이름은 UTF-8로 처리한다.
                }
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/app/batch/config/BatchBodyLimitConfig.java`:

```java
package kkdugi.app.batch.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/**
 * 본문 한도 필터를 배치 경로에만 서블릿 필터로 등록한다. {@code FilterRegistrationBean}으로 감싸므로 필터 자체가 전역 빈이
 * 되지 않는다. 처리 순서는 인증(Spring Security 체인) → 본문 한도 → 프로토콜 버전/메뉴 권한이다.
 */
@Configuration
public class BatchBodyLimitConfig {

    /** Spring Security 서블릿 필터 순서(-100)보다 뒤. */
    private static final int AFTER_SECURITY = -90;

    @Bean
    FilterRegistrationBean<BatchBodyLimitFilter> batchBodyLimitFilterRegistration(BatchProperties properties,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<BatchBodyLimitFilter> registration = new FilterRegistrationBean<>(
                new BatchBodyLimitFilter(properties.getJsonBytes(), new BatchErrorWriter(objectMapper)));
        registration.addUrlPatterns(BatchAgentSecurityConfig.AGENT_PATH + "/*", "/api/v1.0/admin/batch/*");
        registration.setOrder(AFTER_SECURITY);
        return registration;
    }
}
```

- [ ] **Step 4: 컨트롤러 부모와 Runner 컨트롤러 작성**

`kkdugi-admin/src/main/java/kkdugi/api/BatchApiSupport.java`:

```java
package kkdugi.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchIdempotentResponse;
import kkdugi.core.exceptions.ExceptionMessage;

/**
 * 배치 컨트롤러의 공통 부모. 오류를 계약의 {@code {code, message}}로 바꾼다. 컨트롤러 안의
 * {@code @ExceptionHandler}는 전역 {@code RestfulExceptionAdvice}보다 우선하므로 core를 수정하지 않는다.
 */
public abstract class BatchApiSupport {

    @ExceptionHandler(BatchException.class)
    public ResponseEntity<ExceptionMessage> handleBatch(BatchException e) {
        return ResponseEntity.status(e.getStatus()).body(new ExceptionMessage(e.getCode()));
    }

    /** 잘못된 JSON, 알 수 없는 필드({@code BatchStrictRequest}), 타입 불일치, 쿼리 바인딩 실패. */
    @ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            BindException.class })
    public ResponseEntity<ExceptionMessage> handleMalformed(Exception e) {
        return ResponseEntity.badRequest().body(new ExceptionMessage(BatchErrors.REQUEST_INVALID));
    }

    /** 멱등 명령의 저장된/신규 응답을 그대로 내보낸다(본문은 이미 직렬화된 JSON). */
    protected static ResponseEntity<String> json(BatchIdempotentResponse response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.getStatus());
        if (response.getLocation() != null) {
            builder.header(HttpHeaders.LOCATION, response.getLocation());
        }
        if (response.getBody() == null) {
            return builder.build();
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(response.getBody());
    }
}
```

`kkdugi-admin/src/main/java/kkdugi/api/BatchAgentController.java`:

```java
package kkdugi.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.batch.config.BatchAgentSecurityConfig;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchAgentPrincipal;
import kkdugi.app.batch.models.BatchHeartbeatRequest;
import kkdugi.app.batch.models.BatchHeartbeatResponse;
import kkdugi.app.batch.models.BatchRegistrationRequest;
import kkdugi.app.batch.models.BatchRegistrationResponse;
import kkdugi.app.batch.models.BatchSessionRequest;
import kkdugi.app.batch.models.BatchSessionResponse;
import kkdugi.app.batch.service.BatchAgentService;

/**
 * Runner API(runner-api.md). 인증·자격증명 종류 검사는 {@code BatchAgentSecurityConfig}의 전용 보안 체인이,
 * 프로토콜 버전·no-store는 {@code BatchAgentInterceptor}가 처리한다. 여기서는 인증된 runner ID로 서비스를 호출한다.
 */
@RestController
@RequestMapping(BatchAgentSecurityConfig.AGENT_PATH)
public class BatchAgentController extends BatchApiSupport {

    private final BatchAgentService service;

    public BatchAgentController(BatchAgentService service) {
        this.service = service;
    }

    @PostMapping("/registrations")
    public ResponseEntity<BatchRegistrationResponse> register(@RequestBody BatchRegistrationRequest request) {
        BatchAgentPrincipal principal = principal();
        return ResponseEntity.status(201).body(service.register(principal.getRunnerId(), principal.getCredentialId(), request));
    }

    @PostMapping("/sessions")
    public BatchSessionResponse openSession(@RequestBody BatchSessionRequest request) {
        BatchAgentPrincipal principal = principal();
        return service.openSession(principal.getRunnerId(), principal.getCredentialId(), request);
    }

    @PostMapping("/heartbeat")
    public BatchHeartbeatResponse heartbeat(
            @RequestHeader(value = "X-Runner-Session", required = false) String session,
            @RequestBody BatchHeartbeatRequest request) {
        return service.heartbeat(principal().getRunnerId(), session, request);
    }

    private static BatchAgentPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof BatchAgentPrincipal principal)) {
            throw BatchException.unauthorized(BatchErrors.CREDENTIAL_INVALID);
        }
        return principal;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인과 회귀 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest="BatchAgentApiTest,BatchBodyLimitFilterTest"`
Expected: PASS (13 + 7 tests).

`kkdugi-admin/src/test/java/kkdugi/app/batch/config/BatchBodyLimitOrderTest.java`: 본문 한도 필터의 `FilterRegistrationBean` 순서가 Spring Security 체인(`DelegatingFilterProxyRegistrationBean`)보다 뒤이고 경로가 배치 두 곳뿐인지 공유 컨텍스트에서 검사한다(2개). MockMvc 테스트는 필터 순서를 직접 흉내 내므로 운영 컨테이너의 등록 순서는 이 테스트로만 지켜진다. **실제 Tomcat HTTP 검증은 자동화할 수 없다**: `MessageUtils`/`SerialUtils` 같은 core static 싱글톤이 두 번째 `ApplicationContext`를 거부해서 `RANDOM_PORT` 테스트는 전체 실행에서 컨텍스트 로딩에 실패한다(단독 실행에서만 통과). S1 구현 때 실제 서버로 한 차례 검증했다: 인증 없는 1 MiB 초과 요청은 401, 인증된 초과·chunked 초과 요청은 413, 정확히 한도인 본문은 필터를 통과. `bodiesOverOneMibAreRejectedAfterAuthentication`에서 인증 없는 초과 본문이 401 대신 413이면 필터 순서가 보안 체인보다 앞선 것이다(`.apply(springSecurity())` 뒤에 `.addFilters(...)`인지, `BatchBodyLimitConfig`의 순서가 -90인지 확인).

Run(기존 인증 체인 회귀): `./mvnw.cmd -B -ntp test -Dtest="SecurityCheckerApiTest,SecurityCheckerTest,AuthenticationProcessingFilterTest,LoginControllerTest,PragmaControllerTest"`
Expected: 베이스라인과 같은 결과(베이스라인에서 통과하던 테스트가 새로 실패하면 회귀). 새로 실패하면 `BatchAgentSecurityConfig`의 `@Order(1)`/`securityMatcher`가 기존 체인의 매칭을 바꾸지 않았는지, `BatchAgentAuthenticationFilter`가 빈으로 등록되지 않았는지 확인한다.

- [ ] **Step 6: Commit**

```bash
git add kkdugi-admin/src/main/java/kkdugi/app/batch kkdugi-admin/src/main/java/kkdugi/api/BatchApiSupport.java kkdugi-admin/src/main/java/kkdugi/api/BatchAgentController.java kkdugi-admin/src/test/java/kkdugi/api/BatchAgentApiTest.java kkdugi-admin/src/test/java/kkdugi/app/batch/config
git commit -m "feat(batch): add runner API security chain and controller" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: 관리자 Runner API 컨트롤러

**Files:**
- Modify: `kkdugi-admin/src/test/java/kkdugi/support/BatchTestData.java` (`API_USER` 추가, `wipe` 확장)
- Modify: `kkdugi-admin/src/test/java/kkdugi/support/TestAuthorization.java` (`mvcWithFilters` 추가)
- Create: `kkdugi-admin/src/main/java/kkdugi/api/admin/AdminBatchRunnerController.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/api/admin/AdminBatchRunnerControllerTest.java`

**Interfaces:**
- Consumes: `BatchRunnerService`, `BatchEnrollmentService`, `BatchIdempotencyService`, `BatchRequestKeys`, `BatchApiSupport.json(...)`, 요청/결과 모델, `SessionUtils.getUser().getId()`, `TestAuthorization.mvc(...)`.
- Produces: `/api/v1.0/admin/batch/runners` — `GET`(목록, 필터 code/name/status/page/pageSize), `GET /{id}`, `POST`(201+Location), `PUT /{id}`(If-Match, 200), `DELETE /{id}`(204), `POST /{id}/enrollment`(201, no-store, 멱등 제외), `POST /{id}/revoke`(200). 쓰기 명령은 `Idempotency-Key` + `X-Request-Created-At` 필수(enrollment 제외). program `admin/batch/runner`.

- [ ] **Step 1: 테스트 지원 클래스 확장**

`kkdugi-admin/src/test/java/kkdugi/support/BatchTestData.java`에서 `USER` 상수 아래에 상수를 추가하고 `wipe` 메서드를 다음으로 교체한다(관리자 API 테스트는 `TestAuthorization`의 세션 사용자 `U_TEST_API`로 기록된다).

```java
    /** {@code TestAuthorization}이 만드는 세션 사용자. 관리자 API 테스트가 남기는 event/멱등 기록의 actor다. */
    public static final String API_USER = "U_TEST_API";

    public static void wipe(JdbcTemplate jdbc) {
        String runnerIds = "SELECT runner_id FROM kkdugi_batch_runner WHERE runner_cd LIKE '" + CODE_PREFIX + "%'";
        jdbc.update("DELETE FROM kkdugi_batch_event WHERE target_id IN (" + runnerIds + ") OR actor_id IN (?, ?)",
                USER, API_USER);
        jdbc.update("DELETE FROM kkdugi_batch_runner_credential WHERE runner_id IN (" + runnerIds + ")");
        jdbc.update("DELETE FROM kkdugi_batch_runner WHERE runner_cd LIKE '" + CODE_PREFIX + "%'");
        jdbc.update("DELETE FROM kkdugi_batch_api_request WHERE subject_id IN (?, ?)", USER, API_USER);
    }
```

`kkdugi-admin/src/test/java/kkdugi/support/TestAuthorization.java`에서 `import org.springframework.security.core.context.SecurityContextHolder;` 위에 `import jakarta.servlet.Filter;`를 추가하고, 마지막 `mvc(WebApplicationContext, String, int, String...)` 메서드를 다음 두 메서드로 교체한다(MockMvc는 서블릿 필터 빈을 자동 등록하지 않아, 본문 한도 필터를 거치는 요청을 검증하려면 필터를 직접 넣어야 한다).

```java
    /** 지정한 RBAC 비트/역할의 세션으로 호출하는 MockMvc — 인가 어노테이션 적용을 검증할 때 쓴다. */
    public static MockMvc mvc(WebApplicationContext context, String program, int bits, String... roles) {
        return mvcWithFilters(context, program, bits, new Filter[0], roles);
    }

    /** {@link #mvc}와 같지만 세션 주입 필터 뒤에 서블릿 필터를 더 등록한다(본문 한도 필터처럼 필터를 거치는 동작을 검증할 때 쓴다). */
    public static MockMvc mvcWithFilters(WebApplicationContext context, String program, int bits, Filter[] after,
            String... roles) {
        Filter session = (request, response, chain) -> {
            var previous = SecurityContextHolder.getContext();
            var security = SecurityContextHolder.createEmptyContext();
            security.setAuthentication(session(program, bits, roles));
            SecurityContextHolder.setContext(security);
            try { chain.doFilter(request, response); }
            finally { SecurityContextHolder.setContext(previous); }
        };
        Filter[] filters = new Filter[after.length + 1];
        filters[0] = session;
        System.arraycopy(after, 0, filters, 1, after.length);
        return MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(MockMvcRequestBuilders.get("/").header(SecurityChecker.MENU_ID_HEADER, "M_TEST_API"))
                .addFilters(filters).build();
    }
```

- [ ] **Step 2: 컨트롤러 테스트 작성 (실패)**

`kkdugi-admin/src/test/java/kkdugi/api/admin/AdminBatchRunnerControllerTest.java`:

```java
package kkdugi.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.Filter;
import kkdugi.KkdugiAdminApplication;
import kkdugi.support.BatchTestData;
import kkdugi.support.TestAuthorization;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminBatchRunnerControllerTest {

    private static final String URL = "/api/v1.0/admin/batch/runners";
    private static final String PROGRAM = "admin/batch/runner";
    private static final int READ = 1;
    private static final int WRTE = 2;
    private static final int DELT = 4;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BatchTestData.wipe(jdbc);
        mvc = TestAuthorization.mvc(context, PROGRAM); // SYS_ADMIN + 모든 RBAC 비트
    }

    @AfterEach
    void cleanUp() {
        BatchTestData.wipe(jdbc);
    }

    private static String body(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"Admin " + code + "\",\"capacity\":2}";
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
    }

    private static MockHttpServletRequestBuilder keyed(MockHttpServletRequestBuilder builder, String key, String createdAt) {
        return builder.header("Idempotency-Key", key).header("X-Request-Created-At", createdAt);
    }

    private static MockHttpServletRequestBuilder keyed(MockHttpServletRequestBuilder builder) {
        return keyed(builder, UUID.randomUUID().toString(), now());
    }

    private String createRunner(String code) throws Exception {
        MvcResult result = mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body(code))))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private int runnerCount(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner WHERE runner_cd = ?", Integer.class, code);
    }

    // ---- 생성·조회 -----------------------------------------------------

    @Test
    void createReturns201WithLocationAndTheRunner() throws Exception {
        MvcResult result = mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("tb-adm-1"))
                .andExpect(jsonPath("$.name").value("Admin tb-adm-1"))
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpect(jsonPath("$.status").value("REGISTERING"))
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.session").value("0"))
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist())
                .andExpect(jsonPath("$.bootRef").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result.getResponse().getHeader("Location")).endsWith(URL + "/" + id);
    }

    @Test
    void writeCommandsRequireIdempotencyHeaders() throws Exception {
        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        mvc.perform(post(URL).header("Idempotency-Key", "k1").contentType(APPLICATION_JSON).content(body("tb-adm-2")))
                .andExpect(status().isBadRequest());
        assertThat(runnerCount("tb-adm-2")).isZero();
    }

    @Test
    void replayingACreateReturnsTheSameRunnerWithoutCreatingAnother() throws Exception {
        String key = UUID.randomUUID().toString();
        String createdAt = now();

        MvcResult first = mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-3")), key, createdAt))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-3")), key, createdAt))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(
                        (String) JsonPath.read(first.getResponse().getContentAsString(), "$.id"))))
                .andReturn();

        assertThat((String) JsonPath.read(replay.getResponse().getContentAsString(), "$.id"))
                .isEqualTo(JsonPath.read(first.getResponse().getContentAsString(), "$.id"));
        assertThat(runnerCount("tb-adm-3")).isEqualTo(1);

        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-3x")), key, createdAt))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.idempotency.conflict"));
    }

    @Test
    void duplicateCodeAndInvalidBodiesAreRejected() throws Exception {
        createRunner("tb-adm-4");

        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-4"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.runner.duplicate_code"));
        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content("{\"code\":\"tb-adm-5\",\"name\":\"n\",\"capacity\":0}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON).content("{\"code\":\"tb-adm-5\",\"name\":\"n\",\"capacity\":1,\"extra\":true}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    @Test
    void listFiltersAndDetailAndNotFound() throws Exception {
        String id = createRunner("tb-adm-6");
        createRunner("tb-adm-7");

        mvc.perform(get(URL).param("code", "tb-adm-6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(200))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.contents[0].id").value(id));
        mvc.perform(get(URL).param("code", "tb-adm-").param("status", "REGISTERING").param("page", "1").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.contents.length()").value(1));
        mvc.perform(get(URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("tb-adm-6"));
        mvc.perform(get(URL + "/BRNOSUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("batch.runner.not_found"));
        mvc.perform(get(URL).param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
    }

    // ---- 수정·삭제 -----------------------------------------------------

    @Test
    void putRequiresIfMatchAndDetectsStaleVersions() throws Exception {
        String id = createRunner("tb-adm-8");
        String putBody = "{\"code\":\"tb-adm-8\",\"name\":\"Renamed\",\"capacity\":3}";

        mvc.perform(keyed(put(URL + "/" + id).contentType(APPLICATION_JSON).content(putBody)))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("batch.version.required"));
        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "\"1\"").contentType(APPLICATION_JSON).content(putBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.version").value("2"));
        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "\"1\"").contentType(APPLICATION_JSON).content(putBody)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("batch.version.conflict"));
        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "abc").contentType(APPLICATION_JSON).content(putBody)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replayingAPutAfterTheVersionMovedStillReturnsTheOriginalResponse() throws Exception {
        String id = createRunner("tb-adm-9");
        String putBody = "{\"code\":\"tb-adm-9\",\"name\":\"Once\",\"capacity\":2}";
        String key = UUID.randomUUID().toString();
        String createdAt = now();

        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "\"1\"").contentType(APPLICATION_JSON).content(putBody), key, createdAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2"));
        // 응답이 유실돼 같은 key·같은 If-Match로 재전송: version이 이미 올라갔어도 최초 응답이 재현된다.
        mvc.perform(keyed(put(URL + "/" + id).header("If-Match", "\"1\"").contentType(APPLICATION_JSON).content(putBody), key, createdAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2"))
                .andExpect(jsonPath("$.name").value("Once"));
    }

    @Test
    void deleteReturns204AndReplaysWithoutError() throws Exception {
        String id = createRunner("tb-adm-10");
        String key = UUID.randomUUID().toString();
        String createdAt = now();

        mvc.perform(keyed(delete(URL + "/" + id), key, createdAt)).andExpect(status().isNoContent());
        mvc.perform(keyed(delete(URL + "/" + id), key, createdAt)).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/" + id)).andExpect(status().isNotFound());
        // 새 key로 다시 지우면 이미 없으므로 404다(멱등 재현이 아니라 새 명령).
        mvc.perform(keyed(delete(URL + "/" + id))).andExpect(status().isNotFound());
    }

    @Test
    void anActiveRunnerCannotBeDeletedUntilRevoked() throws Exception {
        String id = createRunner("tb-adm-11");
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'ACTIVE' WHERE runner_id = ?", id);

        mvc.perform(keyed(delete(URL + "/" + id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch.state.conflict"));
    }

    // ---- 등록 토큰·폐기 ------------------------------------------------

    @Test
    void enrollmentIssuesANoStoreTokenWithoutIdempotencyHeaders() throws Exception {
        String id = createRunner("tb-adm-12");

        mvc.perform(post(URL + "/" + id + "/enrollment"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.credentialId").exists())
                .andExpect(jsonPath("$.enrollmentToken").value(containsString(".")))
                .andExpect(jsonPath("$.expiresAt").exists());
        mvc.perform(post(URL + "/BRNOSUCH/enrollment"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("batch.runner.not_found"));
    }

    @Test
    void revokeMarksTheRunnerRevokedAndValidatesTheReason() throws Exception {
        String id = createRunner("tb-adm-13");
        jdbc.update("UPDATE kkdugi_batch_runner SET runner_stat = 'ACTIVE' WHERE runner_id = ?", id);

        mvc.perform(keyed(post(URL + "/" + id + "/revoke").contentType(APPLICATION_JSON).content("{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        mvc.perform(keyed(post(URL + "/" + id + "/revoke").contentType(APPLICATION_JSON).content("{\"reason\":\"retired\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    // ---- 본문 한도·JSON 타입 -------------------------------------------

    @Test
    void bodiesOverOneMibAreRejectedWithoutSideEffects() throws Exception {
        Filter bodyLimit = context.getBean("batchBodyLimitFilterRegistration", FilterRegistrationBean.class).getFilter();
        MockMvc limited = TestAuthorization.mvcWithFilters(context, PROGRAM, 15, new Filter[] { bodyLimit }, "SYS_ADMIN");
        String base = body("tb-adm-19");
        String over = base + " ".repeat(1_048_577 - base.length()); // ASCII라 문자 수 == byte 수
        String exact = base + " ".repeat(1_048_576 - base.length());

        limited.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(over)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("batch.payload.too_large"));

        // 거절된 요청은 runner·멱등 기록·event를 만들지 않는다.
        assertThat(runnerCount("tb-adm-19")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_api_request WHERE subject_id = ?",
                Integer.class, BatchTestData.API_USER)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_event WHERE actor_id = ?",
                Integer.class, BatchTestData.API_USER)).isZero();

        // 정확히 한도인 본문은 통과한다.
        limited.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(exact)))
                .andExpect(status().isCreated());
    }

    @Test
    void wrongJsonTypesAreBadRequests() throws Exception {
        for (String capacity : new String[] { "1.9", "\"1\"", "true", "null", "[1]" }) {
            mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON)
                    .content("{\"code\":\"tb-adm-20\",\"name\":\"n\",\"capacity\":" + capacity + "}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        }
        mvc.perform(keyed(post(URL).contentType(APPLICATION_JSON)
                .content("{\"code\":\"tb-adm-20\",\"name\":123,\"capacity\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("batch.request.invalid"));
        assertThat(runnerCount("tb-adm-20")).isZero();
    }

    // ---- 권한 ----------------------------------------------------------

    @Test
    void readOnlyUsersCannotWrite() throws Exception {
        MockMvc readOnly = TestAuthorization.mvc(context, PROGRAM, READ, "SYS_ADMIN");

        readOnly.perform(get(URL)).andExpect(status().isOk());
        readOnly.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-14"))))
                .andExpect(status().isForbidden());
        assertThat(runnerCount("tb-adm-14")).isZero();
    }

    @Test
    void anotherMenusWritePermissionCannotCreateRunners() throws Exception {
        MockMvc otherMenu = TestAuthorization.mvc(context, "admin/code", 15, "SYS_ADMIN");

        otherMenu.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-15"))))
                .andExpect(status().isForbidden());
        otherMenu.perform(get(URL)).andExpect(status().isForbidden());
        assertThat(runnerCount("tb-adm-15")).isZero();
    }

    @Test
    void deleteNeedsTheDeletePermissionBit() throws Exception {
        String id = createRunner("tb-adm-16");
        MockMvc noDelete = TestAuthorization.mvc(context, PROGRAM, READ | WRTE, "SYS_ADMIN");

        noDelete.perform(keyed(delete(URL + "/" + id))).andExpect(status().isForbidden());
        TestAuthorization.mvc(context, PROGRAM, READ | DELT, "SYS_ADMIN")
                .perform(keyed(delete(URL + "/" + id))).andExpect(status().isNoContent());
    }

    @Test
    void enrollmentAndRevokeRequireTheSysAdminRole() throws Exception {
        String id = createRunner("tb-adm-17");
        MockMvc notSysAdmin = TestAuthorization.mvc(context, PROGRAM, 15); // 역할 없음

        notSysAdmin.perform(post(URL + "/" + id + "/enrollment")).andExpect(status().isForbidden());
        notSysAdmin.perform(keyed(post(URL + "/" + id + "/revoke").contentType(APPLICATION_JSON).content("{\"reason\":\"x\"}")))
                .andExpect(status().isForbidden());
        // 일반 설정 관리는 역할 없이도 메뉴 권한만으로 가능하다.
        notSysAdmin.perform(keyed(post(URL).contentType(APPLICATION_JSON).content(body("tb-adm-18"))))
                .andExpect(status().isCreated());
    }
}
```

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminBatchRunnerControllerTest`
Expected: FAIL — `/api/v1.0/admin/batch/runners`가 없어 404.

- [ ] **Step 3: 컨트롤러 구현**

`kkdugi-admin/src/main/java/kkdugi/api/admin/AdminBatchRunnerController.java`:

```java
package kkdugi.api.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import kkdugi.api.BatchApiSupport;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchEnrollmentResult;
import kkdugi.app.batch.models.BatchIdempotencyKey;
import kkdugi.app.batch.models.BatchIdempotentResponse;
import kkdugi.app.batch.models.BatchRevokeRequest;
import kkdugi.app.batch.models.BatchRunner;
import kkdugi.app.batch.models.BatchRunnerParams;
import kkdugi.app.batch.models.BatchRunnerRequest;
import kkdugi.app.batch.service.BatchEnrollmentService;
import kkdugi.app.batch.service.BatchIdempotencyService;
import kkdugi.app.batch.service.BatchRequestKeys;
import kkdugi.app.batch.service.BatchRunnerService;
import kkdugi.core.Constants;
import kkdugi.core.enums.Rbac;
import kkdugi.core.models.Page;
import kkdugi.core.security.annotation.HasRole;
import kkdugi.core.security.annotation.RequireAuthority;
import kkdugi.core.util.SessionUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 배치 Runner 관리 API(admin-api.md). 메뉴 RBAC는 {@code admin/batch/runner} 프로그램에 묶인다 —
 * 조회 READ, 생성·수정·폐기·토큰 발급 WRTE, 삭제 DELT. 등록 토큰 발급·폐기는 SYS_ADMIN 역할이 추가로 필요하다.
 * 일회성 토큰 발급을 제외한 쓰기 명령은 {@code Idempotency-Key} + {@code X-Request-Created-At}가 필수다.
 */
@RestController
@RequestMapping("/api/v1.0/admin/batch/runners")
public class AdminBatchRunnerController extends BatchApiSupport {

    private static final String PROGRAM = "admin/batch/runner";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String CREATED_AT_HEADER = "X-Request-Created-At";

    private final BatchRunnerService runners;
    private final BatchEnrollmentService enrollment;
    private final BatchIdempotencyService idempotency;
    private final BatchRequestKeys keys;
    private final ObjectMapper objectMapper;

    public AdminBatchRunnerController(BatchRunnerService runners, BatchEnrollmentService enrollment,
            BatchIdempotencyService idempotency, BatchRequestKeys keys, ObjectMapper objectMapper) {
        this.runners = runners;
        this.enrollment = enrollment;
        this.idempotency = idempotency;
        this.keys = keys;
        this.objectMapper = objectMapper;
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @GetMapping
    public Page<BatchRunner> search(BatchRunnerParams params) {
        return runners.search(params);
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @GetMapping("/{id}")
    public BatchRunner get(@PathVariable("id") String id) {
        return runners.get(id);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @PostMapping
    public ResponseEntity<String> create(@RequestBody BatchRunnerRequest body,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @RequestHeader(value = CREATED_AT_HEADER, required = false) String createdAt,
            HttpServletRequest request) {
        String actor = actor();
        BatchIdempotencyKey idempotencyKey = keys.forUser(actor, "POST", path(request), key, createdAt, body, null);
        return json(idempotency.execute(idempotencyKey, () -> {
            BatchRunner created = runners.create(body, actor);
            return new BatchIdempotentResponse(201, write(created), request.getRequestURI() + "/" + created.getId());
        }));
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @PutMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable("id") String id, @RequestBody BatchRunnerRequest body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @RequestHeader(value = CREATED_AT_HEADER, required = false) String createdAt,
            HttpServletRequest request) {
        long expectedVersion = parseIfMatch(ifMatch);
        String actor = actor();
        BatchIdempotencyKey idempotencyKey = keys.forUser(actor, "PUT", path(request), key, createdAt, body, ifMatch);
        return json(idempotency.execute(idempotencyKey,
                () -> new BatchIdempotentResponse(200, write(runners.update(id, body, expectedVersion, actor)), null)));
    }

    @RequireAuthority(value = Rbac.DELT, program = PROGRAM)
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable("id") String id,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @RequestHeader(value = CREATED_AT_HEADER, required = false) String createdAt,
            HttpServletRequest request) {
        String actor = actor();
        BatchIdempotencyKey idempotencyKey = keys.forUser(actor, "DELETE", path(request), key, createdAt, null, null);
        return json(idempotency.execute(idempotencyKey, () -> {
            runners.delete(id, actor);
            return new BatchIdempotentResponse(204, null, null);
        }));
    }

    /** 평문 토큰을 한 번만 내려주므로 멱등 재현 대상이 아니다(응답을 잃으면 새로 발급한다). */
    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/enrollment")
    public ResponseEntity<BatchEnrollmentResult> enroll(@PathVariable("id") String id) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(enrollment.issue(id, actor()));
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/revoke")
    public ResponseEntity<String> revoke(@PathVariable("id") String id, @RequestBody BatchRevokeRequest body,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @RequestHeader(value = CREATED_AT_HEADER, required = false) String createdAt,
            HttpServletRequest request) {
        String actor = actor();
        BatchIdempotencyKey idempotencyKey = keys.forUser(actor, "POST", path(request), key, createdAt, body, null);
        return json(idempotency.execute(idempotencyKey,
                () -> new BatchIdempotentResponse(200, write(enrollment.revoke(id, body.getReason(), actor)), null)));
    }

    private static String actor() {
        return SessionUtils.getUser().getId();
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private String write(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    /** {@code If-Match: "3"} 또는 {@code 3}. 누락은 428, 형식 오류는 400. */
    private static long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            throw BatchException.preconditionRequired(BatchErrors.VERSION_REQUIRED);
        }
        String value = header.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw BatchException.badRequest(BatchErrors.REQUEST_INVALID);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminBatchRunnerControllerTest`
Expected: PASS (17 tests).
막히는 지점: `TestAuthorization.mvc(context, PROGRAM, 15)`처럼 역할 없이 호출하는 오버로드가 컴파일되지 않으면 `TestAuthorization.mvc(WebApplicationContext, String, int, String...)`의 가변 인자에 빈 목록이 전달되는지 확인한다(기존 시그니처 그대로 쓴다).

- [ ] **Step 5: Commit**

```bash
git add kkdugi-admin/src/main/java/kkdugi/api/admin/AdminBatchRunnerController.java kkdugi-admin/src/test/java/kkdugi/api/admin/AdminBatchRunnerControllerTest.java kkdugi-admin/src/test/java/kkdugi/support/BatchTestData.java kkdugi-admin/src/test/java/kkdugi/support/TestAuthorization.java
git commit -m "feat(batch): add admin runner management API" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: 동시성 경합 테스트

**Files:**
- Test: `kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchRunnerConcurrencyTest.java`

**Interfaces:**
- Consumes: `BatchAgentService`, `BatchEnrollmentService`, `BatchTokenService`, `BatchRunnerMapper.lockById`, `PlatformTransactionManager`.
- Produces: 등록·폐기·세션·재발급 경합의 불변식(spec §8)을 검증하는 테스트. 잠금 순서를 어기는 구현 변경이 들어오면 실패한다.

이 태스크는 새 프로덕션 코드가 없다. 실패하면 Task 4~7의 잠금 순서(`lockById` → credential)를 고친다.

- [ ] **Step 1: 경합 테스트 작성**

`kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchRunnerConcurrencyTest.java`:

```java
package kkdugi.app.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.mapper.BatchRunnerMapper;
import kkdugi.app.batch.models.BatchRegistrationRequest;
import kkdugi.app.batch.models.BatchRegistrationResponse;
import kkdugi.app.batch.models.BatchSessionRequest;
import kkdugi.support.BatchTestData;

/**
 * runner 행 잠금 아래에서 등록·재발급·폐기·세션 개설이 직렬화되는지 검사한다. 토큰 소비 CAS 한 행만으로는
 * runner 상태 전이 전체가 직렬화되지 않는다(admin-plan-review.md).
 */
@SpringBootTest(classes = KkdugiAdminApplication.class)
class BatchRunnerConcurrencyTest {

    private static final String ACTOR = BatchTestData.USER;

    @Autowired
    private BatchAgentService agent;

    @Autowired
    private BatchEnrollmentService enrollment;

    @Autowired
    private BatchTokenService tokens;

    @Autowired
    private BatchRunnerMapper runnerMapper;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        BatchTestData.wipe(jdbc);
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        BatchTestData.wipe(jdbc);
    }

    /** {runnerId, enrollmentCredentialId} */
    private String[] registrable(String code) {
        String runnerId = BatchTestData.insertRunner(jdbc, code, "REGISTERING");
        String token = enrollment.issue(runnerId, ACTOR).getEnrollmentToken();
        return new String[] { runnerId, tokens.parse(token).getCredentialId() };
    }

    private static BatchRegistrationRequest registration(String code) {
        BatchRegistrationRequest request = new BatchRegistrationRequest();
        request.setRunnerCode(code);
        request.setAgentVersion("0.1.0");
        request.setHostname("host");
        request.setOs("LINUX");
        request.setArchitecture("AMD64");
        return request;
    }

    private static BatchSessionRequest session(String expected) {
        BatchSessionRequest request = new BatchSessionRequest();
        request.setBootId(UUID.randomUUID().toString());
        request.setExpectedSession(expected);
        request.setAgentVersion("0.1.0");
        return request;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 성공하면 true, 배치 오류(BatchException)면 false. 그 외 예외는 그대로 테스트를 실패시킨다. */
    private static Callable<Boolean> attempt(CountDownLatch start, Runnable action) {
        return () -> {
            start.await();
            try {
                action.run();
                return true;
            } catch (BatchException e) {
                return false;
            }
        };
    }

    private int openCredentials(String runnerId) {
        return jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND revoked_dtm IS NULL", Integer.class, runnerId);
    }

    /** 폐기 트랜잭션이 runner 행 잠금을 잡은 채로 열려 있게 하고, 그 사이 다른 스레드의 작업이 대기하게 만든다. */
    private Future<?> holdLockThenRevoke(String runnerId, CountDownLatch locked) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return pool.submit(() -> tx.executeWithoutResult(status -> {
            runnerMapper.lockById(runnerId);
            locked.countDown();
            pause(500); // 다른 스레드가 같은 행 잠금에서 대기할 시간
            enrollment.revoke(runnerId, "race", ACTOR);
        }));
    }

    /** runner 행 잠금만 잡고 holdMillis 뒤 커밋한다(상태 변경 없음). */
    private Future<?> holdLockOnly(String runnerId, CountDownLatch locked, long holdMillis) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return pool.submit(() -> tx.executeWithoutResult(status -> {
            runnerMapper.lockById(runnerId);
            locked.countDown();
            pause(holdMillis);
        }));
    }

    @Test
    void concurrentRegistrationsWithTheSameTokenSucceedExactlyOnce() throws Exception {
        String[] ids = registrable("tb-race-1");
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(attempt(start, () -> agent.register(ids[0], ids[1], registration("tb-race-1")))));
        }
        start.countDown();

        long successes = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }

        assertThat(successes).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND credential_type = 'ACCESS'", Integer.class, ids[0])).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_id = ?",
                String.class, ids[0])).isEqualTo("ACTIVE");
    }

    @Test
    void aRegistrationQueuedBehindARevokeFailsAndNeverRevivesTheRunner() throws Exception {
        String[] ids = registrable("tb-race-2");
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> revoker = holdLockThenRevoke(ids[0], locked);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> agent.register(ids[0], ids[1], registration("tb-race-2")))
                .isInstanceOf(BatchException.class);
        revoker.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_id = ?",
                String.class, ids[0])).isEqualTo("REVOKED");
        assertThat(openCredentials(ids[0])).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND credential_type = 'ACCESS'", Integer.class, ids[0])).isZero();
    }

    @Test
    void aSessionOpenQueuedBehindARevokeIsRejected() throws Exception {
        String[] ids = registrable("tb-race-3");
        BatchRegistrationResponse registered = agent.register(ids[0], ids[1], registration("tb-race-3"));
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> revoker = holdLockThenRevoke(ids[0], locked);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> agent.openSession(ids[0], registered.getCredentialId(), session("0")))
                .isInstanceOf(BatchException.class);
        revoker.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT session_ver FROM kkdugi_batch_runner WHERE runner_id = ?",
                Long.class, ids[0])).isZero();
        assertThat(openCredentials(ids[0])).isZero();
    }

    @Test
    void concurrentSessionOpensAdvanceTheGenerationExactlyOnce() throws Exception {
        String[] ids = registrable("tb-race-4");
        BatchRegistrationResponse registered = agent.register(ids[0], ids[1], registration("tb-race-4"));
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(attempt(start, () -> agent.openSession(ids[0], registered.getCredentialId(), session("0")))));
        }
        start.countDown();

        long successes = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }

        assertThat(successes).isEqualTo(1); // 서로 다른 bootId는 현재 세대를 아는 하나만 세대를 올린다
        assertThat(jdbc.queryForObject("SELECT session_ver FROM kkdugi_batch_runner WHERE runner_id = ?",
                Long.class, ids[0])).isEqualTo(1);
    }

    @Test
    void registrationRacingAReissueHasExactlyOneWinnerForTheToken() throws Exception {
        for (int round = 0; round < 5; round++) {
            String code = "tb-race-5" + round;
            String[] ids = registrable(code);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> register = pool.submit(attempt(start, () -> agent.register(ids[0], ids[1], registration(code))));
            Future<Boolean> reissue = pool.submit(attempt(start, () -> enrollment.issue(ids[0], ACTOR)));
            start.countDown();

            boolean registered = register.get(30, TimeUnit.SECONDS);
            boolean reissued = reissue.get(30, TimeUnit.SECONDS);

            // 재발급이 먼저면 이전 토큰이 폐기돼 등록이 실패하고, 등록이 먼저면 runner가 ACTIVE라 재발급이 409다.
            assertThat(registered ^ reissued).as("round %d: registered=%s reissued=%s", round, registered, reissued).isTrue();
            String status = jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_id = ?",
                    String.class, ids[0]);
            assertThat(status).isEqualTo(registered ? "ACTIVE" : "REGISTERING");
        }
    }

    /**
     * PostgreSQL의 now()는 트랜잭션 시작 시각이다. 만료 직전에 시작한 트랜잭션이 runner 행 잠금에서 기다리다 만료 뒤에 깨어나면
     * now() 기준으로는 아직 유효해 보인다. 만료 판정이 clock_timestamp()인지 검사한다(검토 문서 4번).
     */
    @Test
    void aRegistrationThatWaitedOnTheLockPastTheTokenExpiryIsRejected() throws Exception {
        String[] ids = registrable("tb-race-6");
        // 토큰은 0.8초 뒤에 만료되고 잠금은 1.5초 동안 잡혀 있다: 등록 트랜잭션은 만료 전에 시작해 만료 뒤에 잠금을 얻는다.
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET expires_dtm = clock_timestamp() + interval '800 milliseconds' "
                + "WHERE credential_id = ?", ids[1]);
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> holder = holdLockOnly(ids[0], locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> agent.register(ids[0], ids[1], registration("tb-race-6")))
                .isInstanceOfSatisfying(BatchException.class, e -> assertThat(e.getStatus()).isEqualTo(401));
        holder.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT consumed_dtm IS NULL FROM kkdugi_batch_runner_credential WHERE credential_id = ?",
                Boolean.class, ids[1])).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkdugi_batch_runner_credential "
                + "WHERE runner_id = ? AND credential_type = 'ACCESS'", Integer.class, ids[0])).isZero();
        assertThat(jdbc.queryForObject("SELECT runner_stat FROM kkdugi_batch_runner WHERE runner_id = ?",
                String.class, ids[0])).isEqualTo("REGISTERING");
    }

    @Test
    void aSessionOpenThatWaitedOnTheLockPastTheTokenExpiryIsRejected() throws Exception {
        String[] ids = registrable("tb-race-7");
        BatchRegistrationResponse registered = agent.register(ids[0], ids[1], registration("tb-race-7"));
        jdbc.update("UPDATE kkdugi_batch_runner_credential SET expires_dtm = clock_timestamp() + interval '800 milliseconds' "
                + "WHERE credential_id = ?", registered.getCredentialId());
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> holder = holdLockOnly(ids[0], locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> agent.openSession(ids[0], registered.getCredentialId(), session("0")))
                .isInstanceOfSatisfying(BatchException.class, e -> assertThat(e.getStatus()).isEqualTo(401));
        holder.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT session_ver FROM kkdugi_batch_runner WHERE runner_id = ?",
                Long.class, ids[0])).isZero();
    }
}
```

- [ ] **Step 2: 실행**

Run: `./mvnw.cmd -B -ntp test -Dtest=BatchRunnerConcurrencyTest`
Expected: PASS (7 tests). 첫 시도에서 통과하는 것이 정상이다(구현이 이미 `lockById`를 첫 문장으로 잡는다). 이 테스트는 잠금 순서 회귀를 막는 장치다 — 검증하려면 `BatchAgentService.register`에서 `lockById`를 임시로 지우고 `aRegistrationQueuedBehindARevokeFailsAndNeverRevivesTheRunner`가 실패하는 것을 확인한 뒤 원복한다(원복하지 않은 채 커밋하지 않는다). 만료 경계 테스트도 같은 방식으로 검증한다: Task 3의 `consumeEnrollment`/`findForAuth`의 `clock_timestamp()`를 임시로 `now()`로 되돌리면 `aRegistrationThatWaitedOnTheLockPastTheTokenExpiryIsRejected`가 실패해야 한다.

- [ ] **Step 3: Commit**

```bash
git add kkdugi-admin/src/test/java/kkdugi/app/batch/service/BatchRunnerConcurrencyTest.java
git commit -m "test(batch): add runner state race conditions" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: 문서, 개발용 메뉴 seed, 전체 검증, 실제 runner 등록

**Files:**
- Create: `docs/adr/0019-batch-library-boundary.md`
- Create: `docs/api/batch-runner.md`
- Modify: `docs/api/README.md`, `CLAUDE.md`, `docs/superpowers/specs/2026-09-20-batch-s1-runner-registration-design.md`
- Create: `scripts/dev/batch-menu-seed.sql`

**Interfaces:**
- Consumes: Task 1~10의 결과 전체.
- Produces: ADR-0019, 현행 API 문서, 개발용 메뉴 seed, 전체 테스트 통과 기록, 실제 runner 등록 확인.

- [ ] **Step 1: ADR-0019 작성**

`docs/adr/0019-batch-library-boundary.md`:

```markdown
# ADR-0019: 배치 기능은 라이브러리 분리를 전제로 한 단일 기능 패키지로 둔다

- 상태: 채택 (2026-09-20)
- 관련: [ADR-0016](0016-app-and-admin-feature-split.md), [ADR-0017](0017-menu-context-security-aspect.md), 설계 [S1 spec](../superpowers/specs/2026-09-20-batch-s1-runner-registration-design.md), 구현 계획 [S1 plan](../superpowers/plans/2026-09-20-batch-s1-runner-registration.md), 계약 [docs/batch](../batch/README.md)

## 배경

배치는 나중에 별도 라이브러리로 분리한다. 배치의 관리자 API와 Runner API는 같은 테이블·도메인 로직(Run, 배정)을 공유하고, Runner API는 사용자 JWT가 아닌 runner 전용 토큰으로 인증한다. ADR-0016의 `app.<기능>`/`app.admin.<기능>` 분리와 `core.security`의 `/api/**` 전체 사용자 인증 체인이 그대로는 맞지 않는다.

## 결정

1. **단일 기능 패키지 `kkdugi.app.batch`** (`enums`/`exceptions`/`config`/`models`/`mapper`/`service`). 관리자용과 Runner용을 나누면 서로 import할 수 없어 공유가 막히므로 ADR-0016의 분리를 배치에는 적용하지 않는다. `core`에만 의존하고, `core`와 다른 feature는 배치를 import하지 않는다.
2. **컨트롤러는 기존 규칙대로 `kkdugi.api`에 flat**: `AdminBatchRunnerController`(`/api/v1.0/admin/batch/runners`), `BatchAgentController`(`/api/v1.0/batch-agent`), 공통 부모 `BatchApiSupport`.
3. **Runner API는 배치가 소유한 `SecurityFilterChain`**(`BatchAgentSecurityConfig`, `@Order(1)`, `securityMatcher("/api/v1.0/batch-agent/**")`)으로 인증한다. `core.security`는 수정하지 않는다. 인증 필터는 빈으로 등록하지 않는다(Spring Boot가 Filter 빈을 전역 서블릿 필터로도 등록하기 때문).
4. **배치 enum은 `app.batch.enums`** 에 둔다(CLAUDE.md의 "코드 기반 enum은 `core.enums`" 규칙의 예외). MyBatis `default-enum-type-handler`는 `CodeEnums` 구현체면 패키지와 무관하게 동작한다.
5. **오류는 단일 `BatchException(status, code)`** + `BatchErrors` 상수. 컨트롤러 부모의 `@ExceptionHandler`가 `{code, message}`로 바꾼다. `RestfulExceptionAdvice`는 수정하지 않는다.
6. **메시지 키 `batch.*`** 는 `messages*.properties`에 추가하고, `BatchMessagesTest`가 모든 `BatchErrors` 코드의 메시지 존재를 검사한다.
7. **migration은 기존 `db/migration`의 V13부터** 조각별로 추가한다. Flyway 버전은 위치와 무관하게 전역 유일이므로 분리 시 별도 위치로 옮길 수 있다.
8. **감사 actor는 실제 사용자 ID**(`SessionUtils.getUser().getId()`)다. 다른 admin 서비스의 고정값 `"SYSTEM"`은 답습하지 않는다.
9. **멱등 키(`Idempotency-Key`)와 `kkdugi_batch_api_request`는 S1에 포함**한다(관리자 생성·PUT·DELETE 계약 준수, S2 설치 보고 PUT의 선행 조건).
10. **runner 상태를 바꾸는 모든 명령은 runner 행 `FOR UPDATE` 아래**에서 실행하고 credential 유효성을 다시 확인한다. 잠금 순서는 멱등 advisory lock → runner 행 → credential 행이다.
11. **만료·유효성 판정에는 `clock_timestamp()`** 를 쓴다. `now()`는 트랜잭션 시작 시각이라 행 잠금에서 기다리는 동안 이미 만료된 토큰을 유효로 볼 수 있다. 감사 컬럼(`reg_dtm`/`upd_dtm`)만 트랜잭션 시각을 쓴다.
12. **요청 본문 1 MiB 한도**는 배치가 소유한 서블릿 필터(`BatchBodyLimitFilter`)가 실제로 읽은 byte 수로 판정한다. `FilterRegistrationBean`으로 Spring Security 체인 뒤에 `/api/v1.0/batch-agent/*`, `/api/v1.0/admin/batch/*`에만 등록한다(필터 자체는 전역 빈이 아니다). 처리 순서는 인증 → 본문 한도 → 프로토콜 버전/메뉴 권한이다.
13. **요청 DTO의 문자열·정수 필드는 JSON 토큰 타입을 엄격히 검사**한다(`BatchStrictString`/`BatchStrictInteger`, 필드 단위 `@JsonDeserialize`). Jackson 기본 변환(`1.9`→1, `"1"`→1, `1`→`"1"`)을 배치 요청에서만 막고 전역 설정은 바꾸지 않는다.

## 분리 시 이동 목록

`kkdugi.app.batch.**`, `kkdugi.api.{AdminBatchRunnerController,BatchAgentController,BatchApiSupport}`, `mapper/postgres/app/batch/*.xml`, `db/migration`의 배치 migration(V13~), `messages*.properties`의 `batch.*` 키, 테스트(`kkdugi/app/batch`, `kkdugi/api/*Batch*`, `kkdugi/support/BatchTestData`). 메뉴 seed(`admin/batch/runner` 화면 메뉴)는 호스트 앱 데이터라 남는다.

## 결과

- 배치 소유 코드가 `Batch*` 이름과 `app.batch` 패키지로 식별되어 분리 대상이 명확하다.
- CLAUDE.md의 enum 위치 규칙과 다른 예외가 생겼다(이 ADR과 CLAUDE.md에 기록).
- 배치 API는 기존 admin API와 달리 REST 동사(GET/POST/PUT/DELETE)와 `If-Match`/`Idempotency-Key`를 쓴다. 계약(`docs/batch/admin-api.md`)이 그렇게 정의돼 있다.
```

- [ ] **Step 2: API 문서 작성**

`docs/api/batch-runner.md`:

```markdown
# 배치 Runner API (S1)

- 구현: 2026-09-20 (S1). 계약 원본: [docs/batch/admin-api.md](../batch/admin-api.md), [docs/batch/runner-api.md](../batch/runner-api.md). 설계: [ADR-0019](../adr/0019-batch-library-boundary.md).
- 이 문서는 **구현된 범위만** 다룬다. Program·설치·Job·Run·배정(`claim`/`start`/`completion` 등)·`GET /assignments`는 후속 조각(S2~S5)이라 아직 없다. 따라서 실제 runner의 `run` 루프(세션 개설 후 `GET /assignments?state=UNRESOLVED` 복구와 설치 보고)는 S3 이후에 검증한다.

## 1. 관리자 API — `/api/v1.0/admin/batch/runners`

사용자 JWT와 `X-Menu-Id`(프로그램 `admin/batch/runner` 메뉴)를 쓴다([request-context.md](request-context.md)). 조회 READ, 생성·수정·폐기·토큰 발급 WRTE, 삭제 DELT. 등록 토큰 발급과 폐기는 추가로 `SYS_ADMIN` 역할이 필요하다. 오류는 `{code, message}`.

| Method / 경로 | 설명 | 성공 |
|---|---|---|
| `GET /` | 목록. query `code`, `name`, `status`, `page`, `pageSize`(기본 1/200). 정렬은 생성 시각·ID 내림차순 | 200 `Page` |
| `GET /{id}` | 상세 | 200 |
| `POST /` | 생성 `{code, name, capacity}`. `REGISTERING` 상태로 만든다 | 201 + `Location` |
| `PUT /{id}` | 전체 교체 `{code, name, capacity, status?}`. `If-Match: "{version}"` 필수 | 200 |
| `DELETE /{id}` | `REGISTERING`/`REVOKED`에서만. credential은 함께 삭제되고 event는 남는다 | 204 |
| `POST /{id}/enrollment` | 등록 토큰 발급(10분, 1회용, `no-store`). `REGISTERING`/`REVOKED`에서만 | 201 `{credentialId, enrollmentToken, expiresAt}` |
| `POST /{id}/revoke` | `{reason}`. 모든 자격증명 폐기, `REVOKED` | 200 |

- 쓰기 명령(등록 토큰 발급 제외)은 `Idempotency-Key`(1~200자 ASCII)와 `X-Request-Created-At`(UTC `Z`, 소수 6자리 이하)가 필수다. 같은 key·같은 요청은 최초 응답(상태·본문·Location)을 재현하고(72시간), 같은 key에 다른 요청은 409 `batch.idempotency.conflict`, 기록이 없는 key의 생성 시각이 서버 시각과 5분 넘게 어긋나면 410 `batch.request.expired`다.
- `code`는 `[A-Za-z0-9][A-Za-z0-9._-]{0,19}`, `capacity`는 1~200. `code`는 수정할 수 없다. `status`는 선택이며 지정하면 `ACTIVE`/`PAUSED`만, 현재 상태도 `ACTIVE`/`PAUSED`여야 한다(아니면 409 `batch.state.conflict`).
- 응답 필드: `id, code, name, capacity, status, hostname, os, agentVersion, lastSeenAt, online, session, version`. `session`·`version`은 십진 문자열, `online`은 `lastSeenAt`이 30초 이내인지다. 알 수 없는 요청 필드는 400.
- `version`은 등록·폐기·재발급(`REVOKED`→`REGISTERING`)·PUT에서 올라가고 heartbeat·세션 개설로는 올라가지 않는다.
- 요청 본문은 UTF-8 기준 1 MiB 이하여야 한다. 초과는 413 `batch.payload.too_large`이며 Content-Length가 없거나 거짓이어도 실제로 읽은 byte 수로 판정한다. 처리 순서는 인증(401) → 본문 한도(413) → 메뉴 권한(403) → 요청 검증(400)이다.
- 정수 필드는 JSON 정수, 문자열 필드는 JSON 문자열만 받는다. `1.9`, `"1"`, `1`을 다른 타입으로 조용히 바꾸지 않고 400이다.

| 상태 | code |
|---|---|
| 400 | `batch.request.invalid` |
| 404 | `batch.runner.not_found` |
| 409 | `batch.runner.duplicate_code`, `batch.state.conflict`, `batch.idempotency.conflict` |
| 410 | `batch.request.expired` |
| 413 | `batch.payload.too_large` |
| 412 / 428 | `batch.version.conflict` / `batch.version.required` |

## 2. Runner API — `/api/v1.0/batch-agent`

runner 전용 보안 체인이 처리한다(사용자 JWT는 401, runner 토큰은 사용자 API에서 401). `Authorization: Bearer {credentialId}.{secret}`, 모든 요청에 `X-Protocol-Version: 1`(아니면 409 `batch.protocol.unsupported`). 모든 응답은 `Cache-Control: no-store`. 시각은 UTC `Z`, bigint는 십진 문자열.

| Method / 경로 | 자격증명 | 설명 |
|---|---|---|
| `POST /registrations` | ENROLLMENT | `{runnerCode, agentVersion, hostname, os(LINUX/WINDOWS), architecture(AMD64/ARM64)}` → 201 `{runnerId, credentialId, accessToken, tokenExpiresAt, session}`. 토큰 원자 소비, runner `ACTIVE` |
| `POST /sessions` | ACCESS | `{bootId(UUID), expectedSession, agentVersion}` → 200 `{runnerId, session, serverTime, heartbeatSeconds, pollSeconds, leaseSeconds, capacity, limits}`. 같은 `bootId`는 같은 세대를 반환하고, 새 `bootId`는 `expectedSession`이 현재 세대와 같을 때만 세대를 올린다 |
| `POST /heartbeat` | ACCESS | 헤더 `X-Runner-Session`. `{observedAt, mode, freeSlots, assignments}` → 200 `{serverTime, acceptingAssignments, assignments}`. `last_seen`만 갱신한다 |

- 자격증명 종류가 맞지 않으면 403 `batch.runner.forbidden`, 없거나 폐기·만료되면 401 `batch.credential.invalid`. 낡은 세션은 409 `batch.session.stale`.
- 요청 본문 1 MiB 초과는 413 `batch.payload.too_large`. 처리 순서는 인증(401/403) → 본문 한도(413) → 프로토콜 버전(409) → 요청 검증(400)이다. 정수·문자열 필드의 JSON 타입이 다르면(예: `expectedSession`을 숫자로 보냄) 400이다.
- **S1 한계**: 배정 테이블이 없으므로 heartbeat에 보고된 assignment id는 모두 `RECONCILE`로 응답한다. S3에서 실제 조회로 대체한다.
- 등록 응답을 잃으면 토큰을 재조회할 수 없다. 관리자가 새 등록 토큰을 발급한다(미사용 이전 토큰은 자동 폐기).
```

`docs/api/README.md`의 목차 표에서 `session.md` 행 바로 아래에 다음 행을 추가한다(정확한 기존 행: `| [session.md](session.md) | 로그인한 사용자의 메뉴 트리 조회, 메뉴 단위 화면(Pragma) 조각 서빙 |`).

```markdown
| [batch-runner.md](batch-runner.md) | 배치 Runner 관리(관리자)와 Runner 등록·세션·heartbeat(runner 전용 API) — S1 |
```

- [ ] **Step 3: CLAUDE.md 갱신**

`CLAUDE.md`를 읽고 다음 세 곳을 수정한다.

(a) Package structure의 트리에서 아래 줄을 찾아

```
├─ api              — controllers stay flat (not split into subpackages)
```

바로 위에 다음 두 줄을 추가한다.

```
├─ app.batch        — batch (single feature package built for future library extraction, depends on core only,
│                     ADR-0019): enums, exceptions, config (runner-API SecurityFilterChain), models, mapper, service
```

(b) 같은 트리의 `│   ├─ CodeController (/api/v1.0/code), MenuController (/api/v1.0/menu)` 줄 아래에 다음 줄을 추가한다.

```
│   ├─ BatchAgentController (/api/v1.0/batch-agent, runner-only auth), BatchApiSupport (shared handlers)
```

그리고 `└─ AdminAuthorityController, AdminUserController` 목록이 있는 admin 항목 끝(`AdminUserController` 뒤)에 `, AdminBatchRunnerController (/api/v1.0/admin/batch/runners)`를 덧붙인다.

(c) `## Scope notes` 섹션의 마지막 항목 뒤(파일 끝)에 다음 항목을 추가한다.

```markdown
- Batch (`kkdugi.app.batch`, S1 implemented 2026-09-20; design in
  [docs/batch/](docs/batch/README.md), [S1 spec](docs/superpowers/specs/2026-09-20-batch-s1-runner-registration-design.md),
  [ADR-0019](docs/adr/0019-batch-library-boundary.md)) is built to be extracted into a library later, so it
  deliberately differs from the conventions above: one feature package instead of `app.<feature>`/`app.admin.<feature>`;
  its enums live in `kkdugi.app.batch.enums` (not `core.enums`); the runner API
  (`/api/v1.0/batch-agent/**`) is authenticated by a batch-owned `SecurityFilterChain` with runner tokens (never touch
  `core.security` for it, and never register that filter as a bean); errors are one `BatchException(status, code)`;
  message keys `batch.*` go in `messages*.properties`; request DTO scalars are type-strict (`BatchStrict*`
  deserializers), bodies are capped at 1 MiB by a batch-owned servlet filter, and validity checks use
  `clock_timestamp()` (not `now()`). Batch admin commands take `Idempotency-Key` +
  `X-Request-Created-At` and `If-Match`, and every runner state change locks the runner row first. Only the runner
  registration layer exists so far (runner CRUD, enrollment, register/session/heartbeat) — programs, jobs, runs,
  assignments, schedules are later slices (S2~S5).
```

- [ ] **Step 4: spec 갱신**

`docs/superpowers/specs/2026-09-20-batch-s1-runner-registration-design.md`에서 §3의 `exceptions` 줄

```
├─ exceptions  Batch{Validation,Conflict,NotFound,Credential,Forbidden,...}Exception (code = batch.*)
```

을 다음으로 바꾼다.

```
├─ exceptions  BatchException(status, code) + BatchErrors (code = batch.*)
```

§3의 "예외는 기존 admin 컨트롤러처럼 …" 항목 끝에 `(구현: 두 컨트롤러의 공통 부모 BatchApiSupport에 핸들러를 둔다.)`를 덧붙이고, §11 마지막 줄(`- program 식별자 …제안이다.`) 뒤에 다음을 추가한다.

```markdown
- 구현 계획에서 확정한 세부: PUT의 `status`는 선택(생략하면 유지, 지정하면 `ACTIVE`/`PAUSED`이고 현재 상태도 `ACTIVE`/`PAUSED`여야 함). `config_ver`는 등록·폐기·재발급(`REVOKED`→`REGISTERING`)·PUT에서 올라가고 heartbeat·세션 개설에서는 올라가지 않는다. 예외는 단일 `BatchException`이다. `kkdugi_batch_event.run_id`/`attempt_no`는 컬럼만 두고 S3에서 FK를 추가한다.
```

- [ ] **Step 5: 개발용 메뉴 seed 작성**

`scripts/dev/batch-menu-seed.sql` (migration이 아니다 — 제품용 메뉴 seed는 별도 단계). SYS_ADMIN은 로그인 시점에 전체 메뉴·전체 RBAC를 받으므로(`KkdugiUserDetailsService`) 메뉴 행만 있으면 관리자 API를 호출할 수 있다.

```sql
-- 개발/수동 검증 전용: 배치 Runner 관리 메뉴(admin/batch/runner)를 만든다. Flyway migration이 아니다.
-- 실행: docker-compose exec -T postgres psql -U kkdugi_dev -d kkdugi_dev < scripts/dev/batch-menu-seed.sql
-- SYS_ADMIN은 로그인 시점에 전체 메뉴+전체 RBAC를 받으므로 kkdugi_auth_menu 행은 넣지 않는다(V10과 같은 규칙).
-- 여러 번 실행해도 안전하다. 만든 menu_id는 마지막 SELECT로 확인해 X-Menu-Id 헤더에 쓴다.
DO $$
DECLARE
    v_key  VARCHAR := to_char(CURRENT_TIMESTAMP, 'YYYYMMDDHH24MI');
    v_menu VARCHAR := 'M' || v_key || lpad(fn_get_serial('KKDUGI_MENU', v_key, 1)::text, 4, '0');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM kkdugi_menu_base WHERE menu_pgm = 'admin/batch/runner') THEN
        INSERT INTO kkdugi_menu_base (menu_id, menu_parent_id, menu_pgm, menu_lvl, menu_path, sort_seq, reg_id)
        VALUES (v_menu, NULL, 'admin/batch/runner', 0, '/' || v_menu, 90, 'SYSTEM');
        -- 세션 메뉴 조회가 kkdugi_menu_lang을 INNER JOIN하므로 등록된 언어를 모두 넣는다.
        INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES
            (v_menu, 'ko_KR', '배치 Runner', 'SYSTEM'),
            (v_menu, 'en_US', 'Batch Runners', 'SYSTEM'),
            (v_menu, 'jp_JA', 'バッチRunner', 'SYSTEM');
    END IF;
END $$;

SELECT menu_id, menu_pgm FROM kkdugi_menu_base WHERE menu_pgm = 'admin/batch/runner';
```

- [ ] **Step 6: 전체 테스트**

Run(저장소 루트에서 `docker-compose up -d` 확인 후):

```bash
./mvnw.cmd -B -ntp test
node --test src/test/js/*.test.mjs
```

Expected: 둘 다 PASS. Java 쪽은 기존 테스트 + 배치 테스트(`BatchSchemaTest`, `BatchEnumsTest`, `BatchTimeTest`, `BatchMessagesTest`, `BatchRunnerMapperTest`, `BatchCredentialMapperTest`, `BatchTokenServiceTest`, `BatchEventServiceTest`, `BatchRunnerServiceTest`, `BatchEnrollmentServiceTest`, `BatchRequestKeysTest`, `BatchIdempotencyServiceTest`, `BatchStrictJsonTest`, `BatchAgentJsonTest`, `BatchBodyLimitFilterTest`, `BatchAgentServiceTest`, `BatchAgentApiTest`, `AdminBatchRunnerControllerTest`, `BatchRunnerConcurrencyTest`)가 모두 통과해야 한다. 기존 테스트는 **Task 1 Step 0에서 기록한 베이스라인과 비교**한다. 베이스라인에 없던 실패만 이 작업의 회귀로 보고 `superpowers:systematic-debugging`으로 원인을 찾는다(예: `SecurityCheckerApiTest`가 새로 실패하면 `BatchAgentSecurityConfig`가 기존 체인에 영향을 준 것이다). 베이스라인의 기존 실패는 고치지 말고 결과 보고에 그대로 적는다.

- [ ] **Step 7: 실제 runner 등록 확인 (수동)**

runner는 TLS 검증을 끌 수 없으므로 사설 CA로 로컬 HTTPS를 구성한다(임시 파일은 저장소 밖 `C:/tmp`에 둔다).

1. 인증서 생성(Git Bash):

```bash
mkdir -p /c/tmp/kkdugi-tls && cd /c/tmp/kkdugi-tls
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 30 -subj "/CN=kkdugi-dev-ca"
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=localhost"
printf "subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n" > ext.cnf
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days 30 -extfile ext.cnf
openssl pkcs12 -export -in server.pem -inkey server.key -certfile ca.pem -out server.p12 -name kkdugi -passout pass:changeit
```

2. 개발 메뉴 seed 적용 후 menu_id 확인:

```bash
cd /c/projects/kkdugi
docker-compose exec -T postgres psql -U kkdugi_dev -d kkdugi_dev < scripts/dev/batch-menu-seed.sql
```

출력의 `menu_id`를 `MENU_ID`로 기억한다.

3. admin을 HTTPS로 기동(별도 터미널, `kkdugi-admin/`에서):

```bash
./mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8443 --server.ssl.enabled=true --server.ssl.key-store=file:C:/tmp/kkdugi-tls/server.p12 --server.ssl.key-store-password=changeit --server.ssl.key-store-type=PKCS12"
```

4. 관리자로 로그인해 Runner를 만들고 등록 토큰을 발급한다(개발 계정 `admin`/`admin1234`, [auth.md](../../docs/api/auth.md) — 비밀번호 변경을 요구하면 그 문서의 `passwordAction` 절차를 따른다).

```bash
CA=C:/tmp/kkdugi-tls/ca.pem; API=https://localhost:8443/api/v1.0
curl --cacert $CA -c /tmp/kk.jar -d "username=admin&password=admin1234" $API/auth/login
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl --cacert $CA -b /tmp/kk.jar -H "X-Menu-Id: $MENU_ID" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)" -H "X-Request-Created-At: $NOW" \
  -d '{"code":"worker-01","name":"Worker 01","capacity":1}' $API/admin/batch/runners
# 응답의 id를 RUNNER_ID로 사용
curl --cacert $CA -b /tmp/kk.jar -H "X-Menu-Id: $MENU_ID" -X POST $API/admin/batch/runners/$RUNNER_ID/enrollment
# 응답의 enrollmentToken을 복사 (10분 안에 사용)
```

5. runner 설정 `C:/tmp/kkdugi-runner-dev/runner.toml`을 만들고 등록한다(runner 릴리스의 Windows 바이너리 사용).

```toml
data_dir = 'C:/tmp/kkdugi-runner-dev/data'
capacity = 1

[admin]
base_url = 'https://localhost:8443/api/v1.0/batch-agent'
runner_code = 'worker-01'
credential_dir = 'C:/tmp/kkdugi-runner-dev/data/credentials'
ca_file = 'C:/tmp/kkdugi-tls/ca.pem'
```

```bash
cd /c/projects/kkdugi/kkdugi-runner/bin/releases/0.1.0-preview.2/kkdugi-runner-0.1.0-preview.2-windows-amd64
echo "<enrollmentToken>" | ./kkdugi-runner.exe register --config C:/tmp/kkdugi-runner-dev/runner.toml
```

Expected: `runner registered; credential saved`.

6. DB에서 확인:

```bash
docker-compose exec -T postgres psql -U kkdugi_dev -d kkdugi_dev -c "SELECT runner_cd, runner_stat, host_nm, os_cd, agent_ver, session_ver FROM kkdugi_batch_runner WHERE runner_cd='worker-01'"
docker-compose exec -T postgres psql -U kkdugi_dev -d kkdugi_dev -c "SELECT credential_type, consumed_dtm IS NOT NULL AS consumed, revoked_dtm IS NOT NULL AS revoked FROM kkdugi_batch_runner_credential c JOIN kkdugi_batch_runner r USING (runner_id) WHERE r.runner_cd='worker-01' ORDER BY c.credential_type"
docker-compose exec -T postgres psql -U kkdugi_dev -d kkdugi_dev -c "SELECT event_type, actor_type FROM kkdugi_batch_event e JOIN kkdugi_batch_runner r ON r.runner_id = e.target_id WHERE r.runner_cd='worker-01' ORDER BY e.occurred_dtm"
```

Expected: runner `ACTIVE`, `host_nm`/`os_cd`/`agent_ver` 채워짐, `ACCESS`(미소비·미폐기)와 `ENROLLMENT`(소비됨) 각 1행, event는 `CREATED`, `ENROLLMENT_ISSUED`, `REGISTERED`. 검증이 끝나면 테스트 runner를 정리한다(`POST .../revoke` 후 `DELETE`) 또는 dev DB에서 그대로 두어도 된다. **이 수동 검증은 `run` 루프를 검증하지 않는다**(S3에서 수행).

- [ ] **Step 8: Commit**

```bash
git add docs/adr/0019-batch-library-boundary.md docs/api/batch-runner.md docs/api/README.md CLAUDE.md docs/superpowers/specs/2026-09-20-batch-s1-runner-registration-design.md docs/superpowers/plans/2026-09-20-batch-s1-runner-registration.md scripts/dev/batch-menu-seed.sql
git commit -m "docs(batch): add ADR-0019, S1 API doc, dev menu seed and plan" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## S1 완료 기준 (spec §10)

- [ ] V13 migration 적용, `./mvnw.cmd -B -ntp test` 전체 통과 (Task 11 Step 6)
- [ ] register CLI로 실제 admin에 등록 확인 (Task 11 Step 7)
- [ ] ADR-0019와 CLAUDE.md 갱신 (Task 11 Step 1, 3)
- [ ] `docs/api/batch-runner.md` 추가 (Task 11 Step 2)
- [ ] 후속 조각 인계: S2(Program·설치 보고/승인, `RUNNER` 멱등 주체, `EventTargetType` 확장), S3(`GET /assignments`, heartbeat의 실제 assignment 조회, capacity 축소·삭제·재등록 토큰 발급의 미해결 실행 검사, event FK)
