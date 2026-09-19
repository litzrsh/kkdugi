> 2026-09-19: 문서는 [docs/design](../docs/design/README.md)으로 통합했습니다. 실제 서버 구현과 로그인 계약은 [최신 연동 기록](../docs/design/plan/14-session-auth-confirm-and-docs.md), [현행 API](../docs/api/README.md)를 참조합니다. 아래는 독립 미리보기의 과거 퍼블리싱 기록입니다.

# kkdugi admin · 디자인 & 퍼블리싱

2026-09-16 / Spring Thymeleaf + Vue 3 SFC / Bulma / Pretendard / line-awesome / AG Grid

## 실행

이 폴더에서 Node.js 22 이상으로 실행한다. npm 설치·프론트 빌드는 필요 없다.

```powershell
node scripts/preview.mjs
```

브라우저에서 `http://127.0.0.1:4173/`을 연다. 서버는 0.0.0.0:4173으로 바인딩하며 내부망에서도 접속할 수 있다. `#admcode`, `#admmsge`, `#admmenu`, `#admauth`, `#aduser`로 초기 화면을 지정할 수 있다.

미리보기는 메모리 예시 데이터로만 동작한다. 추가·수정·삭제·매핑·비밀번호 초기화·상태 변경은 실제 서버를 호출하지 않고 새로고침하면 초기화된다. 사용자·이메일은 가상 데이터다. 화면에 미리보기 표시가 유지된다.

## 구현 범위

- 새 라이트 디자인: 회백색 배경, 청록 강조, 얕은 경계, Pretendard. Kluvo 디자인·Tailwind는 복사하지 않았다.
- 5개 메뉴, AG Grid 목록, 검색·서버형 페이지 계약, 공통코드 계층 이동.
- 코드·메시지·메뉴 혼합 배치 CRUD, 변경 건수, 삭제예정/복원, 이동 전 저장·버림·취소.
- 로케일별 코드/메뉴 필드 및 메시지 번역 편집. 메시지 코드 패턴 검증.
- 권한 등록/수정/삭제, 메뉴 READ/WRITE 및 응답에 포함된 추가 권한 플래그, 사용자 매핑·기간.
- 사용자 등록/수정/권한 매핑, 비밀번호 초기화, 영구 삭제 확인, 상태 일괄 변경.
- Vue DialogHost 한 곳에서 알림·확인·폼·매핑 처리. 입력 취소 보호, 제출 중 중복 동작 차단, 실패 입력 유지.
- 모바일 drawer 탐색·단열 검색·44px 터치 영역·그리드 가로 이동·전체 화면 편집 Dialog. virtual viewport 높이 대응.
- 실제 API용 adapter, Spring 메시지 번들, Thymeleaf 연결 템플릿.

## Spring 프로젝트로 연결

`src/main/resources/templates/admin/index.html`과 `static/admin`, `static/vendor`를 실제 Spring 애플리케이션의 같은 resource 위치에 통합한다. 이번 작업에서는 `kkdugi-admin`의 Java·설정·브랜치를 변경하지 않았다.

컨트롤러는 `admin/index` 뷰를 반환하고 `adminUiConfig` 모델을 제공해야 한다. 이 모델은 기존 관리 CRUD API를 대체하는 새로운 공개 endpoint가 아니라 Thymeleaf 화면 초기 설정이다.

```json
{
  "mode": "live",
  "locale": "ko_KR",
  "languages": [{"code":"ko_KR","label":"한국어"},{"code":"en_US","label":"English"}],
  "statuses": ["서버에 등록된 실제 상태값"],
  "messages": {"admin.ui.admcode":"공통코드관리","admin.ui.save":"변경 저장"},
  "csrf": {"header":"서버 CSRF 헤더 이름","token":"요청별 실제 토큰"}
}
```

- `languages`, `statuses`는 서버가 등록 데이터에서 공급한다. preview의 ACTIVE/INACTIVE/LOCKED는 예시이며 운영 enum으로 확정하지 않는다.
- messages는 `src/main/resources/messages/admin-ui_*.properties`의 모든 키를 Spring MessageSource로 요청 locale에 맞게 해석해 제공한다. 기존 번들에 병합하거나 basename에 추가한다. JSON의 messages 예시는 축약본이다.
- 운영 모드는 누락된 UI 메시지에 key를 표시한다. 미리보기만 JS 예시 사전을 쓴다. 메시지 번들은 `node scripts/export-messages.mjs`로 재생성한다.
- locale 전환은 `?lang=`으로 서버 페이지를 다시 요청한다. 서버 LocaleResolver/LocaleChangeInterceptor 등에서 이 파라미터를 처리해야 한다.
- contextPath는 Thymeleaf `@{/}`에서 받는다. POST에 same-origin credentials와 제공된 CSRF header를 보낸다.
- 인증·메뉴 접근·쓰기 권한·기본 메뉴 보호는 실제 서버에서 최종 검사한다. 현재 shell의 고정 메뉴는 권한별 탐색 조회 API가 정의되지 않은 상황의 퍼블리싱 기준이다.
- 운영 페이지에서 preview 모드를 설정하지 않는다. `preview.html`과 `demo.mjs`는 개발 확인용이며 운영 배포 목록에서 제외할 수 있다.

## API 계약과 적용 해석

원본: `C:\projects\kkdugi\docs\api-define-admin.md` (2026-09-16 확인).

| 동작 | 경로 / 방식 |
|---|---|
| 코드·메시지·권한·사용자 목록 | POST `/api/v1.0/admin/{code,i18n,authority,user}` |
| 메뉴 계층 조회 | GET `/api/v1.0/admin/menu` |
| 코드·메시지·메뉴 배치 | POST `/api/v1.0/admin/{code,i18n,menu}/persist` |
| 권한·사용자 상세 | GET `/api/v1.0/admin/{authority,user}/{id}` |
| 권한·사용자 등록/수정 | POST `.../{resource}/regist` 또는 `.../{resource}/{id}` |
| 권한 메뉴/후보 사용자 | POST `.../authority/{id}/menu`, `.../authority/{id}/user` |
| 사용자 권한 | GET/POST `.../user/{id}/authorities` |
| 비밀번호 초기화 | POST `.../user/reset-password` `{id:[...]}` |
| 상태 일괄 변경 | POST `.../user/change-status` `{id:[...],status}` |
| 권한·사용자 삭제 | POST `.../{authority,user}/{id}/delete` |

page는 1부터, 결과는 contents/totalItems/totalPages, 배치는 insert/update/delete 객체. 신규 id는 빈 문자열, DB 채번 결과는 저장 뒤 재조회한다. 코드·메뉴 삭제는 하위 cascade를 알린다. 메시지는 locale 객체를 한 행으로 보존한다.

### 남아 있는 원문 모호성

1. 원문에 `authroity`, 권한 저장 `addmin`, 사용자 삭제 `/api/v1.0/user`가 남아 있어 기본 adapter는 일관된 `/api/v1.0/admin/...`를 사용한다. 경로가 다르면 `adminUiConfig.endpoints.authorityDelete`, `userAuthorities`, `userDelete`에 `{id}`를 포함한 문자열 경로를 공급하면 된다. endpoint 자동 재시도는 하지 않는다.
2. 권한 저장 예시 배열에 키를 넣은 문법은 JSON 객체 `{insert,update,delete}`로 해석했다.
3. 권한 상세는 users가 문자열 ID이며 적용 기간이 없다. 기존 사용자의 `/user/{id}/authorities`에서 기간을 조회해 보존한다. 그 관계가 조회되지 않으면 덮어쓰기하지 않고 오류를 표시한다.
4. 메뉴 신규 등록에는 parentId가 없다. 새 메뉴 path에 기존 부모 path(루트는 빈 값), level에 깊이를 전송하며 서버가 새 id를 채번하고 경로를 완성한다고 가정했다. 신규 부모 아래 추가는 부모 저장 후 가능하다. 이 부분은 실제 서버 연동 시 확인한다.
5. locale 값의 빈 문자열은 그대로 전송한다. 번역 키 삭제의 서버 의미는 정의서에 없으므로 별도 DELETE를 만들어 보내지 않는다. 명시적 번역 삭제 정책은 후속 계약이다.
6. 정의서에는 에러 상세 스키마가 없어 HTTP별 안내와 adapter의 원문 errors 보존까지 구현했다. 셀별 서버 오류 매핑은 응답 필드 확정 후 연결한다.
7. 저장 성공 응답 본문이 정의되지 않아 성공 후 목록을 재조회한다. 갱신 실패 시 서버 ID 미확정 초안으로 다시 저장하지 않도록 목록을 비우고 재조회를 안내한다.

## 파일 구성

- `App.vue`: 5개 관리 화면의 공통 shell·폼/목록·상태 및 화면별 작업
- `Grid.vue`: AG Grid 생성·갱신·destroy
- `DialogHost.vue`: 공통 Vue dialog shell·본문·결과·취소 보호
- `domain.mjs`: 화면 정의, 배치 diff, 검증, 메뉴 보호
- `api.mjs`: 운영 HTTP 계약 / `demo.mjs`: 독립 메모리 fixture adapter
- `admin.css`: desktop/mobile 디자인 토큰·컴포넌트

이번 1차 퍼블리싱은 공통 패턴을 하나의 App SFC에서 screenDefs로 전환한다. 화면별 SFC 5개와 별도 PageHost/OverlayRoot app을 만들지는 않았다. 같은 Vue app 안에서 Grid를 화면별로 해제하고 DialogHost를 공유해 서비스·수명주기 중복을 줄였다.

## 검증

```powershell
node --test tests/contracts.test.mjs
node scripts/check-sfc.mjs
node scripts/check-render.mjs
```

계약 테스트 11건, 3개 SFC 실제 loader 컴파일, 5개 화면·Dialog의 메모리 renderer smoke 검사, HTTP 200·자산 경로 검사를 수행한다. 메모리 renderer의 AG Grid/DOM은 test double이므로 브라우저 렌더링 검사를 대신하지 않는다.

현재 환경에서 제어 가능한 브라우저가 연결되지 않아 실제 화면 시각 검수·터치·가상 키보드·iOS/Android 검수는 미완료다. 실제 Spring API·인증·CSRF·DB 저장 연동도 아직 검증하지 않았다.

## 자산

Vue 3.4.21, vue3-sfc-loader 0.9.5는 사용자가 지정한 Kluvo 로컬 자산을 재사용했다. AG Grid Community 36.1.0, Pretendard 1.3.9, line-awesome 1.3.0은 제공된 libraries를 사용했다. Bulma 1.0.4는 공식 npm 배포 CDN에서 로컬로 확보했다. 라이선스 파일을 함께 포함한다. 실행 시 CDN 요청은 없다. 런타임 SFC 컴파일의 운영 CSP 호환성은 실제 배포 환경에서 확인한다.

## 로그인 화면

미리보기: http://127.0.0.1:4173/login.html. HTML + CSS3 + vanilla JS로 구현하며 Vue를 로드하지 않는다. 서버 연동은 ../docs/design/plan/07-login-publishing.md를 참고한다.
