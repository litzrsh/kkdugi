-- 기본 메뉴 트리를 만든다:
--   홈 (home)
--   시스템관리
--     공통코드관리 (admin/code)
--     메시지관리   (admin/message)
--     메뉴관리     (admin/menu)
--     권한관리     (admin/authority)
--     사용자관리   (admin/user)
-- 괄호 안은 menu_pgm(program) 값이다 — PragmaController가 templates/pragma/ 아래
-- 이 경로의 .vue 파일로 해석한다. 시스템관리는 폴더 노드라 program이 없다.
--
-- ID는 애플리케이션(MenuAdminService의 KKDUGI_MENU + "M%s%04d")이 쓰는 것과 같은
-- fn_get_serial()로 채번해 형식을 맞춘다: M{yyyyMMddHHmm}{0000}. menu_path/menu_lvl도
-- MenuAdminService.insertOne과 같은 규칙이다(root는 lvl 0, path는 "/상위ID/자기ID").
-- V8과 같이 한 트랜잭션 안에서 실행되므로 CURRENT_TIMESTAMP는 항상 같은 값이다.
--
-- SYS_ADMIN은 KkdugiUserDetailsService가 로그인 시점에 전체 메뉴를 채워주므로 여기서
-- kkdugi_auth_menu 권한 행을 따로 넣지 않는다.
--
-- kkdugi_menu_lang은 세션 메뉴 조회에서 INNER JOIN이라 로케일 행이 없는 언어로 로그인하면
-- 그 메뉴가 통째로 빠진다 — 그래서 application.yml에 등록된 두 언어(ko_KR, en_US)를 모두 넣는다.
DO $$
DECLARE
    v_key       VARCHAR := to_char(CURRENT_TIMESTAMP, 'YYYYMMDDHH24MI');
    v_home      VARCHAR := 'M' || v_key || lpad(fn_get_serial('KKDUGI_MENU', v_key, 1)::text, 4, '0');
    v_system    VARCHAR := 'M' || v_key || lpad(fn_get_serial('KKDUGI_MENU', v_key, 1)::text, 4, '0');
    v_code      VARCHAR := 'M' || v_key || lpad(fn_get_serial('KKDUGI_MENU', v_key, 1)::text, 4, '0');
    v_message   VARCHAR := 'M' || v_key || lpad(fn_get_serial('KKDUGI_MENU', v_key, 1)::text, 4, '0');
    v_menu      VARCHAR := 'M' || v_key || lpad(fn_get_serial('KKDUGI_MENU', v_key, 1)::text, 4, '0');
    v_authority VARCHAR := 'M' || v_key || lpad(fn_get_serial('KKDUGI_MENU', v_key, 1)::text, 4, '0');
    v_user      VARCHAR := 'M' || v_key || lpad(fn_get_serial('KKDUGI_MENU', v_key, 1)::text, 4, '0');
BEGIN
    INSERT INTO kkdugi_menu_base (menu_id, menu_parent_id, menu_pgm, menu_lvl, menu_path, sort_seq, reg_id) VALUES
        (v_home,      NULL,     'home',            0, '/' || v_home,                        1, 'SYSTEM'),
        (v_system,    NULL,     NULL,              0, '/' || v_system,                      2, 'SYSTEM'),
        (v_code,      v_system, 'admin/code',      1, '/' || v_system || '/' || v_code,      1, 'SYSTEM'),
        (v_message,   v_system, 'admin/message',   1, '/' || v_system || '/' || v_message,   2, 'SYSTEM'),
        (v_menu,      v_system, 'admin/menu',      1, '/' || v_system || '/' || v_menu,      3, 'SYSTEM'),
        (v_authority, v_system, 'admin/authority', 1, '/' || v_system || '/' || v_authority, 4, 'SYSTEM'),
        (v_user,      v_system, 'admin/user',      1, '/' || v_system || '/' || v_user,      5, 'SYSTEM');

    INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES
        (v_home,      'ko_KR', '홈',           'SYSTEM'),
        (v_home,      'en_US', 'Home',         'SYSTEM'),
        (v_system,    'ko_KR', '시스템관리',    'SYSTEM'),
        (v_system,    'en_US', 'System',       'SYSTEM'),
        (v_code,      'ko_KR', '공통코드관리',  'SYSTEM'),
        (v_code,      'en_US', 'Common Codes', 'SYSTEM'),
        (v_message,   'ko_KR', '메시지관리',    'SYSTEM'),
        (v_message,   'en_US', 'Messages',     'SYSTEM'),
        (v_menu,      'ko_KR', '메뉴관리',     'SYSTEM'),
        (v_menu,      'en_US', 'Menus',        'SYSTEM'),
        (v_authority, 'ko_KR', '권한관리',     'SYSTEM'),
        (v_authority, 'en_US', 'Authorities',  'SYSTEM'),
        (v_user,      'ko_KR', '사용자관리',    'SYSTEM'),
        (v_user,      'en_US', 'Users',        'SYSTEM');
END $$;
