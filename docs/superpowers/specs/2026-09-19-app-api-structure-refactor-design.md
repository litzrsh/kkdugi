# app / api 구조 리팩터링 설계

- 작성일: 2026-09-19
- 상태: 사용자 승인된 설계 (구현 계획 작성 전)
- 브랜치: `refactor/app-structure` (기준 커밋 `e883db9`)

## 배경과 목표

현재 code / menu / i18n 기능은 DB 행 모델·mapper를 `core.{code,menu,i18n}`에,
모델·예외·서비스를 `app.admin.*`에 두고 있고, `app.<기능>` 계층이 없다.
`AdminXxxService`가 core mapper를 read/write 구분 없이 직접 호출한다.
API는 관리자 컨트롤러(`/api/v1.0/admin/*`)와 세션 메뉴
(`/api/v1.0/session/menu`)만 있다.

프로젝트가 커지면 기능별로 별개 라이브러리 프로젝트로 떼어낼 계획이므로,
**사용자용 기능(`app.<기능>`)과 관리자용 기능(`app.admin.<기능>`)을
데이터 모델부터 분리**한다. 사용자에게 보이는 데이터와 관리자 기능이 필요로
하는 데이터가 다르므로 서로의 모델·mapper를 공유하지 않는다.

## 목표 구조

```
kkdugi
├─ core        공통 인프라만 (models, util, enums, serial, security, exceptions,
│              mybatis, i18n 의 MessageSource 인프라)
├─ app
│   ├─ code    models(Code, CodeParams) / mapper(CodeMapper) / service(CodeService)
│   ├─ menu    models(Menu) / service(MenuService)      ※ 세션 기반이라 mapper 없음
│   └─ admin
│       ├─ code   models / exceptions / mapper(AdminCodeMapper) / service(AdminCodeService)
│       ├─ menu   (동일 패턴)
│       └─ i18n   (동일 패턴)
└─ api
    ├─ MenuController, CodeController                  /api/v1.0/menu, /api/v1.0/code
    └─ admin
        ├─ AdminCodeController, AdminMenuController,
        └─ AdminMessageController                      /api/v1.0/admin/{code,menu,i18n}
```

### 의존 규칙

- `api → app → core`. `app.admin.*` 는 `app.*` 를 import하지 않는다
  (그 반대도 마찬가지). 각각 core만 의존해야 라이브러리로 분리할 수 있다.
- 기능별 하위 패키지는 기존 컨벤션(`models`, `mapper`, `service`, `exceptions`,
  `config`)을 그대로 따른다. `docs/conventions/common-base-model.md` 의
  BaseModel/BaseParams 상속, `Page<T>` 쿼리 레벨 페이징, `SerialConfig`/
  `SerialUtils` 규칙은 변하지 않는다.

### 이동 대상

| 현재 | 이후 |
|---|---|
| `core.code.models.{CodeBase,CodeLang,CodeValue}` | `app.admin.code.models` |
| `core.code.mapper.{CodeBaseMapper,CodeLangMapper}` | `app.admin.code.mapper.AdminCodeMapper` 로 통합 |
| `core.menu.models`, `core.menu.mapper` | `app.admin.menu.*` (동일 방식) |
| `app.admin.code.{models,exceptions,service}` | 같은 위치, `Admin` 접두사 부여 |
| `api.admin.*.{Code,Menu,Message}AdminController` | `api.admin.Admin{Code,Menu,Message}Controller` |
| `api.session.{SessionMenuController,MenuTreeItem}` | `api.MenuController`, `app.menu.models.Menu` |
| `core.i18n.*` (MessageSource 인프라) | 그대로 core에 유지 |

i18n은 Spring `MessageSource` 구현(`KkdugiMessageSource`, `I18nMessageMapper`)이
런타임 인프라이므로 core에 둔다. 관리자 쓰기/검색만 `AdminMessageMapper` 로 분리하며,
저장 시 `KkdugiMessageSource` 캐시 무효화 호출은 기존대로 유지한다.
사용자용 `app.i18n` 은 만들지 않는다.

## mapper 메서드 분리 (읽기/쓰기)

| mapper | 메서드 |
|---|---|
| `CodeMapper` (사용자) | use=Y, 현재 언어 이름 해석을 SQL에서 처리하는 read 전용: `findChildren(parentId, langCode)`, `findByPath(path, langCode)` |
| `AdminCodeMapper` | 기존 `CodeBaseMapper` + `CodeLangMapper` 통합: `search`(use 무관, `COUNT(*) OVER()` 페이징), `findById`, `findSelfAndDescendants`, `insert`/`update`/`deleteByIds`, `insertLang`/`updateLang`/`deleteLangByCodeIds`, `findLangsByCodeIds` |

- 두 mapper는 resultMap/XML을 공유하지 않는다.
- XML은 패키지를 미러링한다: `mapper/postgres/app/code/CodeMapper.xml`,
  `mapper/postgres/app/admin/code/AdminCodeMapper.xml` (menu, i18n도 동일).
  `mapper-locations` 는 이미 재귀 glob 이고 `@Mapper` 어노테이션 스캔이라
  설정 변경은 필요 없다.
- namespace, resultMap `type`, SQL 주석의 QueryID 는 새 FQCN으로 갱신한다.
- 메뉴는 사용자 쪽 데이터가 로그인 시 세션에 이미 로드되므로
  (`core.security` 의 `SessionMenu`) `app.menu` 에 mapper를 만들지 않는다.

## API 변경

| 항목 | 변경 |
|---|---|
| 세션 메뉴 | `/api/v1.0/session/menu` → `/api/v1.0/menu`. 옛 경로 별칭은 두지 않는다 |
| 관리자 API | URL 유지 (`/api/v1.0/admin/{code,menu,i18n}`), 클래스 이름만 `Admin*` |
| 공통코드 조회 (신규) | `GET /api/v1.0/code` — 부모 기준 하위 코드, 현재 언어 이름, use=Y만. 최소 read 하나만 만들고 `docs/api/code.md` 에 계약을 문서화한다 |

URL별 보안 규칙은 없으므로(`anyRequest().permitAll()`) 경로 이전에 따른
`SecurityConfigurer` 변경은 없다. 하드코딩된 URL은
`static/js/api/index.mjs`, `src/test/js/current-contract.test.mjs`,
`src/test/java/.../SessionMenuControllerTest.java`,
`AuthenticationProcessingFilterTest.java` 에서 갱신한다.

## 이름 규칙

`app.admin` 쪽 모델·예외·서비스·mapper·컨트롤러에는 `Admin` 접두사를 붙인다
(`CodeContent` → `AdminCode`, `CodeSearchParams` → `AdminCodeParams`,
`CodePersistRequest` → `AdminCodePersistRequest`, `CodeConflictException` →
`AdminCodeConflictException` …). 같은 이름의 사용자용 클래스(`Code`, `CodeParams`)와
import가 섞이지 않게 하기 위해서다. 응답 JSON 필드 이름은 바꾸지 않는다
(DB 컬럼명 노출 금지 규칙 유지).

## 진행 순서 (점진적으로)

1. **code** — 견본. `app.code` 신설, `core.code` → `app.admin.code` 이동,
   mapper 통합/분리, `CodeController` 신설, 테스트 이전.
2. **menu** — `core.menu` → `app.admin.menu`, `api.session` → `api.MenuController` +
   `app.menu`, `/api/v1.0/menu` 이전과 프런트 URL 갱신.
3. **i18n** — `AdminMessage*` 로 이름 정리, `AdminMessageMapper` 분리.

각 단계가 끝날 때마다 `mvn test`(및 `src/test/js` 테스트)가 통과해야 다음으로
넘어간다. 한 번에 전부 바꾸지 않는다.

## 문서 갱신

- `CLAUDE.md` 패키지 구조 트리와 관련 서술
- `docs/conventions/common-base-model.md`, `docs/api/{README,session,menu,common-code,i18n-message}.md`
- 신규: `docs/adr/0016-app-and-admin-feature-split.md`, `docs/api/code.md`

## 범위 밖

- 사용자용 code 외 신규 API (menu/i18n 사용자 API는 이번에 만들지 않는다)
- authority / users 도메인 (기존대로 별도 단계)
- `core.security` 내부 구조 변경 (세션 메뉴 로딩 경로 그대로)
- DB 스키마 변경 (Flyway 마이그레이션 없음)

## 위험과 확인 사항

- `/api/v1.0/session/menu` 를 쓰는 외부 클라이언트가 있으면 깨진다
  (별칭 없이 이전하기로 결정 — 프런트가 같은 레포이고 릴리스 전이므로).
- mapper 통합 시 기존 `CodeBaseMapper.findChildren` 의 페이징 파라미터
  (`offset`, `pageSize`)와 `COUNT(*) OVER()` 동작이 `search` 로 그대로 옮겨졌는지
  기존 `CodeAdminServiceTest` 가 그대로 통과하는지로 검증한다.
- 구현 계획 단계에서 `static/js/api/http.mjs` 의 요청 경로 허용 규칙이
  `/api/v1.0/menu` 를 막지 않는지 확인한다.
