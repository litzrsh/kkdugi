> 2026-09-19: 이 문서의 로그인·세션 계약은 당시 기록입니다. 현행 form/쿠키/JSON 오류 및 확인 팝업은 [최신 연동 기록](../plan/14-session-auth-confirm-and-docs.md)을 참조합니다.

# 최신 소스·API 계약 재동기화

- 날짜: 2026-09-18
- 실제 구현: `C:/projects/kkdugi/kkdugi-admin`
- 근거: `C:/projects/kkdugi/docs/api/{README,auth,session,common-code,i18n-message,menu}.md` 및 해당 컨트롤러/서비스
- 구조: [역할별 리소스 배치](12-role-based-resource-layout.md)를 유지한다.

## 다시 확인한 변경

- 공통 셸이 `/admin`에서 `/`로 이동했다. 로그인 성공 URL도 `/`다.
- 로그인/로그아웃은 `/api/v1.0/auth/login`, `/api/v1.0/auth/logout`, 사용자 메뉴는 `/api/v1.0/session/menu`다. 이 경로와 프런트 HTTP 허용 목록은 이미 수정돼 있어 유지했다.
- 도메인 API는 `/api/v1.0/admin/` 아래다. 메뉴 GET 및 persist POST가 새로 구현됐다.
- 코드/메시지 오류는 `{code,message}` 단일 객체로 통일됐으며, 메시지는 기존 코드에 새 언어를 추가하는 update를 지원한다.
- 코드/메뉴의 최상위 parentId는 null이다. locale에는 빈 이름·메시지 값을 전송할 수 없다.
- DB 목록 페이징 구현은 바뀌었지만 Page 응답의 page/pageSize/totalItems/totalPages/contents 구조는 유지된다.
- Maven Wrapper가 추가돼 이번 검증은 `mvnw.cmd test -q`로 실행했다.

## 수정 내용

### 메뉴관리 연결

`templates/pragma/admmenu.vue`와 기존 `vue/pages/BatchPage.vue`의 메뉴 구성을 연결했다. 실제 메뉴의 program이 `admmenu`일 때 서버가 이 화면을 제공한다. DB 메뉴를 임의로 생성하지 않았다.

- `GET /api/v1.0/admin/menu`: 요청 본문 없이 전체 트리 조회. leaf의 children:null 처리.
- AG Grid에 깊이를 들여쓰기로 표현하며 접기/펼치기 없이 전체 행을 표시한다. 검색은 클라이언트에서 수행하고 검색 결과의 조상도 함께 표시한다. 페이징하지 않는다.
- 최상위/하위 메뉴 등록, 언어 탭에서 label/remarks 편집, program/icon/use/close/sort 편집.
- parentId는 기존 메뉴에서 불변이며 새 메뉴 생성 시 null 또는 저장된 부모 ID를 보낸다. 아직 저장하지 않은 부모 아래의 추가 등록은 부모 저장 이후로 안내한다.
- insert/update/delete 배치 저장. 부모 삭제 시 하위 행도 삭제 상태로 표시하고 복원 시 함께 복원한다. 요청에는 삭제 최상위 조상만 보낸다. 부모와 자식을 모두 전송해 서버의 하위 삭제 후 자식 not_found 409가 생기는 문제를 피한다.
- 기본 5개 프로그램과 해당 상위 메뉴의 삭제를 UI에서 막는다. 기본 메뉴의 식별·구조 설정은 읽기 전용이며 로케일 라벨/설명은 편집할 수 있다. 이는 API의 접근 제어를 대체하지 않는다.
- 새 close 필드는 '화면 탭 닫기 허용'으로 관리한다. 현재 단일 화면 셸에 임의의 다중 탭 기능을 추가하지 않았다.
- 메뉴는 세션 스냅샷이므로 저장 후 탐색 메뉴 반영에는 재로그인이 필요하다고 안내한다.

### 코드/메시지 저장 보정

- 최상위 코드 등록의 parentId 빈 문자열을 null로 보낸다. 기존 코드가 ''를 부모 ID로 조회하며 실패할 수 있던 불일치를 수정했다.
- 신규·추가 언어의 완전히 비어 있는 입력은 locale에서 제외한다. 하나 이상의 유효 번역은 필수다. 설명만 있고 이름이 없는 언어는 오류로 안내한다.
- 기존 번역을 비우는 경우에는 저장 전에 안내한다. 현재 API에는 번역 삭제 계약이 없으므로 빈 값을 보내거나 해당 언어를 조용히 생략해 변경이 저장된 것처럼 보이게 하지 않는다.
- 새 언어 번역 추가는 기존 메시지 코드의 update locale에 넣어 전달한다.
- 단일 서버 오류는 서버가 번역한 message를 표시하고, 공통 배열 오류도 각 message를 우선 표시한다. 제공되지 않은 오류 행 ID를 추정하지 않는다.

## 변경 파일

- static/js/api/index.mjs
- static/js/domain/batch.mjs
- static/vue/pages/BatchPage.vue
- templates/pragma/admmenu.vue
- messages/admin-ui_ko_KR.properties, admin-ui_en_US.properties
- PragmaControllerTest.java, pragma-sfc.test.mjs, current-contract.test.mjs

static/templates/messages는 src/main/resources 아래다. Java 테스트는 src/test/java/kkdugi/web/admin, Node 테스트는 src/test/js 아래다.

## 검증

- Maven Wrapper 전체 테스트: **109개 통과**, 실패/오류/skip 0.
- Node 테스트: **18개 통과**. API 경로·GET body 없음·루트 parentId·빈 번역 제외·기존 번역 삭제 방지·null children·검색 조상 보존·삭제 요청 축약·close/parentId 유지·기본 메뉴 보호 포함.
- 실제 Spring이 렌더링한 코드/메시지/메뉴 SFC를 4가지 권한 조합으로 컴파일했다.
- Headless Edge 1440px/390px: 새 루트/세션 경로, 최상위 코드 등록과 한 언어만 전송, 메시지 input 수정/저장, 메뉴 하위 등록 및 close=N, 부모/자식 삭제·복원과 부모만 전송, 기본 메뉴 삭제 차단/프로그램 읽기 전용, 미저장 보호, 모바일 고정 GNB/메뉴 동작을 확인했다.
- 브라우저는 실제 정적 자산과 Spring 렌더링 SFC에 테스트용 API 응답을 연결했다. 실제 사용자 데이터 쓰기는 하지 않았다. 실제 DB/API는 Maven 통합 테스트가 검증한다.
- 임시 테스트 서버·브라우저는 종료했다. Spring 상주 서버를 시작하지 않았고 브랜치/스테이징 상태를 변경하지 않았다.

## 남은 범위

권한·사용자 관리와 최초/만료 비밀번호 변경은 현재 docs/api의 미구현 범위다. 서버 permitAll, 세션 메뉴 언어 고정, 권한 조상 보충/실행 가능 메뉴 구분 정책은 이번 프런트 계약 보정에서 변경하지 않았다. 메뉴 저장 API 자체의 기본 메뉴 보호·권한 강제는 별도 서버 정책이 필요하다.

이 문서가 과거 계획 11의 '메뉴 API 대기' 상태와 이전 /admin·/api/v1.0/admin/auth·/api/v1.0/admin/session 경로 설명을 대체한다. 폐기된 API 원문은 참조하지 않았다.
