CREATE TABLE kkdugi_session (
    sess_id  VARCHAR(60) NOT NULL,
    user_id  VARCHAR(60) NOT NULL,
    user_dtl JSONB,
    reg_dtm  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    exp_dtm  TIMESTAMP NOT NULL,
    upd_dtm  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT kkdugi_session_pk PRIMARY KEY (sess_id),
    CONSTRAINT kkdugi_session_user_fk FOREIGN KEY (user_id) REFERENCES kkdugi_user_base (user_id)
);

-- allowMultiple=false일 때 로그인 시 기존 세션을 찾아 지우는 조회
-- (findByUserId/deleteByUserId)가 자주 실행되므로 인덱스를 둔다.
CREATE INDEX kkdugi_session_idx_user_id ON kkdugi_session (user_id);

COMMENT ON TABLE kkdugi_session IS '세션';
COMMENT ON COLUMN kkdugi_session.sess_id IS '세션 ID';
COMMENT ON COLUMN kkdugi_session.user_id IS '사용자 ID (시스템 생성)';
COMMENT ON COLUMN kkdugi_session.user_dtl IS '사용자 데이터 (JSON, SessionUser 직렬화)';
COMMENT ON COLUMN kkdugi_session.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_session.exp_dtm IS '만료일시';
COMMENT ON COLUMN kkdugi_session.upd_dtm IS '수정일시';
