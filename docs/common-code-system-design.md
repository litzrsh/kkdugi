# kkdugi-admin 공통코드 시스템 설계

- 작성일: 2026-09-16
- 작성자: litzrsh (with Claude)
- 상태: 승인됨, 구현 완료 (트리 재구성은 후속 작업 — 아래 "후속 작업" 참고)
- 관련 ADR: [ADR-0011](adr/0011-api-define-admin-contract-and-record-models.md)
  (API 계약/패키지 규약의 기반), [ADR-0012](adr/0012-common-code-system.md)
  (이 시스템 고유의 결정: 채번, 계층 저장/삭제, 검색 범위)

## 배경

`docs/api-define-admin.md` 1절("공통코드관리")의 계약을 구현한다. i18n
시스템([docs/i18n-system-design.md](i18n-system-design.md))에 이어 두
번째로 구현된 관리 화면이며, 같은 패키지 규약과 `Page<T>` 응답 래퍼를
그대로 재사용한다. 이번 작업은 백엔드 API까지만 다룬다 — 화면/프론트엔드는
범위 밖이다(다른 곳에서 진행).

## 데이터 모델

ERD의 `KKDUGI_CODE_BASE`/`KKDUGI_CODE_LANG`/`KKDUGI_SERIAL_BASE`를 그대로
따른다(Flyway `V2__create_serial_base.sql`, `V3__create_code.sql`,
`V4__create_fn_get_serial.sql`).

**`kkdugi_code_base`** — 코드 트리의 각 노드 1행.

| 컬럼 | 설명 | 비고 |
|---|---|---|
| CODE_ID | 코드 ID | PK, 서버 채번(`SerialConfig`/`fn_get_serial` 기반, 아래 참고) |
| CODE_PARENT_ID | 상위 코드 ID | root면 NULL |
| CODE_VAL | 코드 값(경로 세그먼트) | `(CODE_PARENT_ID, CODE_VAL)` 유니크, root는 별도 부분 유니크 인덱스로 중복 방지 |
| ETC_VAL1~5 | 추가 값 5개 | nullable |
| CODE_LVL | 계층 레벨 | root = 0 |
| CODE_PATH | `CODE_VAL`을 `/`로 이어붙인 경로 | 예: `/SYS/USER/STAT` |
| SORT_SEQ | 정렬 순번 | nullable |
| USE_YN | 사용 여부 | `Y`/`N`, 기본 `Y` |
| REG_DTM/REG_ID, UPD_DTM/UPD_ID | 감사 필드 | i18n과 동일한 관례 |

**`kkdugi_code_lang`** — 코드 하나당 언어별 이름/설명. PK
`(CODE_ID, LANG_CD)`.

**`kkdugi_serial_base`** — 서버 채번용 공용 인프라(i18n에는 없던, 이번에
새로 추가된 부분). `p_id`(채번 대상 식별자, 예: `"KKDUGI_CODE"`)당 한
행을 두고, 현재 채번 구간 키(`seq_base`)와 그 구간 안의 카운터(`seq_val`)를
갖는다. **채번 로직 자체는 Postgres 함수 `fn_get_serial(id, key, size)`**로
구현되어 있다 — 동시성이 필요하고 여러 애플리케이션 인스턴스가 뜰 수
있어서, Java 레벨의 조회-후-갱신이 아니라 `INSERT ... ON CONFLICT ...`
한 문장으로 원자적으로 처리한다. 자세한 알고리즘과 설계 변경 경위는
[ADR-0012](adr/0012-common-code-system.md)의 addendum 참고. 이후
메뉴(`M`)/권한(`A`)/사용자(`U`) 화면도 같은 함수를 재사용한다.

## 패키지 구조

```
kkdugi
├─ core
│   ├─ serial
│   │   ├─ SerialConfig.java   — 채번 대상마다 구현하는 설정 인터페이스
│   │   ├─ mapper   — SerialMapper (fn_get_serial 호출)
│   │   └─ service  — SerialService (@Service, 버킷 오버플로우 시 분할 채번)
│   └─ code
│       ├─ models   — CodeBase, CodeLang (record, DB 행 그대로)
│       └─ mapper   — CodeBaseMapper, CodeLangMapper
├─ app.admin.code
│   ├─ models      — CodeSearchParams, CodeLocale, CodeContent, CodePersistRequest, CodeError
│   ├─ exceptions  — CodeValidationException, CodeConflictException
│   └─ service     — CodeAdminService (SerialUtils.next(config)로 채번)
└─ api.admin.code
    ├─ CodeAdminController
    └─ CodeErrorResponse
```

`core.util.SerialUtils`가 `SerialService`를 감싸는 정적 서비스 로케이터
역할을 한다 — `CodeAdminService`는 생성자 주입 없이 `SerialUtils.next(config)`로
채번한다.

i18n과 마찬가지로 컨트롤러는 `app.admin.code.models`의 타입을 요청/응답
바디로 직접 재사용한다([ADR-0011](adr/0011-api-define-admin-contract-and-record-models.md)
4절과 동일한 근거).

## API

### `POST /api/v1.0/admin/code` — 조회

요청: `{ parentId?, path?, code?, name?, use?, page, pageSize }`
응답: `Page<CodeContent>` = `{ page, pageSize, totalItems, totalPages, contents }`,
각 `contents[]` 항목은 `{ id, parentId, code, locale: { ko_KR: { name, remarks }, ... }, use, extra1..5, path, level, sort }`.

**중요**: `parentId`가 오면(생략 시 root 취급) **그 부모의 직접 자식만**
반환한다 — 트리 전체를 가로지르는 전역 검색이 아니다
(`kkdugi-design/docs/adr/0002-grid-batch-and-hierarchy.md`의 브레드크럼
탐색 결정을 따름). `path`/`code`/`name`/`use`는 그 형제 범위 안에서 추가로
좁히는 필터다. `name`은 등록된 언어 중 아무 언어에라도 일치하면 매치된다.

### `POST /api/v1.0/admin/code/persist` — 저장

요청: `{ insert: [CodeContent], update: [CodeContent], delete: [CodeContent] }`
(각각 생략 가능). 성공 시 빈 200. 검증 실패는 400, 충돌/대상 없음은
409 — 둘 다 `{ errors: [{ id, code, reason }] }`.

- **insert**: `id`는 비어 있어야 한다(서버가 `SerialUtils.next(SerialConfig)`로
  채번). `parentId`가 있으면 존재해야 하고(없으면 409), `code`/`locale`은
  필수. `(parentId, code)` 중복이면 409("코드충돌 ... insert 전용").
- **update**: `id` 필수, 존재해야 함(409). **`code`와 `parentId`는 생성
  후 수정할 수 없다** — 값이 다르면 409([ADR-0012](adr/0012-common-code-system.md)
  3절: 경로 재계산 캐스케이드를 아직 구현하지 않아서 의도적으로 막아둠).
  `extra1~5`/`use`/`sort`/`locale`은 수정 가능하며, `locale`에 새 언어가
  들어오면 그 언어는 INSERT로 처리한다(i18n의 update 처리와 동일한 패턴).
- **delete**: `id` 필수, 존재해야 함(409). **하위 코드도 모두 함께
  삭제한다**(`code_path` 접두어로 자기 자신+모든 하위를 찾아 삭제,
  api-define-admin.md 1.2절 명시 사항).

## 검증

- `code`(코드 값) 자체에는 `MessageCode` 같은 정규식 형식 검증이 없다 —
  비어있지 않은지만 확인한다(ERD/스펙 어디에도 형식 규칙이 없음).
- `locale`은 insert/`update`(제공 시)에서 최소 1개 이상, 각 항목의
  `name`은 필수(`remarks`는 선택).

## 테스트

- `CodeAdminServiceTest`(`app.admin.code.service`): root 코드 등록 후
  조회, 자식 등록 후 부모 조회 시 자식만 나오는지, 부모 삭제 시 자식까지
  삭제되는지, `locale` 누락 검증, 존재하지 않는 `id`로 update 시 409.
- `CodeAdminControllerTest`(`api.admin.code`): `MockMvc` 기반 400/409/200
  응답 형태 검증.

i18n과 동일하게 Testcontainers 없이 `docker-compose.yml`의 로컬
Postgres를 전제로 한다. **2026-09-17부터, 테스트는 작성 후 `mvn test`로
실제로 통과하는지 확인하고 실패하면 고친다** ([docs/i18n-system-design.md](i18n-system-design.md#테스트-실행-정책)
참고 — 이전 "작성만 하고 실행하지 않는다" 정책은 폐기됨).

## 후속 작업

- `code`/`parentId` 변경(노드 이동·경로 세그먼트 이름 변경)을 지원하려면
  캐스케이드 경로 재계산이 필요하다 — 아직 미구현([ADR-0012](adr/0012-common-code-system.md)
  "미해결 이슈").
- 메뉴/권한/사용자(옛 `docs/api-define-admin.md` 3·4·5절, 2026-09-18 삭제 —
  원문은 [docs/archive/api-define-admin.md](archive/api-define-admin.md)
  참고, 더 이상 살아있는 계약 아님이라 착수 전 오너 재확인 필요) — 같은
  패키지 규약과 `SerialConfig`/`SerialUtils`/`fn_get_serial`, `Page<T>`를
  재사용해 이어서 구현한다.
