# 사용자 관리 API

- 구현: `kkdugi.api.admin.AdminUserController` → `AdminUserService` → `AdminUserMapper`
- 설계 근거: [사용자 관리 설계](../user-system-design.md)

모든 엔드포인트는 `/api/v1.0/admin/user` 아래에 있고 `SYS_ADMIN` 역할과 `admin/user` 메뉴의 RBAC를 요구한다
(조회 READ, 등록/저장/초기화/상태 변경 WRTE, 삭제 DELT). 권한 저장(6번)은 프로젝트의 배치 규약을 따른다 — `insert`/`update`가 비어 있지
않으면 WRTE, `delete`가 비어 있지 않으면 DELT를 요구한다(삭제만 있는 요청은 DELT).
`X-Menu-Id` 헤더 규약은 [request-context.md](request-context.md)를 따른다.

## 공통

- **사용자 객체**: `{ id, username, name, remarks, image, email, status, lastLoginAt, lastChangePasswordAt, passwordStatus }`.
  `status`는 `UserStatus` 코드(`10` 대기, `20` 정상, `30` 휴면, `40` 탈퇴, `50` 정지), `passwordStatus`는 `PasswordStatus`
  코드(`10` 신규·변경 필요, `20` 만료, `30` 정상, 값이 없으면 `null`). 일시는 `yyyy-MM-dd HH:mm:ss`, 없으면 `null`.
  비밀번호·CI/DI·설정 데이터는 어떤 응답에도 없다.
- **권한 항목**: `{ id, role, type, name, remarks, use, applyStartDate, applyEndDate }`, 날짜는 `yyyy-MM-dd`.
  `type`은 `ROLE`/`PLAN`, `use`는 `Y`/`N`이다.
  요청에서는 `id`와 두 날짜만 읽고 나머지는 응답 전용이라 무시한다(응답 항목을 그대로 요청으로 돌려보내도 된다).
- **요청 본문의 알 수 없는 필드**는 무시한다(`id`, `lastLoginAt` 등 서버 관리 필드가 실려 와도 된다).
- **오류 응답**: `{ "code": "...", "message": "..." }` (`ExceptionMessage`, 컨트롤러 로컬 `@ExceptionHandler`).

| 상태 | 코드 | 경우 |
|---|---|---|
| 400 | `user.err.malformed_request` | 필수값 누락(등록은 username/name/email, 저장은 name/email — username은 생략 가능), 길이 초과(username 100, name 200, email 200, remarks 1000, image 500), 알 수 없는 status(목록 필터 포함), 빈 id 목록·빈 id, 권한 항목 오류(null/빈 id/중복 id/적용기간 역전) |
| 400 | `user.err.malformed_request` | 본문을 해석할 수 없음(깨진 JSON, 잘못된 날짜 형식 포함) |
| 400 | `user.err.authority_not_found` | 6번(사용자별 권한 저장)의 `insert`/`update`에 존재하지 않는 권한 id |
| 404 | `user.err.not_found` | 없는 사용자(상세·저장·권한 조회/저장, 초기화·상태 변경은 없는 id가 하나라도 있을 때) |
| 409 | `user.err.duplicate_username` / `user.err.duplicate_email` | 로그인 ID / 이메일 충돌 |
| 409 | `user.err.immutable` | 저장에서 `username` 변경 시도 |
| 409 | `user.err.self_delete` | 자기 자신 삭제 시도 |
| 401 / 403 | (공통 인증·인가 오류) | 미인증 / `SYS_ADMIN` 역할·RBAC 비트 부족, 또는 `X-Menu-Id`가 `admin/user` 메뉴가 아님 |

## 1. 목록 — `POST /api/v1.0/admin/user`
```javascript
Request  // 모두 선택. username/name은 대소문자 무시 부분 일치, status는 코드 일치
{ "username": "adm", "name": "kim", "status": "20", "page": 1, "pageSize": 200 }
Response // kkdugi.core.models.Page — 최신 등록순
{ "page": 1, "pageSize": 200, "totalItems": 1, "totalPages": 1, "contents": [ { /* 사용자 객체 */ } ] }
```
`page`/`pageSize`를 생략하면 1/200이고 `pageSize`는 최대 200이다. 알 수 없는 `status`는 400.

## 2. 상세 — `GET /api/v1.0/admin/user/{id}`
사용자 객체. 없으면 404.

## 3. 등록 — `POST /api/v1.0/admin/user/regist`
```javascript
Request  // id는 보내지 않는다(서버 채번, 접두사 U). status 생략 시 "20". username/name/email은 필수
{ "username": "...", "name": "...", "email": "...", "remarks": "...", "image": "...", "status": "20" }
Response // 사용자 객체 (passwordStatus = "10")
```
서버가 임시 비밀번호를 생성해 인코딩 저장한다. **메일 발송은 아직 없다 — `TODO(mail)`. 임시로 평문이 애플리케이션 로그(WARN,
`TODO(mail) 임시 비밀번호 발급 ...`)에 남는다.** 메일 발송이 구현되면 이 로그를 제거한다. 응답에는 비밀번호가 없다.
로그인 ID나 이메일이 이미 있으면 409.

## 4. 저장 — `POST /api/v1.0/admin/user/{id}`
요청은 등록과 같다. 본문 `id`는 무시하고 경로 id를 쓴다. `username`은 생략하거나 기존 값과 같아야 한다(다르면 409). `name`/`email`은 필수이고,
`name`/`remarks`/`image`/`email`은 요청 값으로 교체되며(`remarks`/`image` 생략은 비움), `status` 생략 시 기존 값을 유지한다.
다른 사용자가 쓰는 이메일이면 409. 비밀번호는 건드리지 않는다. 응답은 저장 후의 사용자 객체.

## 5. 사용자별 권한 조회 — `GET /api/v1.0/admin/user/{id}/authorities`
권한 항목 배열(권한 id 순). 사용자가 없으면 404.

## 6. 사용자별 권한 저장 — `POST /api/v1.0/admin/user/{id}/authorities`
```javascript
Request  // 각 목록은 생략 가능. insert/update는 둘 다 upsert(없으면 추가, 있으면 적용기간 갱신), delete는 매핑 삭제(없는 매핑은 무시)
{ "insert": [ { "id": "A...", "applyStartDate": "2026-10-01", "applyEndDate": "2026-12-31" } ],
  "update": [ ... ], "delete": [ { "id": "A..." } ] }
Response // 저장 후의 권한 항목 배열
```
날짜를 생략하면 시작=오늘, 종료=`9999-12-31`이다 — `update`에서 날짜를 생략하면 기존 기간을 유지하지 않고 이 기본값으로 덮어쓴다.
같은 id가 요청 안에 두 번 나오면(세 목록에 걸쳐서 센다) 400이고, 종료가 시작보다 빠르면 400이다. `insert`/`update`의 권한 id가 하나라도 없으면
`user.err.authority_not_found`(400)이며 `delete` 대상은 존재 여부를 확인하지 않는다. `delete`를 먼저 적용한 뒤 upsert한다.
하나라도 잘못되면 아무것도 저장하지 않는다.
RBAC는 요청 내용에서 정해진다 — `insert`/`update`가 있으면 WRTE, `delete` 목록이 비어 있지 않으면 DELT(삭제만 있는 요청은 DELT만).
세 목록이 모두 비어 있으면 추가 비트 없이 메뉴 접근만 확인한다(아무것도 바꾸지 않는다).

## 7. 비밀번호 초기화 — `POST /api/v1.0/admin/user/reset-password`
`{ "id": ["U...", ...] }` → 200(본문 없음). 대상마다 새 임시 비밀번호를 생성해 저장하고 `passwordStatus = "10"`, `lastChangePasswordAt`을 갱신한다
(로그 방식은 3번과 같다). 중복 id는 한 번만 처리한다. 빈 목록·빈 id는 400, 없는 id가 하나라도 있으면 404이고 아무것도 바뀌지 않는다.

## 8. 삭제 — `POST /api/v1.0/admin/user/{id}/delete`
물리 삭제(사용자의 세션과 권한 매핑도 함께 삭제, 권한 자체는 유지). 이미 없는 사용자는 200(본문 없음). 자기 자신은 409.

## 9. 상태 일괄 변경 — `POST /api/v1.0/admin/user/change-status`
`{ "id": ["U...", ...], "status": "50" }` → 200(본문 없음). 전부 성공하거나 전부 실패한다. 없는 id가 있으면 404, 빈 목록·빈 id·누락되었거나 알 수 없는
status는 400. 상태 전이 제약은 없다.

## 알려진 한계
- **마지막 `SYS_ADMIN` 사용자 보호가 없다(오너 결정).** 삭제(8), 권한 저장(6), 그리고 [권한 API](authority.md)의 `users`로
  마지막 시스템 관리자를 없앨 수 있고, 그러면 DB를 직접 고치는 것 외에 복구 방법이 없다. 상태 변경(9)도 상태가 로그인에 반영되면 같은 위험이 생긴다.
- **사용자 상태는 아직 로그인에 반영되지 않는다.** 정지·탈퇴·휴면 사용자도 로그인된다. 9번은 값을 저장할 뿐 강제하지 않는다.
- 임시 비밀번호 평문 로그는 메일 발송이 구현될 때까지의 임시 조치다.
- 사용자 본인의 비밀번호 변경, 로그인 실패 잠금, 비밀번호 정책은 범위 밖이다.

## 10. 연결 가능한 권한 후보 — `POST /api/v1.0/admin/user/{id}/authority-candidates`

사용자관리 화면의 권한 팝업용 조회. `SYS_ADMIN` 역할과 `admin/user` 메뉴의 READ가 필요하며,
`X-Menu-Id`에는 팝업을 연 사용자관리 메뉴 ID를 그대로 전달한다.

요청: `{ "query": "admin" }` (본문·query 생략 가능, 공백은 제거하며 빈 값은 전체 조회).

응답: 공통 권한 항목의 배열. `use=Y`이며 해당 사용자에게 아직 연결되지 않은 권한을
권한명·ID 순서로 최대 200개 반환한다. query는 역할 코드·권한명·ID의 대소문자 무시 부분 일치이며
`%`, `_`도 일반 문자로 취급한다. 기존 연결은 적용 기간이 지났어도 후보에서 제외한다.
후보의 applyStartDate/applyEndDate는 null이며 프런트에서 오늘/9999-12-31을 초기값으로 채운다.

없는 사용자 ID는 404. 조회는 상태를 변경하지 않는다. 선택 후 저장은 6번 배치 API를 사용한다.
이 API는 다른 메뉴의 인가 컨텍스트를 빌리지 않고 사용자관리 범위에서 후보를 조회하기 위해 추가했다.
