CREATE TABLE kkdugi_auth_base (
    auth_id      VARCHAR(60)  NOT NULL,
    auth_role_cd VARCHAR(60)  NOT NULL,
    auth_tp_cd   VARCHAR(20)  NOT NULL,
    auth_nm      VARCHAR(200) NOT NULL,
    auth_dc      VARCHAR(1000),
    use_yn       CHAR(1) NOT NULL DEFAULT 'Y',
    reg_dtm      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reg_id       VARCHAR(60) NOT NULL,
    upd_dtm      TIMESTAMP,
    upd_id       VARCHAR(60),
    CONSTRAINT kkdugi_auth_base_pk PRIMARY KEY (auth_id),
    CONSTRAINT kkdugi_auth_base_use_yn_chk CHECK (use_yn IN ('Y', 'N')),
    -- kkdugi.core.enums.AuthorityType과 맞춘다.
    CONSTRAINT kkdugi_auth_base_tp_chk CHECK (auth_tp_cd IN ('ROLE', 'PLAN'))
);

COMMENT ON TABLE kkdugi_auth_base IS '권한';
COMMENT ON COLUMN kkdugi_auth_base.auth_id IS '권한 ID';
COMMENT ON COLUMN kkdugi_auth_base.auth_role_cd IS '권한 ROLE 코드';
COMMENT ON COLUMN kkdugi_auth_base.auth_tp_cd IS '권한 유형 코드 (kkdugi.core.enums.AuthorityType)';
COMMENT ON COLUMN kkdugi_auth_base.auth_nm IS '권한 이름';
COMMENT ON COLUMN kkdugi_auth_base.auth_dc IS '권한 설명';
COMMENT ON COLUMN kkdugi_auth_base.use_yn IS '사용 여부';
COMMENT ON COLUMN kkdugi_auth_base.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_auth_base.reg_id IS '등록자 ID';
COMMENT ON COLUMN kkdugi_auth_base.upd_dtm IS '수정일시';
COMMENT ON COLUMN kkdugi_auth_base.upd_id IS '수정자 ID';

CREATE TABLE kkdugi_user_auth (
    user_id    VARCHAR(60) NOT NULL,
    auth_id    VARCHAR(60) NOT NULL,
    apl_st_dtm DATE NOT NULL,
    apl_ed_dtm DATE NOT NULL,
    reg_dtm    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reg_id     VARCHAR(60) NOT NULL,
    upd_dtm    TIMESTAMP,
    upd_id     VARCHAR(60),
    CONSTRAINT kkdugi_user_auth_pk PRIMARY KEY (user_id, auth_id),
    CONSTRAINT kkdugi_user_auth_user_fk FOREIGN KEY (user_id) REFERENCES kkdugi_user_base (user_id),
    CONSTRAINT kkdugi_user_auth_auth_fk FOREIGN KEY (auth_id) REFERENCES kkdugi_auth_base (auth_id),
    CONSTRAINT kkdugi_user_auth_period_chk CHECK (apl_ed_dtm >= apl_st_dtm)
);

COMMENT ON TABLE kkdugi_user_auth IS '사용자 권한';
COMMENT ON COLUMN kkdugi_user_auth.user_id IS '사용자 ID (시스템 생성)';
COMMENT ON COLUMN kkdugi_user_auth.auth_id IS '권한 ID';
COMMENT ON COLUMN kkdugi_user_auth.apl_st_dtm IS '적용 시작 일시';
COMMENT ON COLUMN kkdugi_user_auth.apl_ed_dtm IS '적용 종료 일시';
COMMENT ON COLUMN kkdugi_user_auth.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_user_auth.reg_id IS '등록자 ID';
COMMENT ON COLUMN kkdugi_user_auth.upd_dtm IS '수정일시';
COMMENT ON COLUMN kkdugi_user_auth.upd_id IS '수정자 ID';
