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
