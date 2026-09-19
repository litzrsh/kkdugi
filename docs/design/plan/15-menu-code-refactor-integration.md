# 메뉴·코드 조회 리팩토링 후 관리 화면 연결

- 일자: 2026-09-19
- 범위: 코드관리, 메시지관리, 메뉴 탐색·Pragma 로딩, 사용자용 코드 조회

## 조회 계약

| 목적 | 요청 | 응답 |
|---|---|---|
| 내 메뉴 탐색 | GET /api/v1.0/menu | 메뉴 트리 |
| 단일 메뉴 화면 | GET /pragma/{menuId} | text/html Vue SFC |
| 사용자용 코드 조회 | GET /api/v1.0/code?path=... | 요청 언어의 코드 배열 |
| 코드관리 | POST /api/v1.0/admin/code | 다국어 코드 페이지 |
| 메시지관리 | POST /api/v1.0/admin/i18n | 언어별 값을 한 행에 담은 페이지 |

관리 화면의 계층 이동·검색·배치 저장은 관리용 API를 유지한다. 사용자용 코드 조회는 현재 서버의 정확한 path 일치·배열 응답에 맞췄으며 별도 codes(path) 메서드로 제공한다.

## 수정

PragmaController는 text/html 응답을 선언한다. 클라이언트가 Accept: application/json만 보내던 요청은 Spring MVC에서 406으로 거부되어 코드·메시지 화면이 열리지 않았다. Pragma에 한해 text/html도 수락하도록 수정하고 실제 Spring 렌더링 테스트에도 같은 헤더를 적용했다.

비동기 API 요청은 X-Requested-With: XMLHttpRequest를 전송한다. 인증 진입점은 이 요청을 HTML 페이지 이동과 구분하므로, 이후 보호되는 API의 인증 실패도 기존 401 JSON 처리로 이어진다. 일반 브라우저 페이지 이동은 로그인 리다이렉트를 유지한다.

코드 조회 리팩토링의 캐시를 활성화하고 관리 저장 후 캐시를 커밋 시점에 비우도록 구성했다. 코드 path 누락은 400으로 처리한다. 예전 parentId/페이지 응답을 가정하던 코드 조회 테스트와 API 문서를 현재 계약에 맞춰 변경했다.

## 검증

전체 Maven/JavaScript 테스트, 실제 Thymeleaf 렌더링 및 데스크톱·모바일 브라우저에서 코드 등록·메시지 입력 편집·저장·읽기 권한·메뉴 이동을 검증한다. 브라우저는 Spring이 렌더링한 SFC와 HTTP fixture를 사용하고, DB 저장과 캐시 갱신은 Spring 통합 테스트로 검증한다.

최종 결과: Maven clean test 146개, JavaScript 25개 통과. 데스크톱·모바일 브라우저에서 코드 등록, 메시지 인라인 편집·저장, 메뉴 이동 및 권한별 동작 확인. 테스트용 서버·브라우저 종료.

## 반영

사용자가 작성한 미커밋 코드 조회 리팩토링을 보존한 작업 브랜치에서 수정한다. 최종 결과는 master에 squash merge해 단일 커밋으로 push한다. 기존 디자인 문서 14의 Pragma Accept: application/json 단독 설정은 이 문서의 수정으로 대체된다.
