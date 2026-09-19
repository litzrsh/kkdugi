-- 같은 유형(auth_tp_cd) 안에서 권한 ROLE 코드(auth_role_cd)는 유일해야 한다.
-- SessionUtils의 hasRole 판단이 role 코드 기준이라 중복되면 모호해지고,
-- 서비스의 사전 검사(409)와 별개로 동시 요청 경쟁을 DB가 막게 한다.
ALTER TABLE kkdugi_auth_base
    ADD CONSTRAINT kkdugi_auth_base_udx_tp_role UNIQUE (auth_tp_cd, auth_role_cd);
