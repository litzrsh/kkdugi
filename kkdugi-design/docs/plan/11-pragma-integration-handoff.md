# Pragma 화면 연동 구현 인계

- 날짜: 2026-09-18
- 구현 위치: `C:/projects/kkdugi/kkdugi-admin`
- 기준: [설계 10](10-pragma-structure-migration.md), [ADR-0009](../adr/0009-pragma-menu-screen-architecture.md), `C:/projects/kkdugi/docs/api/`
- 상태: 공통 셸·세션 메뉴·공통코드/메시지 Pragma 화면 이관 완료. 나머지 도메인 및 서버 인가 정책은 별도 대기.

## 적용 내용

1. `IndexController`의 `/admin` HTML 및 plain 로그인은 유지한다. `App.vue`에서 고정 메뉴 목록과 업무 분기를 제거하고 세션 메뉴 트리, GNB, PageHost, DialogHost, 알림을 관리한다.
2. `GET /api/v1.0/admin/session/menu`에서 메뉴를 읽고 `/admin#menu=<메뉴ID>`로 이동한다. `children:null`을 정규화한다. 빈 메뉴, 없는 메뉴, 프로그램 없는 leaf/미구현 화면은 안내 상태로 처리한다. 클라이언트에서 프로그램 코드를 추정하지 않는다.
3. `/pragma/{menuId}` 요청에 Bearer를 보낸다. 성공한 text/html SFC만 컴파일한다. 401은 토큰/활성 화면/팝업을 정리하고 로그인으로 이동한다. 빈 메뉴 200이나 Pragma 404를 임의의 인증 실패로 간주하지 않는다.
4. `templates/pragma/admcode.vue`, `admmsge.vue`를 추가했다. 실제 업무 UI는 두 화면이 공유하는 `components/BatchPage.vue`에 있다. Pragma 진입점은 각 화면 코드와 서버 권한을 명시한다. Grid·폼·배치 편집을 중복 복사하지 않았다.
5. Pragma의 authorities를 Thymeleaf에서 안전하게 JavaScript 객체로 직렬화한다. READ가 없으면 업무 화면을 렌더링하지 않는다. WRTE는 신규/수정과 Grid input 편집, DELT는 삭제/복원을 제어한다. 삭제만 가능한 사용자의 배치 저장도 허용하며 저장 전 insert/update/delete 권한을 각각 확인한다.
6. 메뉴 전환은 현재 화면의 beforeLeave를 기다린다. 저장 실패/취소 시 화면과 hash를 유지한다. 이전 fetch를 취소하고 늦게 끝난 컴파일은 버린다. 화면 종료 시 Grid·요청·이벤트·해당 화면 소유 Dialog Promise를 정리한다.
7. Vue app은 한 번 마운트한다. 정적 컴포넌트는 공유하되 사용자별 Pragma 결과는 매번 새로 컴파일한다. `@admin/` import를 contextPath 포함 경로로 해석하고 외부 의존성 URL을 거부한다. Pragma 응답과 요청에 no-store를 적용한다.
8. PragmaController에 요청 Locale을 전달하고 전용 SpringTemplateEngine에 기존 MessageSource를 연결했다. 서버의 숫자 권한 키 10/20/30/40을 그대로 사용한다.
9. 실제 adapter는 문서화된 auth/session/code/i18n만 호출한다. 미정의 메뉴·권한·사용자 CRUD endpoint를 live adapter에서 제거했다. 디자인 프로젝트의 나머지 화면 자산은 보존했다.
10. 작업 도중 갱신된 common-code API의 신규 코드 형식 `^[A-Z0-9]+(_[A-Z0-9]+)*$`와 단일 `{code,message}` 오류에 대응했다. 입력값을 자동 대문자로 바꾸지 않고 검증과 안내를 제공한다. 기존 데이터의 코드 수정은 허용하지 않는다. 기존 `{errors:[...]}` 및 공통 배열 오류도 읽을 수 있다.

## 파일 안내

모든 경로는 kkdugi-admin 기준이다.

| 경로 | 역할 |
|---|---|
| src/main/resources/static/admin/App.vue | 공통 셸 및 전환 조율 |
| static/admin/components/NavigationTree.vue | 서버 메뉴 트리 |
| static/admin/components/PageHost.vue | 활성 화면과 beforeLeave 위임 |
| static/admin/components/BatchPage.vue | 코드·메시지 공유 배치 화면 |
| static/admin/runtime/sfc-loader.mjs | Pragma 및 정적 SFC 로딩 |
| static/admin/runtime/navigation.mjs | 메뉴 정규화·hash·요청 순서 제어 |
| static/admin/api.mjs, api/http.mjs | 현행 API adapter·인증·응답 처리 |
| templates/pragma/admcode.vue, admmsge.vue | Thymeleaf 업무 진입 SFC |
| web/admin/PragmaController.java | Locale 및 no-store |
| web/admin/config/PragmaTemplateConfig.java | 공통 MessageSource 연결 |

표의 static/templates는 `src/main/resources/`, web은 `src/main/java/kkdugi/` 아래다. 기존 bootstrap, Grid, domain, CSS 및 admin-ui 메시지 번들도 함께 수정했다.

## 검증 결과

- Maven `test` 실행 성공. 확인한 Surefire 보고서 합계 96 tests, failures/errors/skipped 0.
- `node --test src/test/js/*.test.mjs`: 12개 통과. 기존 인증 3개 + Pragma/메뉴/오류/형식/경합 및 실제 렌더링 SFC 검증.
- PragmaControllerTest는 실제 두 템플릿을 READ / READ+WRTE / READ+DELT / 전체 권한으로 렌더링하고 no-store·권한값을 검증한다. READ 없는 화면의 영어 오류 문구도 확인한다.
- `pragma-sfc.test.mjs`는 Maven 테스트가 만든 `target/pragma-test-output/*.vue`를 실제 로컬 vue3-sfc-loader로 컴파일한다. contextPath, 공유 컴포넌트, 사용자별 소스 재컴파일, 외부 의존성 거부를 검사한다. **Maven 테스트 후 Node 테스트 순서로 실행한다.**
- Headless Edge 1440px/390px: 세션 메뉴→Pragma 로딩, 코드 등록 Dialog/고정 textarea, 메시지 input 편집/저장, 미저장 상태 이동 취소, 읽기 전용/삭제 권한, 404 후 기존 화면 유지, 모바일 메뉴 닫힘·고정 GNB 확인. 브라우저 테스트는 실제 서버 렌더링 SFC 및 프로젝트 정적 자산에 테스트용 API 응답을 연결했다. 실제 사용자 데이터 저장을 검증한 것으로 해석하지 않는다.
- 실제 API/DB 동작은 Maven 통합 테스트가 담당한다. 실제 사용자 계정으로 로그인한 전체 브라우저 E2E는 미실시다.
- 브라우저 검수의 임시 HTTP 서버·브라우저는 종료했다. Spring 상주 서버를 시작하지 않았다. 브랜치 전환/커밋/사용자 데이터 수동 수정 없음.

## 남은 의존성·제약

- 메뉴·권한·사용자 CRUD, 첫 비밀번호/만료 비밀번호 변경은 새 API 계약 이후 연결한다.
- 서버의 permitAll, 메뉴 조회의 익명 200 빈 배열, Pragma 404 계약은 유지한다. 이번 UI 권한 제어는 API 인가 완성이 아니다.
- 일반 사용자에게 자식 메뉴만 권한이 있을 때 표시용 조상을 보충하는 작업, READ 비트에 대한 서버 정책, 세션 스냅샷 권한 갱신은 서버 측 대기 사항이다.
- 메뉴 응답에 실행 가능 여부가 없어 현재 자식이 있는 노드는 펼침, leaf는 열기 시도로 처리한다. 실행 가능한 부모 지원은 openable 등의 API 확정이 필요하다.
- Pragma 자체는 요청 Locale/MessageSource에 연결했지만 세션 메뉴의 한국어 고정은 그대로다. 셸 언어 전환만으로 메뉴 제목 번역까지 완료됐다고 볼 수 없다.
- 동시 진행 중인 공통코드·메시지 백엔드 변경은 덮어쓰지 않았다. 현행 단일 오류에서는 문제 행 ID가 제공되지 않으므로 상단 서버 메시지를 표시하며 행별 오류 위치를 추정하지 않는다.

## 설계 대비 구체화

- code/i18n adapter 파일을 각각 늘리는 대신 작은 `api.mjs`에서 허용된 두 resource를 명시하고 HTTP 처리를 분리했다.
- 코드/메시지의 공통 배치 UI는 BatchPage 하나로 공유한다. 서로 다른 프로그램의 서버 진입 SFC와 페이지 인스턴스는 분리된다.
- 테스트·시각 증빙은 현재 작업 공간의 `outputs/pragma-verification/desktop.png`, `mobile.png`에 보관했다.
