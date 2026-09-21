-- 개발/수동 검증 전용: 배치 Runner 관리 메뉴(admin/batch/runner)를 만든다. Flyway migration이 아니다.
-- 실행: docker-compose exec -T postgres psql -U kkdugi_dev -d kkdugi_dev < scripts/dev/batch-menu-seed.sql
-- SYS_ADMIN은 로그인 시점에 전체 메뉴+전체 RBAC를 받으므로 kkdugi_auth_menu 행은 넣지 않는다(V10과 같은 규칙).
-- 여러 번 실행해도 안전하다. 만든 menu_id는 마지막 SELECT로 확인해 X-Menu-Id 헤더에 쓴다.
DO $$
DECLARE
    v_key  VARCHAR := to_char(CURRENT_TIMESTAMP, 'YYYYMMDDHH24MI');
    v_menu VARCHAR := 'M' || v_key || lpad(fn_get_serial('KKDUGI_MENU', v_key, 1)::text, 4, '0');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM kkdugi_menu_base WHERE menu_pgm = 'admin/batch/runner') THEN
        INSERT INTO kkdugi_menu_base (menu_id, menu_parent_id, menu_pgm, menu_lvl, menu_path, sort_seq, reg_id)
        VALUES (v_menu, NULL, 'admin/batch/runner', 0, '/' || v_menu, 90, 'SYSTEM');
        -- 세션 메뉴 조회가 kkdugi_menu_lang을 INNER JOIN하므로 등록된 언어를 모두 넣는다.
        INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES
            (v_menu, 'ko_KR', '배치 Runner', 'SYSTEM'),
            (v_menu, 'en_US', 'Batch Runners', 'SYSTEM'),
            (v_menu, 'jp_JA', 'バッチRunner', 'SYSTEM');
    END IF;
END $$;

SELECT menu_id, menu_pgm FROM kkdugi_menu_base WHERE menu_pgm = 'admin/batch/runner';
