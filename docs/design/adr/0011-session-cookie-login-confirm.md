# ADR-0011: 쿠키 세션 로그인과 현재 화면 확인 팝업

- 일자: 2026-09-19
- 상태: 적용
- 대체 범위: ADR-0007의 독립 중복 로그인 화면, ADR-0008의 JSON 요청·sessionStorage 토큰 방식
- [상세 변경·검증](../plan/14-session-auth-confirm-and-docs.md)

## 결정

로그인 화면은 Vue 없이 HTML/CSS3/vanilla JavaScript를 유지한다. form 입력을 URLSearchParams로 직렬화해 기존 로그인 필터에 전송한다. 성공은 쿠키 설정과 루트 리다이렉트, 실패는 401 JSON이며 중복 로그인의 코드는 session.err.duplicate다.

중복 로그인 응답은 현재 페이지의 native dialog 확인 팝업을 연다. 기본 포커스는 취소이고 Esc도 취소다. 확인을 눌렀을 때만 같은 ID·비밀번호에 force=true를 붙여 재시도한다. 재시도 실패 시 자동 루프를 만들지 않는다. 비밀번호는 현재 form 입력에서만 유지하며 취소·페이지 이탈·실패·성공 시 비운다.

API 토큰은 설정된 쿠키에서 매번 읽어 Bearer 헤더로 보낸다. JWT의 exp나 클라이언트 타이머로 세션 만료를 판단하지 않는다. 만료·강제 종료는 서버 세션이 판정한다. API/Pragma의 401은 쿠키 제거와 화면 해제 후 로그인으로 이동한다. 로그인 자체의 401은 이 공통 이동 로직에서 제외한다.

디자인 문서는 최상위 docs/design으로 통합하고 기존 ADR 번호와 문서 내부 링크를 유지한다. 백엔드 ADR은 docs/adr, API 계약은 docs/api에서 관리한다.
