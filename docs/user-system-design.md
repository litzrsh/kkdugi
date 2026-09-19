# kkdugi-admin 사용자 관리 시스템 설계

- 작성일: 2026-09-19
- 작성자: litzrsh (with Claude)
- 상태: 구현 완료 (2026-09-19) — API 문서는 [api/user.md](api/user.md)
- 관련 문서: [공통 규약](conventions/common-base-model.md),
  [ADR-0016](adr/0016-app-and-admin-feature-split.md)(app/app.admin 분리),
  [권한 관리 설계](authority-system-design.md),
  [권한 API](api/authority.md),
  [아카이브된 API 스펙 5절](archive/api-define-admin.md)

## 배경과 범위

아카이브된 `api-define-admin.md` 5절("사용자 관리")을 구현한다. 그 스펙은 더
이상 살아있는 계약이 아니라서(아카이브 문서 머리말), 착수 시점에 오너와
아래 항목을 재확인해 확정했다. 백엔드 API까지만 다룬다 — 화면은 범위 밖.

- **범위: 스펙 5절 전부(5.1~5.9)** — 목록, 상세, 등록, 저장, 사용자별 권한
  조회/저장, 비밀번호 초기화, 삭제, 상태 일괄 변경.
- **비밀번호(결정)**: 스펙은 등록(5.3)·초기화(5.7)에서 비밀번호를 어떻게
  정하는지 말하지 않는다. 오너 결정에 따라 **서버가 임시 비밀번호를 무작위로
  생성**한다. 인코딩(`{bcrypt}`, 기존 `PasswordEncoder` 빈)해서 저장하고
  `pwd_stat_cd = 10`(`PasswordStatus.NEWP`, 로그인 시 변경 필요)으로 둔다.
  임시 비밀번호는 메일로 보내는 것이 최종 방식이지만 메일 발송은 아직 없다
  → **`TODO(mail)`로 남기고, 임시로 평문을 애플리케이션 로그에 남긴다.**
  메일 발송이 들어오면 이 로그를 반드시 제거한다. API 응답에는 어떤 경우에도
  비밀번호를 싣지 않는다.
- **삭제(결정)**: **물리 삭제.** 스키마에 소프트 삭제 컬럼이 없고, 상태
  `40`(탈퇴)은 5.9 상태 변경의 값이지 "삭제"가 아니다. 삭제된 사용자의
  `login_id`/`email`이 유니크 제약을 계속 점유하는 문제도 피한다.
- **사용자별 권한(5.5/5.6)의 쓰기 경로 (결정)**: `kkdugi_user_auth`는 권한
  쪽(`AdminAuthorityService`)과 사용자 쪽 두 곳에서 쓰게 된다. `app.admin.<기능>`
  끼리는 import하지 않는다는 규칙(ADR-0016) 때문에 공용 컴포넌트로 합치지
  않고 **사용자 쪽 mapper에 전용 SQL을 둔다.** 검증 규칙(적용기간, 권한 존재)은
  권한 쪽과 같게 맞춘다. 중복은 알려진 한계로 [api/authority.md](api/authority.md)에
  반영한다.
- 스펙의 표기 오류는 고친다: 5.6 경로 `addmin` → `admin`, 5.8 경로
  `/api/v1.0/user/{id}/delete` → `/api/v1.0/admin/user/{id}/delete`, 5.6 요청의
  `[ "insert": ... ]`는 유효한 JSON이 아니므로 객체 `{ "insert", "update",
  "delete" }`로 한다.

## 데이터 모델 (기존 테이블 재사용 — 마이그레이션 없음)

| 테이블 | 정의 | 용도 |
|---|---|---|
| `kkdugi_user_base` | V5 | 사용자 본체. `user_login_id`(UNIQUE), `user_email`(NOT NULL, UNIQUE), `user_stat_cd`(`UserStatus`), `pwd_stat_cd`(`PasswordStatus`) |
| `kkdugi_user_auth` | V6 | 사용자↔권한. PK `(user_id, auth_id)`, 적용기간 `apl_st_dtm`~`apl_ed_dtm`(DATE) |
| `kkdugi_session` | V7 | 세션. `user_id` FK → 사용자 삭제 시 함께 지운다 |

- 새 Flyway 마이그레이션은 필요 없다. ID 카운터(`KKDUGI_USER`)와 `admin/user`
  메뉴(V10)도 이미 있다.
- ID는 `kkdugi.core.serial.SerialConfig` 구현 + `SerialUtils.next(config)`로
  서버 채번한다(접두사 `U`, 형식 `U{yyyyMMddHHmm}{0000}` — V8 시드와 동일).
- 응답에 싣지 않는 컬럼: `user_pwd`, `user_ci`, `user_di`, `user_set_data`,
  `pwd_expr_dtm`. JSON 필드명은 DB 컬럼명을 드러내지 않는다
  (`username`, `name`, `remarks`, `image`, `email`, `status`, `lastLoginAt`,
  `lastChangePasswordAt`, `passwordStatus`).
- `status`/`passwordStatus`는 `CodeEnums.getCode()` 문자열(`"20"` 등)로 주고받는다.
  요청에서는 문자열로 받아 `fromCode`로 변환하고 알 수 없는 값은 400이다
  (`AdminAuthorityPersistRequest`의 `type` 처리와 같은 방식).

## 패키지 구조

```
kkdugi.app.admin.user
├─ models      UserBase (DB 행, extends BaseModel), AdminUser (응답, extends BaseModel),
│              AdminUserParams (extends BaseParams), AdminUserPersistRequest,
│              AdminUserIds (id 목록 요청), AdminUserChangeStatusRequest,
│              AdminUserAuthority (권한 항목: 응답 + 요청), AdminUserAuthoritiesRequest{insert,update,delete}
├─ exceptions  AdminUser{Validation,NotFound,Conflict}Exception
├─ mapper      AdminUserMapper
└─ service     AdminUserService, 임시 비밀번호 생성기(TemporaryPasswordGenerator)
kkdugi.api.admin.AdminUserController      (/api/v1.0/admin/user — 컨트롤러는 평평하게 유지)
resources/mapper/postgres/app/admin/user/AdminUserMapper.xml
```

- 컨트롤러는 `AdminAuthorityController`와 같다: `@HasRole(Constants.SYS_ADMIN)`,
  `@RequireAuthority(READ/WRTE/DELT, program = "admin/user")`(권한 저장만 `batch = true`), 컨트롤러 안의
  `@ExceptionHandler` 3개(400/404/409 → `ExceptionMessage`).
- **의존 규칙**: `api → app → core`. `app.admin.user`는 `app.admin.authority`를
  import하지 않는다. `PasswordEncoder`는 `core.security.config`의 기존 빈이다.
- `reg_id`/`upd_id`는 다른 서비스와 같이 `"SYSTEM"` 상수(세션 연동은 별개 작업).
- `Page<T>`: 목록 쿼리는 `COUNT(*) OVER() AS total_size`를 select하고 resultMap에서
  `BaseModel.totalSize`로 매핑한다. 서비스는 `Page.of` 전에
  `params.setPage(params.resolvedPage()); params.setPageSize(params.resolvedPageSize());`로
  정규화한다. `AdminUser`는 `@JsonIgnoreProperties({"rownum","createdAt","creatorId","updatedAt","updaterId"})`를 붙인다.
- 요청 클래스(final 필드 + 전체 필드 생성자)는 Jackson 3의 암묵적 properties-creator
  감지에 의존한다(컴파일러 `-parameters`). `@JsonIgnoreProperties(ignoreUnknown = true)`.

## 엔드포인트

모두 `/api/v1.0/admin/user` 아래. RBAC: 조회 READ, 등록/저장/초기화/상태변경 WRTE, 삭제 DELT. 권한 저장(5.6)은
프로젝트의 배치 규약(`AuthorityBatch`)을 따른다 — `insert`/`update`가 비어 있지 않으면 WRTE, `delete`가 비어 있지
않으면 DELT를 요구한다(삭제만 있는 요청은 DELT).

| # | 메서드·경로 | 설명 |
|---|---|---|
| 5.1 | `POST /` | 목록. 필터 `username`/`name`/`status`(부분 일치는 username·name, status는 일치), `page`/`pageSize`. 응답 `Page<AdminUser>` |
| 5.2 | `GET /{id}` | 상세. 없으면 404 |
| 5.3 | `POST /regist` | 등록. 응답 `AdminUser` |
| 5.4 | `POST /{id}` | 저장. 응답 `AdminUser` |
| 5.5 | `GET /{id}/authorities` | 사용자의 권한 목록 |
| 5.6 | `POST /{id}/authorities` | 권한 목록 저장(`insert`/`update`/`delete`). 배치 RBAC: insert/update는 WRTE, 비어 있지 않은 delete는 DELT. 응답: 저장 후 권한 목록 |
| 5.7 | `POST /reset-password` | 비밀번호 초기화. `{ "id": [...] }` |
| 5.8 | `POST /{id}/delete` | 삭제 |
| 5.9 | `POST /change-status` | 상태 일괄 변경. `{ "id": [...], "status": "20" }` |

경로 매칭 주의: `reset-password`/`change-status`/`regist`는 고정 경로라 `/{id}`보다 우선한다
(`AdminAuthorityController`의 `regist`와 같은 구조).

## 동작 규칙

1. **등록(5.3)**: `username`(≤100자), `name`(≤200자), `email`(≤200자)은 필수, `remarks`(≤1000자),
   `image`(≤500자)는 선택. `id`는 받지 않는다(서버 채번). `status`를 생략하면 `NORM(20)`.
   임시 비밀번호를 생성해 인코딩·저장하고 `pwd_stat_cd = 10`, `last_chg_pwd_dtm`은 비운다.
   `username` 중복은 409 `user.err.duplicate_username`, `email` 중복은 409
   `user.err.duplicate_email`이다. 사전 조회로 걸러내고 동시 등록은
   `DuplicateKeyException`을 잡아 같은 409로 바꾼다(`AdminAuthorityService`의 패턴).
2. **저장(5.4)**: 본문의 `id`는 무시하고 경로의 id를 쓴다. `username`이 기존과 다르면
   409 `user.err.immutable`(생략하면 통과). `email`을 바꿀 때도 중복 검사를 한다(자기 자신은 제외).
   `status` 생략 시 기존 값을 유지한다. 비밀번호·`lastLoginAt` 등 서버 관리 필드는 요청에 실려 와도 무시한다.
3. **목록(5.1)**: 정렬은 `reg_dtm DESC, user_id`. 페이징은 쿼리 레벨(`COUNT(*) OVER()`).
4. **비밀번호 초기화(5.7)**: `id` 목록 전체가 존재해야 한다(없는 id가 하나라도 있으면 404,
   아무것도 바꾸지 않음). 대상마다 임시 비밀번호를 생성해 `user_pwd`, `pwd_stat_cd = 10`,
   `last_chg_pwd_dtm`, `upd_dtm`을 갱신한다. 각 사용자별로 `TODO(mail)` 로그를 남긴다.
   빈 목록·빈 id는 400. 중복 id는 한 번만 처리한다. 응답 본문 없음.
5. **삭제(5.8)**: 한 트랜잭션에서 `kkdugi_session` → `kkdugi_user_auth` → `kkdugi_user_base` 순으로 지운다.
   이미 없는 사용자는 **멱등하게 200**이다(`AdminAuthorityService.delete`와 같음).
   **자기 자신(현재 로그인 사용자)을 지우려 하면 409 `user.err.self_delete`.**
6. **상태 일괄 변경(5.9)**: 전부 성공하거나 전부 실패한다(단일 트랜잭션). 존재하지 않는 id가
   하나라도 있으면 404, 빈 목록·빈 id·알 수 없는 status는 400이다. 어떤 상태로든 바꿀 수 있고
   전이 제약은 없다.
7. **사용자별 권한 조회(5.5)**: 사용자가 없으면 404. `kkdugi_user_auth`와 `kkdugi_auth_base`를 조인해
   `id`/`role`/`type`/`name`/`remarks`/`use`/`applyStartDate`/`applyEndDate`를 준다. 날짜는
   `yyyy-MM-dd`, 권한 id 순.
8. **사용자별 권한 저장(5.6)**: 사용자가 없으면 404. `insert`/`update`/`delete` 항목에서는
   **`id`와 `applyStartDate`/`applyEndDate`만 읽고**, 나머지 필드(`role`/`type`/`name`/…)는
   응답 전용이라 무시한다(상세 응답을 그대로 save 요청에 돌려보내도 된다). 날짜를 생략하면
   시작 = 오늘, 종료 = `9999-12-31`이다. 없는 권한 id는 400
   `user.err.authority_not_found`, 적용기간 역전·같은 요청 안의 중복 id·잘못된 날짜 형식은
   400 `user.err.malformed_request`. `insert`가 이미 있는 매핑이면 upsert(기간 갱신)로 처리한다.
   `delete`의 없는 매핑은 무시한다. 응답은 저장 후 권한 목록이다.
9. **오류 응답**: `ExceptionMessage`의 메시지 키를 쓴다. 키는 `messages.properties` /
   `messages_ko_KR.properties` / `messages_en_US.properties`에 모두 추가한다.

## 오류 코드

| 상태 | 키 | 경우 |
|---|---|---|
| 400 | `user.err.malformed_request` | 필수값(username/name/email) 누락, 길이 초과, 알 수 없는 status, 빈 id 목록·빈 id, 권한 항목 오류 |
| 400 | `user.err.authority_not_found` | 5.6에서 존재하지 않는 권한 id |
| 404 | `user.err.not_found` | 없는 사용자(5.2, 5.4, 5.5, 5.6, 5.7, 5.9) |
| 409 | `user.err.duplicate_username` | username 충돌 |
| 409 | `user.err.duplicate_email` | email 충돌 |
| 409 | `user.err.immutable` | 5.4에서 username 변경 시도 |
| 409 | `user.err.self_delete` | 자기 자신 삭제 시도 |

`user.err.not_found`는 로그인 쪽 `KkdugiUserDetailsService.ERR_NOT_FOUND`와 이름이 같은 기존 키다.
이미 정의돼 있으면 재사용하고 없으면 새로 추가한다(구현 시 확인).

## 범위 밖 (알려진 한계)

- **마지막 `SYS_ADMIN` 사용자 보호는 하지 않는다 (오너 결정, 2026-09-19).** 사용자 삭제(5.8),
  상태 변경(5.9), 사용자별 권한 저장(5.6), 그리고 기존 권한 저장 API의 `users`로 마지막
  시스템 관리자를 없앨 수 있고, 그러면 DB를 직접 고치는 것 외에 복구 방법이 없다. 오너는
  "그렇게 쓰는 것은 사용자의 잘못 사용이므로 무시해도 된다"고 결정했다. 설계안은 검토했다
  (트랜잭션 안에서 `SYS_ADMIN` 권한 행을 `FOR UPDATE`로 잠근 뒤 작업 전후 유효 관리자 수를 비교) —
  나중에 다시 필요해지면 그 방식으로 시작한다. 이는 [api/authority.md](api/authority.md)의
  "경고" 문단과 같은 위험이다.
- 2026-09-20: 사용자 상태는 로그인 전에 검사한다. 정상(20)만 진행하며 대기/휴면/탈퇴/제재는 차단한다. 기존 세션의 즉시 종료는 별개다. [인증 계약](api/auth.md) 참조.
- **임시 비밀번호 평문 로그**는 메일 발송이 들어오면 제거해야 하는 임시 조치다(`TODO(mail)`).
- **`kkdugi_user_auth` 쓰기 경로가 두 곳**(권한 저장, 사용자별 권한 저장)이다. 규칙(적용기간, 권한
  존재)이 어긋나지 않게 두 서비스의 테스트가 같은 케이스를 검증한다.
- 2026-09-20: 초기/만료 비밀번호 변경과 만료 30일 연장을 로그인 흐름에 구현했다. 로그인 후 별도 비밀번호 변경 API와 로그인 실패 잠금은 여전히 범위 밖이다.

## 테스트

`mvn test`를 실제로 통과시킨다(프로젝트 테스트 정책). 로컬 Postgres(`docker-compose up -d`) 필요.

- **매퍼 테스트** (`@SpringBootTest`, `AdminAuthorityMapperTest`와 같은 방식): 목록 필터·페이징·
  `total_size`, username/email 중복 조회, 삭제 시 `kkdugi_session`/`kkdugi_user_auth` 정리, 권한 목록 조회.
- **서비스 테스트**: 채번 형식(`U` + 12자리 + 4자리), 임시 비밀번호가 인코딩 저장되고 `pwd_stat_cd = 10`,
  username/email 중복 409, username 불변 409, 자기 삭제 409, 이미 없는 사용자 삭제 200(멱등),
  5.9 전부-또는-전무 롤백, 5.7 없는 id 404 롤백, 5.6 기본 날짜·upsert·없는 권한 400.
  임시 비밀번호 로그(`TODO(mail)`)는 로그 캡처로 한 번 확인한다.
- **컨트롤러 테스트** (`AdminAuthorityControllerTest` 패턴): 상태 코드(400/404/409), 403(권한 없음),
  JSON 형태 — 특히 **어떤 응답에도 비밀번호 필드가 없는 것**.
- **임시 비밀번호 생성기 단위 테스트**: 길이·문자 구성, 호출마다 값이 다름.
- 프런트 JS 계약 테스트(`node --test`)는 이 프로젝트가 백엔드 전용이라 추가하지 않는다.

## 문서 작업

- `docs/api/user.md` 신규 작성 + `docs/api/README.md`에 등록.
- CLAUDE.md의 "Authority/users are still TODO" 표현과 Scope notes를 갱신한다.
- [api/authority.md](api/authority.md) "알려진 한계"의 "사용자 관리가 구현되면…" 항목을
  "구현됨(사용자 쪽 전용 SQL, 두 경로가 같은 규칙을 검증)"으로 고친다.
- 이 문서의 상태를 "구현 완료"로 바꾸고 `api/user.md` 링크를 남긴다.
