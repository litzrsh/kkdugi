CREATE TABLE kkdugi_user_base (
    user_id          VARCHAR(60)   NOT NULL,
    user_login_id    VARCHAR(100)  NOT NULL,
    user_pwd         VARCHAR(200),
    user_nm          VARCHAR(200)  NOT NULL,
    user_dc          VARCHAR(1000),
    user_email       VARCHAR(200)  NOT NULL,
    user_img_src     VARCHAR(500),
    user_ci          VARCHAR(200),
    user_di          VARCHAR(200),
    last_login_dtm   TIMESTAMP,
    last_chg_pwd_dtm TIMESTAMP,
    pwd_stat_cd      VARCHAR(20),
    pwd_expr_dtm     TIMESTAMP,
    user_stat_cd     VARCHAR(20) NOT NULL,
    user_set_data    JSONB,
    reg_dtm          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reg_id           VARCHAR(60) NOT NULL,
    upd_dtm          TIMESTAMP,
    upd_id           VARCHAR(60),
    CONSTRAINT kkdugi_user_base_pk PRIMARY KEY (user_id),
    -- ERD에는 USER_LOGIN_ID에 유니크 제약이 없었지만, 로그인 조회
    -- (findByUsername)가 성립하려면 반드시 유니크해야 해서 추가했다
    -- (ADR-0014 참고).
    CONSTRAINT kkdugi_user_base_udx_login_id UNIQUE (user_login_id),
    CONSTRAINT kkdugi_user_base_udx_email UNIQUE (user_email),
    CONSTRAINT kkdugi_user_base_stat_chk CHECK (user_stat_cd IN ('10', '20', '30', '40', '50')),
    CONSTRAINT kkdugi_user_base_pwd_stat_chk CHECK (pwd_stat_cd IS NULL OR pwd_stat_cd IN ('10', '20', '30'))
);

COMMENT ON TABLE kkdugi_user_base IS '사용자';
COMMENT ON COLUMN kkdugi_user_base.user_id IS '사용자 ID (시스템 생성)';
COMMENT ON COLUMN kkdugi_user_base.user_login_id IS '사용자 로그인 ID';
COMMENT ON COLUMN kkdugi_user_base.user_pwd IS '사용자 비밀번호 (인코딩된 값)';
COMMENT ON COLUMN kkdugi_user_base.user_nm IS '사용자 이름';
COMMENT ON COLUMN kkdugi_user_base.user_dc IS '사용자 설명 (프로필)';
COMMENT ON COLUMN kkdugi_user_base.user_email IS '사용자 이메일주소';
COMMENT ON COLUMN kkdugi_user_base.user_img_src IS '사용자 프로필 이미지';
COMMENT ON COLUMN kkdugi_user_base.user_ci IS '사용자 CI';
COMMENT ON COLUMN kkdugi_user_base.user_di IS '사용자 DI';
COMMENT ON COLUMN kkdugi_user_base.last_login_dtm IS '마지막 로그인 일시';
COMMENT ON COLUMN kkdugi_user_base.last_chg_pwd_dtm IS '마지막 비밀번호 변경 일시';
COMMENT ON COLUMN kkdugi_user_base.pwd_stat_cd IS '비밀번호 상태 코드: 10 신규(변경 필요), 20 만료, 30 정상 (kkdugi.core.enums.PasswordStatus)';
COMMENT ON COLUMN kkdugi_user_base.pwd_expr_dtm IS '비밀번호 만료 일시';
COMMENT ON COLUMN kkdugi_user_base.user_stat_cd IS '사용자 상태 코드: 10 대기, 20 정상, 30 휴면, 40 탈퇴, 50 정지 (kkdugi.core.enums.UserStatus)';
COMMENT ON COLUMN kkdugi_user_base.user_set_data IS '사용자 설정 데이터 (JSON)';
COMMENT ON COLUMN kkdugi_user_base.reg_dtm IS '등록일시';
COMMENT ON COLUMN kkdugi_user_base.reg_id IS '등록자 ID';
COMMENT ON COLUMN kkdugi_user_base.upd_dtm IS '수정일시';
COMMENT ON COLUMN kkdugi_user_base.upd_id IS '수정자 ID';
