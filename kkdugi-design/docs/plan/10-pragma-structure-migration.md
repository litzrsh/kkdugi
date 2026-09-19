# Pragma 구조 변경 분석 및 이관 설계

- 작성일: 2026-09-18
- 범위: 문서 검토·현재 코드 비교·설계. 이번 작업에서 실행 코드/DB/브랜치는 변경하지 않고 서버도 시작하지 않는다.
- 결정: [ADR-0009](../adr/0009-pragma-menu-screen-architecture.md)

## 1. 확인한 근거

현행 API: `../../../docs/api/README.md`, `auth.md`, `session.md`, `common-code.md`, `i18n-message.md`.

코드 루트는 `../../../kkdugi-admin/src/`다. 확인 파일:

- main/java/kkdugi/web/admin/{IndexController,PragmaController}.java 및 config/PragmaTemplateConfig.java
- main/java/kkdugi/api/admin/session/{SessionMenuController,MenuTreeItem}.java
- main/java/kkdugi/core/{enums/Rbac,util/TreeUtils}.java
- main/java/kkdugi/core/security/{config/SecurityConfigurer,service/KkdugiUserDetailsService,authentication/filter/BearerTokenAuthenticationFilter}.java
- main/resources/mapper/postgres/SecurityUserDetailsMapper.xml
- main/resources/static/admin/{App.vue,bootstrap.mjs,api.mjs,domain.mjs}
- main/resources/templates/admin/index.html
- test/java/kkdugi/web/admin/PragmaControllerTest.java 및 test/resources/templates/pragma/test_program.vue

테스트 소스는 검토했으며 이번 문서 작업에서 테스트를 실행하지 않았다.

## 2. 현재 구조와 목표

| 항목 | 현재 확인 | 변경 설계 |
|---|---|---|
| 셸 | IndexController가 /admin 렌더링 | 유지 |
| 내비게이션 | screenDefs의 고정 5개 메뉴 | 세션 메뉴 트리 기반 |
| 화면 | App.vue 한 파일에서 screen별 업무 분기 | PageHost + 프로그램별 SFC |
| 진입 URL | #admcode 등 프로그램 코드 | #menu=메뉴ID |
| 로더 | 정적 App.vue, fetch에 Bearer 없음 | 공통 정적 로더 + 인증된 Pragma 텍스트 로딩 |
| 권한 | 화면 작업을 일괄 노출 | 서버 권한 렌더링 + Grid/handler 권한 전달 |
| Dialog | 셸에 단일 DialogHost | 유지, owner별 종료 추가 |
| API | 폐기 계약을 포함한 범용 adapter | 문서화된 도메인별 adapter |

서버의 templates/pragma에는 아직 업무 파일이 없다. 현재 Pragma 테스트는 단순 template 권한 분기만 검증한다. 실제 script setup/import/Thymeleaf 처리 후 Vue 컴파일까지 통과했다는 근거로 사용할 수 없다.

## 3. 현행 API 계약과 적용

| 요청 | 응답 | 적용 |
|---|---|---|
| POST /api/v1.0/admin/auth/login | token 등 / 409 중복 | 기존 vanilla 로그인 유지 |
| POST /api/v1.0/admin/auth/logout | 204 | 변경 보호 후 종료 |
| GET /api/v1.0/admin/session/menu | 트리 배열 | 셸 메뉴 전용 |
| GET /pragma/{menuId} | text/html SFC / 404 | 문자열로 읽어 컴파일 |
| POST /api/v1.0/admin/code | Page | 코드 조회 |
| POST /api/v1.0/admin/code/persist | 빈 200 | insert/update/delete 배치 |
| POST /api/v1.0/admin/i18n | Page | 메시지 조회 |
| POST /api/v1.0/admin/i18n/persist | 빈 200 | 메시지 배치 |

목록은 page/pageSize/totalItems/totalPages/contents, pageSize 최대 200. 코드 locale은 언어별 name/remarks 객체, 메시지 locale은 언어별 문자열이다. 코드 id는 서버 채번이다. 배치 오류 `{errors:[{id?,code,reason}]}`를 행 식별과 메시지 번역에 연결한다. 신규 코드 오류는 같은 화면의 code로 연결하고, 매칭이 불명확하면 상단 오류 요약을 유지한다.

공통 오류는 `{code,message}` 또는 그 배열이며 500도 처리한다. 빈 저장 응답을 JSON 파싱 실패로 취급하지 않는다. 메뉴·권한·사용자 CRUD와 비밀번호 변경 API는 현재 docs/api에 없어 endpoint나 payload를 확정하지 않는다.

## 4. 반드시 구분할 문서·구현 차이

| 사항 | 확인 내용 | 설계 대응/서버 제안 |
|---|---|---|
| 폐기 계약 | API README와 CLAUDE.md가 여전히 구 문서를 목표 계약으로 안내 | 사용자 지시 우선. 구 문서를 역참조하지 않고 문서 소유 측에 정정 필요 기록 |
| 익명/만료 | auth.md는 API 401을 설명하지만 anyRequest().permitAll(); 필터는 잘못된 토큰도 익명으로 통과 | 현재 menu는 200 [], Pragma는 404가 가능. 이를 임의로 세션 만료라고 단정하지 않는다. API/Pragma 인증 강제와 401 형식을 서버에서 정리해야 함 |
| leaf children | 예시는 [], TreeUtils는 null 반환 | `children ?? []`로 정규화 |
| 실행 가능 여부 | 메뉴 응답은 id/parentId/title/remarks/icon/sort/children만 포함 | program/authority 추정 금지. 임시 children 기준 분류 및 leaf 404 안내. openable 확장 권고 |
| 부모 누락 | 일반 사용자 쿼리는 권한 매핑된 메뉴만 조회; TreeUtils는 parentId가 빈 노드부터 구성 | 자식만 허용되면 메뉴가 트리에서 사라질 수 있음. 서버가 표시용 조상 노드를 포함해야 함. 프런트에서 알 수 없는 조상을 합성하지 않음 |
| READ 의미 | HAVING BIT_OR(auth_val)>0은 READ 비트 확인과 다름 | 쓰기/삭제/실행만 있는 메뉴도 포함 가능. Pragma도 별도 READ 검사 없음. READ 허용 조건을 서버에서 명시해야 함 |
| 언어 | 세션 제목은 SESSION_LANG=ko_KR; Pragma는 new Context() | shell locale 변경만으로 메뉴/Pragma 언어 변경이 보장되지 않음. 요청 Locale 및 공통 MessageSource 연결 검증, 메뉴 언어 재조회 계약 필요 |
| 권한 신선도 | 메뉴/권한은 로그인 시 DB 세션 스냅샷 | 캐시 삭제만으로 권한 갱신 불가. 역할 변경 시 세션 무효화/재생성 정책 서버 확정 필요 |
| 404 | 없는 메뉴/프로그램 없음/템플릿 없음 동일 응답 | 사용자에게 하나의 '화면을 열 수 없음' 상태. 원인을 추정해 다른 메시지로 표시하지 않음 |

그룹에는 권한 할당이 무의미하다는 기존 요구를 유지한다. 부모를 보이게 하려고 그룹에 가짜 READ 권한을 요구하는 방식은 채택하지 않는다. 서버가 내비게이션용 조상을 보충하고 Pragma 접근은 실제 실행 메뉴 권한으로 판단해야 한다.

## 5. 목표 파일과 책임 (신규 경로는 제안)

```text
templates/admin/index.html          공통 HTML/설정 (유지)
templates/admin/login.html          Vue 없는 로그인 (유지)
templates/pragma/admcode.vue         공통코드 진입 화면
templates/pragma/admmsge.vue         메시지 진입 화면
static/admin/App.vue               셸·GNB·공통 Dialog/알림
static/admin/components/NavigationTree.vue
static/admin/components/PageHost.vue
static/admin/runtime/sfc-loader.mjs 정적 alias·인증 텍스트 요청·컴파일
static/admin/runtime/navigation.mjs 메뉴ID·전환·변경 보호
static/admin/api/{http,session,code,i18n}.mjs
static/admin/Grid.vue               공유 Grid
static/admin/DialogHost.vue         공유 Vue 팝업/Dialog
static/admin/domain.mjs             순수 배치/검증 유틸리티
static/admin/admin.css              공통 디자인
```

프로그램 파일명은 기본 5개 코드 유지 계획이나, 실제 세션 메뉴의 program 값과 일치하는지 이관 시 확인한다. 브라우저는 그 값을 알 필요가 없다. admmenu/admauth/aduser는 새 API 계약 후 단계적으로 이관한다. 정적 전체 업무 App.vue를 숨은 fallback으로 남겨 보호된 화면을 우회 로딩하지 않는다.

## 6. 런타임 계약

### 초기화·전환

1. 기존 sessionStorage 토큰 존재 검사 → Vue 셸 1회 생성 → Bearer 메뉴 요청.
2. 메뉴 트리를 정규화한다. title/remarks는 텍스트로 출력하고 icon은 허용된 line-awesome 매핑과 기본 아이콘을 사용한다.
3. URL의 menuId가 메뉴 목록에 있는지 확인한다. 초기 hash가 없으면 메뉴 선택 안내를 표시한다. 폐기된 #admcode 등을 특정 메뉴 ID로 임의 변환하지 않는다.
4. 전환 요청은 현재 화면의 `beforeLeave(reason)`을 기다린다. Grid 편집을 먼저 종료해 변경분을 확정한다. 저장 실패/취소 시 현재 화면과 URL을 유지한다.
5. 메뉴 ID는 encodeURIComponent로 경로 세그먼트에 넣는다. contextPath를 한 번만 붙인다. 인증 헤더를 same-origin의 허용 API/Pragma 경로로만 전달한다.
6. Pragma fetch는 cache:no-store, redirect:error와 JSON 오류 협상이 가능한 Accept를 사용한다. 서버는 정상 시 text/html을 반환한다. 응답 상태·미디어 타입·SFC 구조를 검사하며 로그인 HTML을 SFC로 컴파일하지 않는다.
7. 이전 전환 AbortController를 취소하고 generation을 갱신한다. 컴파일 자체가 취소되지 않아도 늦게 완료된 결과는 버린다. 성공 시 기존 화면을 unmount하고 새 화면으로 교체한다. 로딩/실패 중에는 기존 화면 위 진행 상태를 표시하고 중복 작업을 막는다. 실패하면 기존 화면과 확정 URL을 보존하고 재시도를 제공한다.
8. 뒤로/앞으로 이동도 같은 guard를 통과한다. 취소 시 마지막 확정 hash 복원, hash 이벤트 재진입 방지. 인증 상실은 저장 대기를 강제하지 않고 화면/팝업을 정리 후 로그인 이동.

### 의존성·컴포넌트 계약

- SFC는 `vue`를 기존 moduleCache에서 사용하고 `@admin/Grid.vue` 같은 alias로 공유 자산을 import한다. 로더가 alias를 basePath + `/admin/`으로 해석한다. `/pragma/{menuId}`를 기준으로 `./Grid.vue`를 해석하지 않는다. 허용하지 않은 외부 URL/경로 이탈 import는 거부한다.
- inject('kkdugi'): api, t, dialog, notify, languages, statuses, navigation. 화면마다 app을 생성하거나 window.Vue를 다시 로드하지 않는다.
- PageHost가 전달하는 props: menuId, title, remarks, locale. 서버의 authorities는 메뉴 응답에서 만들지 않고 SFC 내 Thymeleaf 안전 직렬화로 권한 boolean 객체를 생성해 공유 Grid/작업 모듈에 전달한다. 실제 script setup + th:inline=javascript 결합은 선행 통합 테스트로 확정한다.
- 화면은 expose로 beforeLeave(reason):Promise<boolean>을 제공한다. 요청 취소·Grid destroy·리스너 제거는 onBeforeUnmount에서 수행한다.
- Dialog는 ownerId를 가진다. 화면 종료 시 해당 owner의 대기 Promise를 cancelled로 정확히 한 번 종료한다. 다이얼로그·폼 변경도 beforeLeave에 포함한다.
- CSS는 기존 공통 파일을 유지한다. addStyle 금지 정책은 유지하며 화면별 SFC style 추가가 필요하면 별도 결정한다.

### 오류와 캐시

401은 토큰/화면 정리 후 로그인, 403은 접근 불가, 404는 일반 화면 불가, 네트워크/5xx/컴파일 오류는 재시도다. 빈 메뉴 200은 '표시할 메뉴 없음'과 재시도/로그아웃을 제공하며 자동 로그인 반복을 만들지 않는다.

Pragma의 소스·컴파일 캐시는 전환 범위로 제한하고 정적 라이브러리 캐시와 분리한다. 같은 메뉴를 다시 열어도 서버에서 권한 렌더링을 다시 받는다. 서버 템플릿 파싱 캐시와 사용자별 응답 캐시는 구분한다. 로그에 토큰/전체 소스/사용자 입력을 남기지 않는다.

## 7. 권한과 기존 디자인 보존

- READ(10): 조회 영역. WRTE(20): 신규/수정·인라인 편집. DELT(30): 삭제 선택·실행. EXEC(40): 실행 기능에만 사용. 비밀번호 초기화 등의 상세 매핑은 새 도메인 계약에서 확정한다.
- 삭제만 허용된 사용자의 배치도 저장할 수 있도록 저장 버튼은 20 또는 30으로 노출한다. payload에서 insert/update는 20, delete는 30을 개별 확인한다. 혼합 배치의 최종 권한 검증은 서버 책임이다.
- Grid cellRenderer로 생성하는 수정/삭제 버튼과 메시지 input 편집도 동일 권한을 적용한다. th:if로 상단 버튼만 숨기는 구현은 불충분하다.
- 메시지 외 다국어 탭, 코드 확장값 textarea 탭, resize:none, 둥근 프로필 미리보기, 권한 적용 기간과 컴팩트 팝업을 유지한다. 미연결 화면은 디자인 자산으로 보존한다.
- 모바일은 고정 GNB 아래 단일 화면, 사이드 메뉴 overlay·포커스 복귀, 기존 반응형 Dialog를 유지한다. 화면 변경 시 제목으로 포커스 이동, 로딩 상태 aria-busy 및 알림 제공. 변경사항 바는 타이틀과 검색 사이에 둔다.

## 8. 실행 순서와 완료 기준

| 순서 | 작업 | 완료 기준 |
|---|---|---|
| 1 | 계약 정리 및 최소 SFC 검증 | 실제 Thymeleaf를 거친 script setup/import/권한 boolean을 loader가 컴파일, contextPath·ko/en 검증 |
| 2 | 인증 요청·session adapter·로더 분리 | Bearer/401/빈 응답/오류 배열/Pragma404/redirect 처리 테스트 |
| 3 | 셸·세션 메뉴·PageHost | 고정 screenDefs 탐색 제거, 빈 트리/null children/새로고침/hash 전환/늦은 응답 처리 |
| 4 | 코드·메시지 화면 이관 | 기존 검색·계층·배치·input 편집 보존, 권한별 작업·팝업·미저장 보호 |
| 5 | 실 API/모바일 회귀 | Maven 및 프런트 테스트, 실제 로그인 후 두 화면 데이터 조회/저장, 모바일 및 키보드 검수 |
| 6 | 나머지 3개 화면 | 해당 docs/api 신규 계약 확인 후 시작, endpoint 추정 금지 |

필수 검증 조합: 익명/만료/강제 로그아웃, READ만/WRTE만/DELT만/전체, 자식만 허용된 메뉴, 프로그램 없는 leaf, 존재하지 않는 템플릿, /context 경로, locale 변경, 저장 실패 뒤 전환 취소, 빠른 연속 클릭, Dialog가 열린 상태의 화면 종료. Pragma 응답에는 no-store 및 사용자 간 렌더링 결과 분리를 확인한다.

서버 조정 의존성 중 API 인가·READ 정책·조상 노드 보충·다국어 처리가 완료되지 않으면 그 한계를 남기고 운영 연동 완료로 보고하지 않는다. 비밀번호 최초/만료 변경 흐름은 기존 대기 사항으로 유지한다.
