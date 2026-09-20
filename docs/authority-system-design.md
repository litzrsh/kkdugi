# kkdugi-admin 권한 관리 시스템 설계

- 작성일: 2026-09-19
- 작성자: litzrsh (with Claude)
- 상태: 구현 완료 (2026-09-19) — 구현 후 API 문서는 [api/authority.md](api/authority.md), 결정 기록은 [ADR-0018](adr/0018-authority-management-system.md)
- 관련 문서: [공통 규약](conventions/common-base-model.md),
  [ADR-0016](adr/0016-app-and-admin-feature-split.md)(app/app.admin 분리),
  [메뉴 관리 설계](adr/0015-menu-management-system.md),
  [아카이브된 API 스펙 4절](archive/api-define-admin.md)

## 배경과 범위

아카이브된 `api-define-admin.md` 4절("권한 관리")을 구현한다. 그 스펙은 더
이상 살아있는 계약이 아니라서(아카이브 문서 머리말), 착수 시점에 오너와
아래 항목을 재확인해 확정했다. 백엔드 API까지만 다룬다 — 화면은 범위 밖.

- **범위: 스펙 4절 전부** — 권한 CRUD, 권한↔메뉴 RBAC 매핑, 권한↔사용자
  매핑, 후보 사용자 조회. 사용자 도메인(5절)은 아직 없으므로 이 작업은
  `kkdugi_user_base`를 읽기 전용으로, `kkdugi_user_auth`를 권한 쪽에서
  직접 쓴다. 이후 사용자 관리(5.5/5.6)가 같은 테이블을 반대편에서 쓰게
  되므로 그때 쓰기 경로 중복을 정리해야 한다(후속 작업).
- **RBAC 맵 키는 `Rbac` 숫자 코드** `"10"`(조회)/`"20"`(등록)/`"30"`(삭제)/
  `"40"`(실행). 스펙 예시의 `READ`/`WRITE`는 쓰지 않는다 — `Rbac.toMap`,
  Pragma(`authorities['10']`), [session.md](api/session.md)와 같은 형태다.
- **`users` 형태는 전부 객체** `{id, applyStartDate, applyEndDate}`. 스펙은
  상세 응답·regist 요청에서 ID 문자열 배열, regist 응답·save 요청/응답에서
  객체로 제각각이었다. 컬럼(`apl_st_dtm`/`apl_ed_dtm`)이 `NOT NULL DATE`라
  ID만으로는 저장할 수 없어 통일했다. 날짜는 `yyyy-MM-dd`.
- 스펙의 표기 오류는 고친다: 삭제 경로 `authroity` → `authority`.

## 데이터 모델 (기존 테이블 재사용 — 마이그레이션은 V11 하나만 추가)

| 테이블 | 정의 | 용도 |
|---|---|---|
| `kkdugi_auth_base` | V6 | 권한 본체. `auth_role_cd`(role), `auth_tp_cd`(`ROLE`/`PLAN`, `AuthorityType`), `auth_nm`, `auth_dc`, `use_yn` |
| `kkdugi_user_auth` | V6 | 사용자↔권한. PK `(user_id, auth_id)`, 적용기간 `apl_st_dtm`~`apl_ed_dtm`(DATE) |
| `kkdugi_auth_menu` | V9 | 권한↔메뉴. PK `(auth_id, menu_id)`, `auth_val` = `Rbac` 비트마스크 |

- **V11**: `kkdugi_auth_base (auth_tp_cd, auth_role_cd)` 유니크 제약 추가
  (아래 "동작 규칙" 3번). 기존 데이터는 기본 `SYS_ADMIN` 1건뿐이라 충돌하지
  않는다.
- ID는 `kkdugi.core.serial.SerialConfig`(접두사 `A`) +
  `SerialUtils.next(config)`로 서버가 채번한다(ADR-0012 addendum).
  regist 요청의 `id`는 무시한다.

## 패키지 구조

```
kkdugi.app.admin.authority
├─ models      — AuthorityBase (DB 행, BaseModel), AdminAuthority (목록/상세 콘텐츠,
│                BaseModel + @JsonIgnoreProperties), AdminAuthorityParams (BaseParams:
│                role/type/name), AdminAuthorityPersistRequest, 사용자 항목
│                {id, applyStartDate, applyEndDate}, 메뉴 항목 {id, authorities},
│                후보 사용자, 메뉴 트리 노드 (core.models.Tree 구현)
├─ exceptions  — AdminAuthorityValidationException(400),
│                AdminAuthorityConflictException(409), AdminAuthorityNotFoundException(404)
├─ mapper      — AdminAuthorityMapper (읽기+쓰기 한 인터페이스, ADR-0016)
└─ service     — AdminAuthorityService

kkdugi.api.admin.AdminAuthorityController   — 평평하게 (/api/v1.0/admin/authority)
mapper XML: src/main/resources/mapper/postgres/app/admin/authority/AdminAuthorityMapper.xml
```

- 의존 방향은 `api → app → core`. `app.admin.authority`는 다른 `app.*`을
  import하지 않고 `core`만 쓴다(메뉴 트리는 자체 쿼리로 읽는다 — 사용자용/
  관리자용 mapper 중복은 의도된 것, 공통 규약 "사용자용/관리자용 분리").
- 규약 체크리스트 적용: `models`/`exceptions`/`mapper`/`service` 분리, record
  금지, DB 컬럼명이 드러나지 않는 필드명(`role`/`type`/`name`/`remarks`/`use`),
  resultMap은 `CommonMapper.baseResultMap` extends, 매퍼 XML은 CDATA/QueryID
  서식, 단건 조회는 `Optional<T>`, 목록은 `COUNT(*) OVER()`의 `total_size` +
  서비스에서 `params` 정규화 후 `Page.of`, 코드성 값은 이미 있는
  `AuthorityType`/`Rbac`을 재사용(별도 `typeHandler=` 없음).
- 감사 필드(`creatorId`/`updaterId`)는 메뉴 서비스와 같은 `"SYSTEM"` 상수
  방식으로 채운다(CLAUDE.md Scope notes와 동일한 현재 상태).

## 엔드포인트

경로는 모두 `/api/v1.0/admin/authority` 기준.

| 메서드/경로 | 설명 | 응답 |
|---|---|---|
| `POST /` | 목록 — 요청 `{role?, type?, name?, page, pageSize}` | `Page<AdminAuthority>` (`id, role, type, name, remarks, use`) |
| `GET /{id}` | 상세 | 위 필드 + `users: [{id, name, image, applyStartDate, applyEndDate}]`(`name`/`image`는 사용자 테이블에서 채우는 응답 전용 값, 요청에서는 무시). 없으면 404 |
| `POST /regist` | 등록 — 요청 `{role, type, name, remarks, use, users?, menus?}` | 등록된 권한(상세와 동일 형태) |
| `POST /{id}` | 저장 — 요청 형태는 regist와 같고 `id` 포함 | 저장된 권한 |
| `POST /{id}/delete` | 삭제 — `kkdugi_user_auth`, `kkdugi_auth_menu`, 본체를 한 트랜잭션으로 삭제 | 200 (없는 id는 스펙에 404가 없으므로 멱등하게 200) |
| `POST /{id}/user` | 후보 사용자 — 요청 `{query?}` | `[{id, username, name, image}]`. 상태 `20`(정상)이고 이 권한에 미매핑인 사용자만. `query`는 로그인ID/이름/이메일 대소문자 무시. 권한이 없으면 404 |
| `POST /{id}/menu` | 이 권한 기준 메뉴 트리 | 메뉴 노드 + `authorities: {"10":true,"20":false,"30":false,"40":false}`. 매핑 없는 메뉴는 전부 `false`. 권한이 없으면 404 |

응답 상태는 스펙의 200/400/404/409를 따른다. 401/403은 `SecurityChecker` Aspect가 만든다(아래 "접근 제어 (병합 시 적용)" 참고).

## 동작 규칙

스펙에 없어서 이 설계에서 정한 기본값이다.

1. **저장은 전체 교체.** 요청의 `users`/`menus`가 있으면 그 목록으로
   교체한다(목록에 없는 기존 행은 삭제, 나머지는 upsert — 그래서 변경 없는
   행의 등록 감사 정보가 보존된다). 필드가 없으면(null) 그 매핑은 건드리지
   않고, 빈 배열이면 전부 비운다. 세 테이블 쓰기는 한 트랜잭션.
2. **`SYS_ADMIN`은 ROLE 유형 권한 전용의 예약 role 코드다.** 세션/메뉴 우회
   판단(`KkdugiUserDetailsService`, `SessionUtils`)이 권한 유형을 보지 않고
   role 문자열만 보기 때문에, ROLE이 아닌 유형의 `SYS_ADMIN`이 있으면 우회가
   생긴다. 구현된 동작: role이 `SYS_ADMIN`인 권한은 삭제, role/type 변경,
   비활성화(`use=N`)를 409(`authority.err.immutable`)로 거절하고 이름/설명만
   바꿀 수 있다. 어떤 권한이든 ROLE이 아닌 유형의 `SYS_ADMIN`으로 등록하거나
   바꾸는 시도도 409 `authority.err.immutable`이다. `(ROLE, SYS_ADMIN)`으로
   만들거나 바꾸는 시도는 시드(V8)가 이미 있으므로 409 `authority.err.duplicate`다.
   `KkdugiUserDetailsService`가 이 role로 메뉴 우회를 판단하므로, ROLE 쪽을
   바꾸면 전원이 잠길 수 있다. 사용자 매핑 변경은 허용한다. `SYS_ADMIN`은
   메뉴 매핑을 우회해 전체를 보므로 `menus` 저장은 받되 실효는 없다.
   **주의(위험을 알고 허용한 결정):** 그래서 `SYS_ADMIN` 권한에 `"users": []`
   (또는 현재 시스템 관리자가 빠진 목록)를 보내는 것도 막지 않으며, 이는
   빠진 사용자의 `SYS_ADMIN` 부여를 제거한다. 그 사용자들은 **다음 로그인부터**
   전체 메뉴 우회를 잃고(이미 로그인된 세션은 그대로) 모든 시스템 관리자가
   비면 DB를 직접 고치는 방법밖에 복구 수단이 없다. 클라이언트는 이
   권한에 빈/부분 `users`를 절대 보내면 안 된다. 가드 여부는 아래
   "열린 결정/후속 작업" 참고.
3. **`(type, role)` 중복은 409.** role 코드가 `SessionUtils`의 `hasRole` 판단
   기준이라 같은 유형 안에서 모호해지면 안 된다. 서비스에서 검사하고, 동시
   요청 경쟁은 V11 유니크 제약이 막는다(위반 시 409로 변환).
4. **검증 실패는 400.** 필수값(role/type/name) 누락, 알 수 없는 `type`, 없는
   user id / menu id, `authorities`의 알 수 없는 RBAC 키(`Rbac.fromMap`은
   알 수 없는 키에서 `CodeEnums.fromCode`가 `IllegalArgumentException`을
   던져 그대로 두면 500이 된다 — `fromMap` 호출 전에 키를 검증해 400으로
   바꾼다. `fromMap` 안의 `rbac == null` 분기는 실제로는 도달하지 않는다),
   `users` 안의 중복 id,
   `applyEndDate < applyStartDate`. 적용기간을 생략하면 시작=오늘,
   종료=`9999-12-31`.
5. **`auth_val`이 0인 메뉴는 행을 저장하지 않는다.** 행이 없는 것이 접근권
   없음이라 의미가 같고, 세션 메뉴 쿼리도 행 기준으로 읽는다.
6. **후보 사용자 조회는 읽기 전용**이다(`kkdugi_user_base`를 조회만 함).
   비밀번호 등 민감 컬럼은 select하지 않는다. 스펙에 페이징이 없어 이름 순으로
   최대 200명까지만 돌려준다(초과분은 `query`로 좁힌다).
7. **메뉴 삭제는 그 메뉴들의 권한 부여도 함께 지운다.** 메뉴 관리의 삭제
   (`AdminMenuService.deleteOne`)는 대상 메뉴와 모든 하위 메뉴에 대해
   `kkdugi_auth_menu` → `kkdugi_menu_lang` → `kkdugi_menu_base` 순으로 삭제한다
   (`kkdugi_auth_menu`가 메뉴를 FK로 참조하고 `ON DELETE CASCADE`가 없기 때문).
   권한(`kkdugi_auth_base`) 자체는 건드리지 않는다. 이 쿼리는 메뉴 도메인의
   `AdminMenuMapper`가 가지므로 `app.admin.menu`와 `app.admin.authority`는
   서로를 import하지 않는다.

## 세션 반영 (결정)

권한을 바꿔도 **이미 로그인된 세션에는 즉시 반영되지 않는다** — 변경은 다음
로그인부터 적용된다(오너 결정). 세션 무효화/재로딩은 이번 범위에 넣지 않고,
API 문서에 이 제약을 명시한다.

## 범위 밖 (알려진 한계)

- **접근 제어 (병합 시 적용).** 이 설계를 쓸 때는 접근 제어가 프로젝트에 없어
  범위 밖이었다(당시 `SecurityConfigurer`가 전부 `permitAll`). 병합 시점에
  master에 `SecurityChecker` Aspect([ADR-0017](adr/0017-menu-context-security-aspect.md))가
  이미 들어와 있어서, 컨트롤러 어노테이션으로 이 API에도 적용했다:
  `@HasRole(SYS_ADMIN)` + `@RequireAuthority(program = "admin/authority")`이고
  조회는 READ, 등록/저장은 WRTE, 삭제는 DELT. 메뉴 RBAC만 요구하면 사용자에게
  `SYS_ADMIN`을 부여할 수 있는 권한 상승 경로가 되므로 메뉴 관리 API와 같이
  역할을 함께 요구한다.
- 사용자 관리(5절)는 구현하지 않는다 — 위 "범위" 참고.
- 조직/조직도 기능은 (과거 ERD에 있었더라도) 여전히 구현 대상이 아니다.
- 프런트엔드는 다루지 않는다(`http.mjs`의 `/api/v1.0/admin/` 화이트리스트가
  이미 이 경로를 허용한다).
- **파싱 실패는 400이 아니라 500.** 깨진 JSON 본문이나 해석 불가한
  `applyStartDate`/`applyEndDate`는 이 컨트롤러가 매핑하지 않아 전역
  `RestfulExceptionAdvice` catch-all(500, `err.default`)로 떨어진다(기존 전역
  동작). 400은 값 검증에 한한다.
- **`AdminCodeParams`/`AdminMessageParams`의 잠복 버그.** Jackson 3가 다중 인자
  편의 생성자를 속성 생성자로 자동 감지해, `page`/`pageSize`를 생략한 목록
  요청이 `Cannot map null into type int`(500)로 실패한다. `AdminAuthorityParams`는
  기본 생성자에 `@JsonCreator`를 붙여 고쳤지만(회귀 단언은
  `AdminAuthorityControllerTest`), 위 두 클래스는 이 작업 범위 밖이라 그대로
  뒀다 — 후속 과제. 자세한 내용은 [ADR-0018](adr/0018-authority-management-system.md#결과).
- V11 유니크 제약(`(auth_tp_cd, auth_role_cd)`)이 V8 시드 `(ROLE, SYS_ADMIN)`을
  직접 INSERT하던 `KkdugiUserDetailsServiceTest`의 SYS_ADMIN 테스트 두 개와
  충돌해, 두 테스트를 시드된 권한에 테스트 사용자를 매핑하도록 다시 연결했다
  (검증 내용은 그대로).

## 열린 결정 / 후속 작업

- **`SYS_ADMIN` 사용자 매핑 가드.** 현재는 승인된 설계대로 `SYS_ADMIN`의
  `users`를 자유롭게 바꿀 수 있어, 빈 배열이나 현재 시스템 관리자가 빠진
  목록으로 전원의 우회 권한을 없앨 수 있다(동작 규칙 2의 주의 참고). 오너가
  "바꿀 수는 있지만 비울 수는 없다"(예: 저장 후 유효한 시스템 관리자가 최소
  1명 남아야 한다) 같은 가드를 원할 수 있다 — 결정 전까지는 구현하지 않는다.

## 테스트

- `MenuControllerTest`와 같은 방식의 통합 테스트(도커 Postgres, `MockMvc`) —
  목록/상세/등록/저장/삭제/후보 사용자/메뉴 트리 정상 경로, 400/404/409 각
  경로(위 동작 규칙 2~4번), 전체 교체 규칙(null/빈 배열/부분 목록),
  `auth_val=0` 미저장, 삭제 시 매핑 연쇄 삭제.
- 메뉴 삭제 시 권한 부여 정리(동작 규칙 7)는 `AdminMenuServiceTest`가 검증한다
  (부모+자식 메뉴에 부여한 뒤 삭제해 부여 행은 사라지고 권한은 남는지 확인).
- 서비스 규칙 중 DB 없이 검증 가능한 것(RBAC 키 검증, 적용기간 기본값)은
  단위 테스트.
- 완료 기준: `./mvnw.cmd -B -ntp test`(`kkdugi-admin/`)가 실제로 통과.

## 문서 작업

구현과 함께 갱신한다: `docs/api/authority.md`(신규), `docs/api/README.md`
목차, ADR-0018(결정 기록), CLAUDE.md의 패키지 트리·Scope notes, 이 문서의
상태를 "구현 완료"로.
