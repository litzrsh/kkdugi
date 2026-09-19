-- V10이 만든 기본 메뉴 트리에 일본어(ja_JP) 메뉴명을 추가한다.
-- V10은 이미 적용된 마이그레이션이라 수정하면 Flyway 체크섬이 어긋나므로 별도 파일로 넣는다.
--
-- kkdugi_menu_lang은 세션 메뉴 조회에서 INNER JOIN이라 로케일 행이 없는 언어로 로그인하면
-- 그 메뉴가 통째로 빠진다 (V10 주석 참고).
--
-- 기본 메뉴는 menu_pgm으로 찾는다. 프로그램이 없는 시스템관리 폴더는 V10과 같이
-- ko_KR 이름('시스템관리')으로 찾는다. 이미 ja_JP 행이 있으면 건드리지 않는다.
INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id)
SELECT b.menu_id, 'ja_JP', d.menu_nm, 'SYSTEM'
FROM kkdugi_menu_base b
JOIN (VALUES
    ('home',            'ホーム'),
    ('admin/code',      '共通コード管理'),
    ('admin/message',   'メッセージ管理'),
    ('admin/menu',      'メニュー管理'),
    ('admin/authority', '権限管理'),
    ('admin/user',      'ユーザー管理')
) AS d (menu_pgm, menu_nm) ON d.menu_pgm = b.menu_pgm
ON CONFLICT (menu_id, lang_cd) DO NOTHING;

INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id)
SELECT b.menu_id, 'ja_JP', 'システム管理', 'SYSTEM'
FROM kkdugi_menu_base b
JOIN kkdugi_menu_lang l ON l.menu_id = b.menu_id AND l.lang_cd = 'ko_KR' AND l.menu_nm = '시스템관리'
WHERE b.menu_pgm IS NULL AND b.menu_parent_id IS NULL
ON CONFLICT (menu_id, lang_cd) DO NOTHING;
