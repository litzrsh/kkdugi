# 인증·세션 연동, 로그인 확인 팝업 및 문서 통합

- 일자: 2026-09-19
- 구현: kkdugi-admin
- 계약: [인증 API](../../api/auth.md), [세션·Pragma](../../api/session.md)
- 결정: [디자인 ADR-0011](../adr/0011-session-cookie-login-confirm.md)

## 확인한 현재 구조

로그인은 POST /api/v1.0/auth/login에 form(username,password,force)을 전송한다. JSON 요청은 받지 않는다. 성공은 토큰 쿠키와 컨텍스트 루트 302다. 인증 실패와 중복 로그인은 401 JSON(code,message)이다. 분석 중 실패 응답이 리다이렉트에서 JSON으로 추가 변경되어 최종 코드와 테스트를 JSON 기준으로 동기화했다.

JWT는 sess_id를 담고 exp는 없다. 서버 DB 세션의 존재와 만료 시각을 확인한다. 인증 요청은 session-timeout에 따라 만료를 연장하며 session-refresh-interval 이내 DB 갱신은 생략한다. 다중 로그인 허용 여부에 따라 강제 로그인과 로그아웃의 세션 정리 범위가 달라진다. 프런트는 이 정책을 다시 구현하지 않는다.

기본 쿠키 이름은 KKDUGI_TOKEN이고 window.KKDUGI.tokenCookie로 설정값을 받는다. 쿠키는 <contextPath>/ 경로로 생성되므로 제거도 끝의 슬래시까지 일치시킨다. 매 API 요청 때 쿠키에서 토큰을 읽는다. 프런트는 토큰을 별도로 저장하거나 만료 타이머를 만들지 않는다.

Pragma는 templates/pragma/admin/{code,message,menu}.vue를 사용한다. 현재 백엔드의 하위 폴더 program 검증·세션 메뉴 이동을 유지했다.

## 로그인 UI 변경

1. form의 기본 페이지 이동을 막고 URLSearchParams로 fetch한다. 숨겨진 CSRF 필드도 보존한다.
2. session.err.duplicate 응답이면 현재 화면의 확인 팝업을 연다. 배경 조작은 차단되며 포커스는 취소에 놓인다.
3. 확인은 같은 입력으로 force=true 재시도, 취소·Esc는 비밀번호 삭제와 입력 포커스 복귀다.
4. 처리 중 입력·언어 전환·재제출을 차단한다. 실패는 현재 화면에서 알리고 강제 로그인도 자동 반복하지 않는다.
5. 성공 응답이 같은 origin의 컨텍스트 루트인지 검사한 뒤 이동한다. 페이지 이탈 시 대기 요청을 중단하고 비밀번호를 지운다.
6. 모바일은 화면 너비에 맞는 팝업과 세로 버튼을 사용한다. 문구는 Spring message로 한국어·영어를 렌더링한다.

공통 API의 Accept는 application/json으로 통일했다. Pragma 응답 본문은 여전히 HTML SFC로 검증하지만, 인증 실패가 HTML 로그인 리다이렉트로 선택되지 않도록 요청한다. 401 시 쿠키 삭제·화면 해제·로그인 이동은 기존 공통 처리를 사용한다.

## 문서 통합

기존 kkdugi-design의 docs 24개를 docs/design으로 이동했다. 디자인 ADR과 plan을 보존하고 새 통합 인덱스 및 최신 연동 문서를 추가했다. API 문서·백엔드 문서에서 디자인 문서를 가리키는 경로도 갱신했다. 기존 JSON 로그인 및 sessionStorage 내용은 과거 기록임을 표시했다.

## 검증

- Maven 전체 테스트와 실제 Thymeleaf 로그인 렌더링, form 성공·오류·강제 로그인 시 서버 세션 교체를 검증한다.
- JavaScript 테스트: form 특수문자 인코딩, 401 중복 코드, 성공 목적지 검증, 예외 응답, 네트워크 실패·중단 및 기존 API/Pragma를 검증한다.
- 실제 Spring 렌더링 HTML + 로컬 HTTP 응답 fixture로 Edge 1440×1000, 390×844에서 확인·취소·Esc·강제 재시도·실패·쿠키 경로 제거를 검사한다. 브라우저 검증은 실제 사용자 세션을 만들지 않는다.
- 최종 결과: Maven 138개(실패·오류·건너뜀 0), JavaScript 23개 통과. 데스크톱·모바일 Edge 브라우저 검증 통과. 기존 디자인 문서 24개 이동 및 상대 링크 검증 완료. 테스트용 HTTP 서버와 브라우저는 종료했다.

## 남은 범위

현재 SecurityConfigurer의 전체 permitAll 정책과 익명 session/menu의 빈 목록 응답은 기존 백엔드 상태다. 이 변경에서 새 API 권한 정책을 임의로 추가하지 않았다. 최초·만료 비밀번호 변경 API 및 권한·사용자 API 연결은 별도 구현 범위다. kkdugi-design의 기존 독립 미리보기는 과거 퍼블리싱 산출물이며 현재 서버 로그인 계약의 기준은 kkdugi-admin이다.
