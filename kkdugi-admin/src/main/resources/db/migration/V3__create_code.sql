CREATE TABLE kkdugi_code_base (
    code_id        VARCHAR(60)   NOT NULL,
    code_parent_id VARCHAR(60),
    code_val       VARCHAR(100)  NOT NULL,
    etc_val1       VARCHAR(1000),
    etc_val2       VARCHAR(1000),
    etc_val3       VARCHAR(1000),
    etc_val4       VARCHAR(1000),
    etc_val5       VARCHAR(1000),
    code_lvl       INTEGER,
    code_path      VARCHAR(500),
    sort_seq       INTEGER,
    use_yn         CHAR(1) NOT NULL DEFAULT 'Y',
    reg_dtm        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reg_id         VARCHAR(60) NOT NULL,
    upd_dtm        TIMESTAMP,
    upd_id         VARCHAR(60),
    CONSTRAINT kkdugi_code_base_pk PRIMARY KEY (code_id),
    CONSTRAINT kkdugi_code_base_fk FOREIGN KEY (code_parent_id) REFERENCES kkdugi_code_base (code_id),
    CONSTRAINT kkdugi_code_base_use_yn_chk CHECK (use_yn IN ('Y', 'N')),
    CONSTRAINT kkdugi_code_base_udx_parent_val UNIQUE (code_parent_id, code_val)
);

-- Postgres의 일반 UNIQUE 제약은 NULL끼리 서로 다른 값으로 취급하므로,
-- 위 kkdugi_code_base_udx_parent_val만으로는 code_parent_id가 NULL인
-- 최상위(root) 코드끼리는 같은 code_val이 중복돼도 걸러지지 않는다.
-- root 코드의 code_val 중복도 막기 위한 부분 유니크 인덱스.
CREATE UNIQUE INDEX kkdugi_code_base_udx_root_val
    ON kkdugi_code_base (code_val)
    WHERE code_parent_id IS NULL;

COMMENT ON TABLE kkdugi_code_base IS '코드';
COMMENT ON COLUMN kkdugi_code_base.code_id IS '코드 ID';
COMMENT ON COLUMN kkdugi_code_base.code_parent_id IS '상위 코드 ID (ROOT인 경우 NULL)';
COMMENT ON COLUMN kkdugi_code_base.code_val IS '코드 값';
COMMENT ON COLUMN kkdugi_code_base.etc_val1 IS '추가 값 1';
COMMENT ON COLUMN kkdugi_code_base.etc_val2 IS '추가 값 2';
COMMENT ON COLUMN kkdugi_code_base.etc_val3 IS '추가 값 3';
COMMENT ON COLUMN kkdugi_code_base.etc_val4 IS '추가 값 4';
COMMENT ON COLUMN kkdugi_code_base.etc_val5 IS '추가 값 5';
COMMENT ON COLUMN kkdugi_code_base.code_lvl IS '코드 Hierarchy level (root = 0)';
COMMENT ON COLUMN kkdugi_code_base.code_path IS '코드 Hierarchy path - code_val을 ''/''로 이어붙인 값 (예: /SYS/USER/STAT)';
COMMENT ON COLUMN kkdugi_code_base.sort_seq IS '정렬 순번';
COMMENT ON COLUMN kkdugi_code_base.use_yn IS '사용 여부';
COMMENT ON COLUMN kkdugi_code_base.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_code_base.reg_id IS '등록자 ID';
COMMENT ON COLUMN kkdugi_code_base.upd_dtm IS '수정일시';
COMMENT ON COLUMN kkdugi_code_base.upd_id IS '수정자 ID';

CREATE TABLE kkdugi_code_lang (
    code_id VARCHAR(60)  NOT NULL,
    lang_cd VARCHAR(20)  NOT NULL,
    code_nm VARCHAR(200) NOT NULL,
    code_dc VARCHAR(1000),
    reg_dtm TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reg_id  VARCHAR(60) NOT NULL,
    upd_dtm TIMESTAMP,
    upd_id  VARCHAR(60),
    CONSTRAINT kkdugi_code_lang_pk PRIMARY KEY (code_id, lang_cd),
    CONSTRAINT kkdugi_code_lang_fk FOREIGN KEY (code_id) REFERENCES kkdugi_code_base (code_id)
);

COMMENT ON TABLE kkdugi_code_lang IS '코드 언어';
COMMENT ON COLUMN kkdugi_code_lang.code_id IS '코드 ID';
COMMENT ON COLUMN kkdugi_code_lang.lang_cd IS '언어 코드 (공통코드 /SYS/LANG)';
COMMENT ON COLUMN kkdugi_code_lang.code_nm IS '코드 이름';
COMMENT ON COLUMN kkdugi_code_lang.code_dc IS '코드 설명';
COMMENT ON COLUMN kkdugi_code_lang.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_code_lang.reg_id IS '등록자 ID';
COMMENT ON COLUMN kkdugi_code_lang.upd_dtm IS '수정일시';
COMMENT ON COLUMN kkdugi_code_lang.upd_id IS '수정자 ID';
