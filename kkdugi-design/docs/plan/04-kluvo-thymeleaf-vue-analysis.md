# Kluvo 분석 및 Thymeleaf–Vue 연동 상세 설계

> 2026-09-16: API 계약과 실제 퍼블리싱 구조는 [ADR-0006](../adr/0006-admin-api-contract-and-publishing.md) 및 계획 06의 현재 상태를 우선합니다. 아래 내용은 최초 설계 기록입니다.

- 분석일: 2026-09-15
- 범위: `C:\projects\kluvo\kluvo-web`, `C:\projects\kluvo\kluvo-admin`의 현재 파일 정적 분석. 앱·테스트 실행은 하지 않았다.
- 결과: kkdugi 문서 확장만 수행. Kluvo 소스와 kkdugi 애플리케이션 소스는 변경하지 않는다.
- 관련 결정: [ADR-0004](../adr/0004-thymeleaf-vue-sfc-runtime.md). 기존 디자인·배치·다국어 정책은 유지한다.

## 1. 현재 Kluvo가 붙이는 방식

```text
admin/index.html (Thymeleaf shell)
  → 메뉴 선택 / KluvoTabManager.open
  → POST /api/v1.0/pragma {id, params}
  → PragmaService: 파일 존재 확인 → READ 권한 검사 → handler 초기 데이터
  → vue-pragma.html: initData와 id를 JavaScript literal로 직렬화
  → shell: fragment 삽입 → executeScripts
  → kluvo-pragma.js: Vue/SFC loader 로드 → /pragma/{id}.vue 컴파일
  → createApp(component, {initData}) → provide('kluvo', ...) → mount
```

Thymeleaf가 `.vue` 파일 자체를 렌더링하는 구조가 아니다. Thymeleaf는 shell과 부트스트랩 HTML을 렌더링하고, 정적 `.vue` SFC(single-file component)는 브라우저의 vue3-sfc-loader가 처리한다. 따라서 정적 `.vue` 안의 `th:text`나 `#{...}`가 자동으로 Spring message 처리된다고 가정하면 안 된다.

## 2. 소스 근거

아래 경로는 분석 대상 루트 기준이다. 줄 번호는 분석 시점이며 실행 검증을 의미하지 않는다.

| 프로젝트 / 파일 | 확인 내용 |
|---|---|
| web `src/main/java/kluvo/web/pragma/PragmaController.java` | POST `/api/v1.0/pragma`, HTML 응답, 미존재·권한 예외 응답 |
| web `src/main/java/kluvo/web/pragma/PragmaService.java:42` | 파일 존재·권한 확인, handler 결과를 initData로 넣고 공통 템플릿 렌더 |
| web `src/main/java/kluvo/web/pragma/PragmaContextHolder.java` | static Map 레지스트리, 동일 ID 재등록 시 덮어쓰기 |
| web `src/main/resources/templates/vue-pragma.html` | `th:inline="javascript"`, `document.currentScript.previousElementSibling`, 동적 import |
| web `src/main/resources/static/scripts/libs/kluvo-pragma.js:25` | Vue 3.4.21 ESM·SFC loader 0.9.5 ESM을 CDN에서 import |
| web `src/main/resources/static/scripts/libs/kluvo-pragma.js:48` | componentCache, moduleCache.vue, style 삽입, createApp·provide·mount |
| web `src/main/resources/static/scripts/libs/kluvo-tab-manager.js:87` | 닫기 시 DOM remove, 재로드 시 122행에서 innerHTML 초기화 |
| web `src/main/resources/static/scripts/libs/kluvo-commons.js` | apiFetch와 fragment script 재실행 유틸리티 |
| admin `src/main/resources/templates/index.html` | 실제 관리자 shell, 메뉴/세션 조회, 프로그램 fragment 로드 및 탭 조작 |
| admin `src/main/resources/static/scripts/libs/kluvo-dialog.mjs:23` | 로컬 Vue/SFC loader, 팝업 SFC 허용 경로, Promise 캐시 |
| admin `src/main/resources/static/scripts/libs/kluvo-dialog.mjs:57` | 팝업 app.unmount, 초점 복귀, owner 감시, 실패 시 입력 보존 |
| admin `src/main/resources/static/scripts/libs/kluvo-grid.mjs` | AG Grid 31.3.2 로컬 loader, 공통 상태 열·renderer |
| admin `src/main/resources/static/pragma/ADCODE.vue:154` | script setup, inject('kluvo'), `.mjs` 공통 모듈 사용 |
| admin `src/main/resources/static/pragma/ADCODE.vue:967` | onBeforeUnmount에서 grid.destroy·전역 이벤트 제거 |
| admin `src/main/resources/static/pragma/ADMSGE.vue` | 메시지 사전 조회·행 배치 저장·Grid 생명주기 사용 |
| admin `src/main/resources/static/scripts/libs/kluvo-i18n.mjs` | locale 결정, 누락 key 표시, 기존 번역을 보존한 언어 목록 병합 |
| admin `src/main/java/kluvo/app/admin/AdminUiMessages.java` | 키 목록을 열거해 Spring MessageSource로 UI 사전 생성 |
| admin `package.json`, `PRAGMA-UI.md` | Tailwind CSS 빌드, 공통 CSS·팝업 규칙. Vue SFC 빌드 명령은 package.json에 없음 |
| web `src/test/java/kluvo/web/pragma/VuePragmaTemplateTest.java` | mount point·초기 데이터 직렬화 확인용 테스트 소스 존재; 이번에는 실행하지 않음 |

로컬 팝업 자산 헤더도 Vue 3.4.21 / vue3-sfc-loader 0.9.5로 확인했다. 같은 버전이어도 CDN ESM과 로컬 global은 별도 런타임 인스턴스가 된다. Kluvo 버전은 참고 관찰값이며 kkdugi 채택 버전 확정값이 아니다.

## 3. 가져올 구조와 보완할 부분

| 관찰 | kkdugi 적용 |
|---|---|
| web가 부트스트랩, admin이 업무 SFC 제공 | 공통 런타임과 5개 업무 SFC를 분리하되 현재 단일 Maven 구조 유지 |
| initData props + provide/inject | 초기 서버 값은 props, 공통 서비스는 inject('kkdugi')로 단일 계약 |
| SFC의 `.mjs` helper import | 순수 helper만 `.mjs` import; 런타임 상태가 있는 API·Dialog는 주입 |
| 프로그램은 CDN, 팝업은 로컬 Vue | 모든 화면·팝업이 같은 로컬 Vue와 loader, 동일 moduleCache 사용 |
| 프로그램 mount가 app/해제 함수를 반환하지 않음 | mount는 PageHandle 반환, 전환·닫기·재로드 전에 dispose 호출 |
| 탭 DOM 제거와 Vue unmount가 연결되지 않음 | 컴포넌트 cleanup hook 존재만으로 해제가 보장되지 않으므로 host가 app.unmount 실행 |
| 프로그램 cache는 컴파일 완료 후 등록 | 진행 중 Promise도 공유해 중복 컴파일 방지, 실패 캐시 제거 |
| 프로그램 스타일을 head에 계속 추가 | 초기 관리 SFC는 공통 CSS 사용; 전용 CSS는 버전별 외부 파일로 한 번 로드 |
| Dialog는 owner 해제·저장 오류 보존 지원 | 구조 채택 + dirty 취소 가드, 부모 종료·저장 중 처리 정책 명시 |
| fragment를 넣고 script를 다시 실행 | 초기 kkdugi는 정적 shell bootstrap + JSON page descriptor로 명시적으로 mount 호출 |
| API helper가 HTTP 오류를 일반 Error로 축약 | status·errorCode·행/셀 errors·requestId를 보존해 배치 오류 매핑 |
| 일부 shell 오류·메뉴 문자열을 innerHTML에 삽입 | 동적 값은 textContent/Vue text binding; 서버 HTML/script 실행 경로를 새로 늘리지 않음 |
| 새 Context()에 locale를 명시하지 않음 | 요청 locale를 명시하고 shell·사전·Grid가 같은 locale 사용 |
| URL/cookie/lang을 화면별로 재계산 | 서버가 확정한 locale를 초기 주입하고 공통 i18n만 접근 |
| 메뉴 코드로 파일 경로 조립, static registry 덮어쓰기 | 명시적 등록 화면 5개 allowlist, Spring bean registry 중복 ID 기동 실패 |

위 보완은 읽은 소스 경로의 구조적 차이이며 실제 메모리 누수·공격 성공·운영 장애를 재현했다는 뜻은 아니다.

## 4. kkdugi 목표 흐름

1. 서버가 `/admin`의 Thymeleaf shell을 렌더링한다. 요청 locale, contextPath, assetVersion, 허용 메뉴, bootstrap endpoint를 안전하게 직렬화한다. 라우트 이름은 제안이다.
2. shell은 로컬 Bulma→토큰 CSS→Pretendard/line-awesome 및 버전 고정 Vue/SFC loader를 로드한다. AG Grid는 공통 Promise로 필요 시 로드한다.
3. 화면 코드 선택 후 PageHost가 현재 변경의 저장/버림/취소를 해결한다.
4. 서버의 페이지 초기화 JSON을 조회한다. 서버는 인증·화면 등록·메뉴 접근을 검사한 뒤 해당 화면의 initData와 UI 메시지를 반환한다.
5. PageHost는 등록된 SFC를 공통 loader로 컴파일하고 `createApp(component, {initData})`를 생성한다. 동일 Vue 인스턴스로 서비스를 provide한다.
6. 메시지·필수 초기 데이터가 준비된 후 mount한다. 업무 목록의 페이징 조회는 별도 API로 수행한다.
7. 재조회·다른 화면 이동·로케일 변경은 공통 가드를 거친다. 화면 교체 시 abort·팝업 종료·app.unmount 후 DOM을 제거한다.

초기 화면은 하나만 활성화한다. Kluvo의 10개 탭·HOME·위젯·조직 메뉴는 사용자 요구가 없어 복제하지 않는다. 나중에 탭을 도입해도 PageHandle 수명주기 계약을 재사용한다.

## 5. 제안 계약

### 서버 초기화

`GET /api/admin/pages/{screenCode}/bootstrap`은 새 계약 제안이며 현재 kkdugi에 구현되어 있지 않다. screenCode는 `admcode`, `admmsge`, `admmenu`, `admauth`, `aduser`만 허용한다. 임의 URL이나 파일 경로를 요청 인자로 받지 않는다.

```json
{
  "screenCode": "admcode",
  "locale": "ko_KR",
  "assetVersion": "release-id",
  "permissions": {"read": true, "save": true},
  "messages": {"admin.btn.save": "저장"},
  "languages": [{"code": "ko_KR", "label": "한국어"}],
  "initData": {"parentId": null}
}
```

permissions는 UI 사용성 정보이며 실제 업무 API 권한을 대체하지 않는다. languages의 원본은 시스템 등록 언어, messages 키 목록은 공통+해당 화면의 명시적 manifest다. Spring MessageSource 자체가 모든 DB 키를 열거한다고 가정하지 않는다. 기존 메시지 목록 GET/배치 POST는 이 endpoint와 별개다.

### 클라이언트 서비스

```text
inject('kkdugi')
  api.request(url, options) → JSON 또는 null; ApiError 구조 보존
  i18n.t(key, args), i18n.locale, i18n.languages
  dialog.alert(...), dialog.confirm(...), dialog.open(...) // ADR-0005의 단일 Vue 서비스
  grid.ensure(), grid.commonOptions()
  lifecycle.register({isDirty, save, discard})
```

서비스 계약은 설계용이며 실행 가능한 완성 코드가 아니다. API 헤더는 기본값·호출자 헤더를 명시적으로 병합하고 credentials·CSRF·AbortSignal 정책을 공통화한다. JSON 외 응답은 별도 명시적 메서드로 분리한다. UI 번역용 사전 조회와 메시지 편집용 CRUD를 혼동하지 않는다.

### PageHandle / 상태

```text
mount(host, descriptor) → Promise<PageHandle>
PageHandle: screenCode, isDirty(), save(), discard(), dispose()
상태: idle → loading → mounted → saving → mounted → disposing → disposed
실패: loading → loadError(재시도), saving → mounted(입력·오류 보존)
```

- PageHost가 핸들을 소유한다. 컴포넌트는 lifecycle 서비스에 배치 상태·저장 핸들러를 등록한다.
- dispose는 여러 번 호출해도 안전하며 진행 중 fetch/observer/timer/listener 정리와 Vue unmount를 끝낸다. Grid는 component onBeforeUnmount에서 destroy한다.
- 로딩 generation ID로 늦게 도착한 이전 응답을 버린다. 공통 컴파일 Promise는 다른 사용자가 있을 수 있어 화면 하나의 취소로 공유 작업 전체를 취소하지 않는다.
- 서버 저장 중 정상 화면 전환을 막는다. 강제 종료/응답 유실은 저장 실패로 단정하지 않고 재조회로 확인한다.
- 브라우저 닫기/새로고침은 native beforeunload 경고만 기대한다. 비동기 ‘저장 후 이동’은 앱 내부 전환에서만 보장한다.

### Dialog

컴포넌트 ID를 registry로 해석하고 외부 URL은 받지 않는다. props는 편집용 복사본. 적용은 배치 초안 반영 또는 즉시 API 저장 중 화면 명세에 정한 방식 하나만 수행한다. 저장 실패 시 팝업과 입력을 유지한다. 결과는 ADR-0005의 submitted/cancelled 객체로 반환한다. 부모 정상 종료 전에 dirty 팝업도 가드를 거치며 저장 중 팝업을 조용히 폐기하지 않는다. Popup과 Dialog는 shell까지 Vue로 통합하며 OverlayRoot app 하나의 DialogHost가 본문을 생성·해제한다. 개별 dialog마다 createApp하지 않는다.

## 6. 파일 구성 제안

```text
src/main/resources/
  templates/admin/index.html
  templates/admin/fragments/{sidebar,header}.html
  static/vendor/{vue,sfc-loader,bulma,pretendard,line-awesome,ag-grid}/<고정 버전>/
  static/admin/js/{bootstrap,page-host,runtime,api,i18n,dialog,grid}.mjs
  static/admin/pages/{admcode,admmsge,admmenu,admauth,aduser}.vue
  static/admin/components/dialogs/*.vue
  static/admin/css/{tokens,components,admin}.css
```

화면별 HTML 5개 제안은 공통 shell 1개 + 화면 SFC 5개로 구체화한다. 정적 SFC는 Thymeleaf 처리 대상이 아니다. root-context를 가정한 `/scripts/...` 복사는 피하고 서버 contextPath 기반 URL resolver를 쓴다. 로컬 파일 더블클릭이 아니라 HTTP 환경을 후속 검수 기준으로 삼는다.

## 7. 자산·캐시·배포 경계

- Kluvo에서 Vue/SFC loader의 로컬 복사 후보를 찾았으나 kkdugi libraries에 복사하지 않았다. 라이선스·해시·호환성을 확인한 후 명시적으로 반입한다.
- SFC loader는 사용자 지정 스택에 추가되는 지원 라이브러리다. Vue 3 + script setup + helper import + 팝업 + AG Grid 조합을 먼저 검증한다. Tailwind와 Kluvo 색상·작은 11px 버튼은 가져오지 않는다.
- 운영 assetVersion으로 전체 정적 배포물을 고정하고 컴파일 cache는 version+component path 기준으로 잡는다. 실패한 Promise는 제거한다. `.vue`뿐 아니라 의존 `.mjs`도 같은 release 경로를 따른다.
- 사용자·locale 의존 bootstrap 데이터는 사용자 사이에 공유 cache하지 않는다. 초기 버전은 민감한 JSON 응답 no-store 제안. 컴파일 캐시에 초기 데이터·사용자 상태를 넣지 않는다.
- 런타임 컴파일과 배포 CSP의 호환성·초기 로딩 비용은 실측 대상이다. 임의로 정책을 완화하지 않는다. 충돌 시 SFC 사전 컴파일 + 같은 PageHost 계약으로 전환하는 후속 ADR을 작성한다.
- Kluvo의 `org.thymeleaf.spring6` Java import는 kkdugi Spring Boot 4 환경에 그대로 복사하지 않고 실제 starter가 제공하는 타입으로 검증한다.

## 8. 후속 검증

1. 최소 SFC 하나로 로컬 Vue/loader·script setup·`.mjs` import·initData·provide/inject·공통 CSS를 검증한다.
2. `</script>`·따옴표·개행·한글이 든 초기 데이터가 실행 코드로 바뀌지 않고 그대로 표시되는지 검사한다.
3. 화면→팝업→닫기→재진입을 반복하여 Vue 중복 로드, 이벤트/observer/Grid 인스턴스 잔존, 중복 요청을 검사한다.
4. 로딩 직후 전환·연속 재로드·로딩 실패 재시도에서 이전 화면이 뒤늦게 mount되지 않아야 한다.
5. 세션 만료·403·404·500·검증 오류·HTML 로그인 응답 오인·204를 구별하고 배치 셀 오류를 보존한다.
6. 한국어/영어 변경, contextPath `/kkdugi`, 외부 인터넷 차단, CSP 정책 적용에서 동작을 확인한다.
7. 미저장 화면·미저장 팝업·저장 중 전환·브라우저 새로고침의 경고와 보존 범위를 확인한다.

## 공식 문서 보조 근거

- [Vue Application API](https://vuejs.org/api/application): mount/unmount와 app.provide의 수명주기·주입 계약 참고.
- [vue3-sfc-loader API](https://github.com/FranckFreiburger/vue3-sfc-loader/blob/main/docs/api/README.md): 공유 moduleCache, getFile, addStyle 계약 참고. `.mjs` 제약은 Kluvo의 현재 loader 설정에 맞춘 규칙이며 모든 ESM 도구의 일반 제약이 아니다.
- [Thymeleaf JavaScript inlining](https://www.thymeleaf.org/doc/tutorials/3.0/usingthymeleaf.html): JavaScript literal 직렬화 방식 참고; kkdugi 실제 설치 버전에서 검증한다.
