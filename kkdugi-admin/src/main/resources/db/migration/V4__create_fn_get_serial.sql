-- 일련번호 채번을 DB function 하나로 원자적으로 처리한다. 여러 애플리케이션
-- 인스턴스가 동시에 호출해도, Postgres의 INSERT ... ON CONFLICT ... DO UPDATE
-- 한 문장이 대상 행에 대해 원자적으로 실행되므로 경쟁 상태 없이 안전하다
-- (애플리케이션 레벨의 조회 후 갱신(read-then-write)과 달리 인스턴스 수와
-- 무관하게 정합성이 보장된다).
--
-- 의미: p_id(채번 대상 식별자, 예: "KKDUGI_CODE") 하나당 행 하나를 두고,
-- p_key(현재 채번 구간 키, 예: SerialConfig.getDateFormat()으로 만든
-- "202609161750")가 저장된 seq_base보다 앞서 있지 않으면(=아직 같은 구간)
-- seq_val에 p_size를 더하고, p_key가 더 최근이면(=새 구간으로 넘어감)
-- seq_val을 p_size로 리셋한다. 반환값은 갱신 후 seq_val이다.
CREATE OR REPLACE FUNCTION fn_get_serial(p_id VARCHAR, p_key VARCHAR, p_size BIGINT)
RETURNS BIGINT AS $$
    INSERT INTO kkdugi_serial_base (serial_id, seq_base, seq_val, upd_dtm)
    VALUES (p_id, p_key, p_size, CURRENT_TIMESTAMP)
    ON CONFLICT (serial_id) DO UPDATE
    SET seq_base = CASE
                        WHEN p_key > kkdugi_serial_base.seq_base THEN p_key
                        ELSE kkdugi_serial_base.seq_base
                    END,
        seq_val  = CASE
                        WHEN p_key > kkdugi_serial_base.seq_base THEN p_size
                        ELSE kkdugi_serial_base.seq_val + p_size
                    END,
        upd_dtm  = CURRENT_TIMESTAMP
    RETURNING seq_val;
$$ LANGUAGE sql;

COMMENT ON FUNCTION fn_get_serial(VARCHAR, VARCHAR, BIGINT) IS
    '일련번호 채번: p_id당 한 행, p_key(채번 구간)가 바뀌면 p_size로 리셋, 아니면 seq_val += p_size. INSERT..ON CONFLICT 한 문장으로 원자적/동시성 안전.';
