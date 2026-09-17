CREATE TABLE kkdugi_serial_base (
    serial_id VARCHAR(60)  NOT NULL,
    seq_base  VARCHAR(60)  NOT NULL,
    seq_val   INTEGER      NOT NULL DEFAULT 0,
    upd_dtm   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT kkdugi_serial_base_pk PRIMARY KEY (serial_id)
);

COMMENT ON TABLE kkdugi_serial_base IS '일련번호';
COMMENT ON COLUMN kkdugi_serial_base.serial_id IS '일련번호 ID (채번 기준 문자열 그 자체 - 접두어+초 단위 타임스탬프)';
COMMENT ON COLUMN kkdugi_serial_base.seq_base IS '일련번호 채번 기준 (접두어, 예: C/M/A/U)';
COMMENT ON COLUMN kkdugi_serial_base.seq_val IS '일련번호 값 (같은 serial_id로 호출될 때마다 증가)';
COMMENT ON COLUMN kkdugi_serial_base.upd_dtm IS '수정일시';
