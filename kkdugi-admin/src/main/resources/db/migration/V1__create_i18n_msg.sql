CREATE TABLE kkdugi_i18n_msg (
    msg_cd  VARCHAR(200) NOT NULL,
    lang_cd VARCHAR(20)  NOT NULL,
    msg_val VARCHAR(1000),
    reg_dtm TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reg_id  VARCHAR(60) NOT NULL,
    upd_dtm TIMESTAMP,
    upd_id  VARCHAR(60),
    CONSTRAINT kkdugi_i18n_msg_pk PRIMARY KEY (msg_cd, lang_cd)
);

COMMENT ON TABLE kkdugi_i18n_msg IS '다국어 메시지 코드';
COMMENT ON COLUMN kkdugi_i18n_msg.msg_cd IS '메시지 코드';
COMMENT ON COLUMN kkdugi_i18n_msg.lang_cd IS '언어 코드 (공통코드 /SYS/LANG)';
COMMENT ON COLUMN kkdugi_i18n_msg.msg_val IS '메시지';
COMMENT ON COLUMN kkdugi_i18n_msg.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_i18n_msg.reg_id IS '등록자 ID';
COMMENT ON COLUMN kkdugi_i18n_msg.upd_dtm IS '수정일시';
COMMENT ON COLUMN kkdugi_i18n_msg.upd_id IS '수정자 ID';
