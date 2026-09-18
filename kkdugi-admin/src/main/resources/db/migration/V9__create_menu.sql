CREATE TABLE kkdugi_menu_base (
    menu_id        VARCHAR(60)  NOT NULL,
    menu_parent_id VARCHAR(60),
    menu_ico       VARCHAR(200),
    menu_pgm       VARCHAR(100),
    menu_lvl       INTEGER,
    menu_path      VARCHAR(500),
    sort_seq       INTEGER,
    use_yn         CHAR(1) NOT NULL DEFAULT 'Y',
    close_yn       CHAR(1) NOT NULL DEFAULT 'Y',
    reg_dtm        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reg_id         VARCHAR(60) NOT NULL,
    upd_dtm        TIMESTAMP,
    upd_id         VARCHAR(60),
    CONSTRAINT kkdugi_menu_base_pk PRIMARY KEY (menu_id),
    CONSTRAINT kkdugi_menu_base_fk FOREIGN KEY (menu_parent_id) REFERENCES kkdugi_menu_base (menu_id),
    CONSTRAINT kkdugi_menu_base_use_yn_chk CHECK (use_yn IN ('Y', 'N')),
    CONSTRAINT kkdugi_menu_base_close_yn_chk CHECK (close_yn IN ('Y', 'N'))
);

COMMENT ON TABLE kkdugi_menu_base IS '메뉴';
COMMENT ON COLUMN kkdugi_menu_base.menu_id IS '메뉴 ID';
COMMENT ON COLUMN kkdugi_menu_base.menu_parent_id IS '상위 메뉴 ID (Root는 NULL)';
COMMENT ON COLUMN kkdugi_menu_base.menu_ico IS '메뉴 아이콘 (반드시 full class name)';
COMMENT ON COLUMN kkdugi_menu_base.menu_pgm IS '메뉴 프로그램 ID';
COMMENT ON COLUMN kkdugi_menu_base.menu_lvl IS '메뉴 Hierarchy level (시스템 관리, root = 0)';
COMMENT ON COLUMN kkdugi_menu_base.menu_path IS '메뉴 Hierarchy path (시스템 관리)';
COMMENT ON COLUMN kkdugi_menu_base.sort_seq IS '정렬 순번';
COMMENT ON COLUMN kkdugi_menu_base.use_yn IS '사용 여부';
COMMENT ON COLUMN kkdugi_menu_base.close_yn IS '탭 UI인 경우 닫을 수 있는지 여부';
COMMENT ON COLUMN kkdugi_menu_base.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_menu_base.reg_id IS '등록자 ID';
COMMENT ON COLUMN kkdugi_menu_base.upd_dtm IS '수정일시';
COMMENT ON COLUMN kkdugi_menu_base.upd_id IS '수정자 ID';

CREATE TABLE kkdugi_menu_lang (
    menu_id VARCHAR(60)  NOT NULL,
    lang_cd VARCHAR(20)  NOT NULL,
    menu_nm VARCHAR(200) NOT NULL,
    menu_dc VARCHAR(1000),
    reg_dtm TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reg_id  VARCHAR(60) NOT NULL,
    upd_dtm TIMESTAMP,
    upd_id  VARCHAR(60),
    CONSTRAINT kkdugi_menu_lang_pk PRIMARY KEY (menu_id, lang_cd),
    CONSTRAINT kkdugi_menu_lang_fk FOREIGN KEY (menu_id) REFERENCES kkdugi_menu_base (menu_id)
);

COMMENT ON TABLE kkdugi_menu_lang IS '메뉴 언어';
COMMENT ON COLUMN kkdugi_menu_lang.menu_id IS '메뉴 ID';
COMMENT ON COLUMN kkdugi_menu_lang.lang_cd IS '언어 코드 (공통코드 /SYS/LANG)';
COMMENT ON COLUMN kkdugi_menu_lang.menu_nm IS '메뉴 이름';
COMMENT ON COLUMN kkdugi_menu_lang.menu_dc IS '메뉴 설명';
COMMENT ON COLUMN kkdugi_menu_lang.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_menu_lang.reg_id IS '등록자 ID';
COMMENT ON COLUMN kkdugi_menu_lang.upd_dtm IS '수정일시';
COMMENT ON COLUMN kkdugi_menu_lang.upd_id IS '수정자 ID';

CREATE TABLE kkdugi_auth_menu (
    auth_id  VARCHAR(60) NOT NULL,
    menu_id  VARCHAR(60) NOT NULL,
    auth_val INTEGER NOT NULL,
    reg_dtm  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reg_id   VARCHAR(60) NOT NULL,
    upd_dtm  TIMESTAMP,
    upd_id   VARCHAR(60),
    CONSTRAINT kkdugi_auth_menu_pk PRIMARY KEY (auth_id, menu_id),
    CONSTRAINT kkdugi_auth_menu_auth_fk FOREIGN KEY (auth_id) REFERENCES kkdugi_auth_base (auth_id),
    CONSTRAINT kkdugi_auth_menu_menu_fk FOREIGN KEY (menu_id) REFERENCES kkdugi_menu_base (menu_id)
);

COMMENT ON TABLE kkdugi_auth_menu IS '권한 메뉴';
COMMENT ON COLUMN kkdugi_auth_menu.auth_id IS '권한 ID';
COMMENT ON COLUMN kkdugi_auth_menu.menu_id IS '메뉴 ID';
COMMENT ON COLUMN kkdugi_auth_menu.auth_val IS '권한 값 (비트마스크, kkdugi.core.enums.Rbac)';
COMMENT ON COLUMN kkdugi_auth_menu.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_auth_menu.reg_id IS '등록자 ID';
COMMENT ON COLUMN kkdugi_auth_menu.upd_dtm IS '수정일시';
COMMENT ON COLUMN kkdugi_auth_menu.upd_id IS '수정자 ID';
