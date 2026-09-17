-- 기본 관리자 계정 + SYS_ADMIN 권한을 만든다. ID는 애플리케이션 코드가 쓰는
-- 것과 동일한 fn_get_serial()로 채번해 형식을 맞춘다:
--   user_id -> U{yyyyMMddHHmm}{0000}  (SessionService의 KKDUGI_SESSION과
--              같은 방식, 여기서는 KKDUGI_USER 카운터)
--   auth_id -> A{yyyyMMddHHmm}{0000}  (KKDUGI_AUTH 카운터)
-- 두 INSERT가 같은 트랜잭션 안에서 실행되므로 CURRENT_TIMESTAMP/CURRENT_DATE는
-- 두 번 평가돼도 항상 같은 값이다(Postgres는 트랜잭션 시작 시각으로 고정).
--
-- 비밀번호는 admin1234를 BCryptPasswordEncoder로 미리 해시한 값이다. 이
-- 프로젝트의 PasswordEncoder는 PasswordEncoderFactories.createDelegatingPasswordEncoder()라
-- 저장값에 {bcrypt} 식별자 접두사가 반드시 있어야 한다. 개발용 기본 계정이니
-- 운영 배포 전 반드시 교체한다.
DO $$
DECLARE
    v_key     VARCHAR := to_char(CURRENT_TIMESTAMP, 'YYYYMMDDHH24MI');
    v_user_id VARCHAR := 'U' || v_key || lpad(fn_get_serial('KKDUGI_USER', v_key, 1)::text, 4, '0');
    v_auth_id VARCHAR := 'A' || v_key || lpad(fn_get_serial('KKDUGI_AUTH', v_key, 1)::text, 4, '0');
BEGIN
    INSERT INTO kkdugi_user_base (user_id, user_login_id, user_pwd, user_nm, user_email, user_stat_cd, reg_id)
    VALUES (v_user_id, 'admin', '{bcrypt}$2a$10$Sl7Rbe8fJKfcvLBQsTwmAOgiul2DN.5A3fsH57mBThciVLO/Ggw3.',
            '관리자', 'admin@kkdugi.local', '20', 'SYSTEM');

    INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id)
    VALUES (v_auth_id, 'SYS_ADMIN', 'ROLE', '시스템 관리자', 'SYSTEM');

    INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id)
    VALUES (v_user_id, v_auth_id, CURRENT_DATE, DATE '9999-12-31', 'SYSTEM');
END $$;
