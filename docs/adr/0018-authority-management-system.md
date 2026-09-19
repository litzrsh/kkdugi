# ADR-0018: 권한 관리 시스템 — 단일 기능 패키지, 전체 교체 저장, SYS_ADMIN 예약

- 상태: 채택 (2026-09-19)
- 관련: [ADR-0016](0016-app-and-admin-feature-split.md), [ADR-0012](0012-common-code-system.md)(채번), 설계 문서 [authority-system-design.md](../authority-system-design.md), API 문서 [api/authority.md](../api/authority.md), 원본 스펙 [archive/api-define-admin.md](../archive/api-define-admin.md) 4절

## 배경

아카이브된 스펙 4절(권한 관리)은 살아있는 계약이 아니라서 착수 시점에 오너와 범위와 모호한 부분을 확정했다. 테이블(`kkdugi_auth_base`, `kkdugi_user_auth`, `kkdugi_auth_menu`)과 세션 쿼리는 이미 있었고, 스펙에는 RBAC 키 표기(`READ`/`WRITE` vs `Rbac` 숫자 코드)와 `users` 형태(ID 문자열 vs 객체)가 엇갈려 있었다.

## 결정

1. **범위는 스펙 4절 전부**: 권한 CRUD, 메뉴 RBAC 매핑, 사용자 매핑, 후보 사용자 조회. 사용자 도메인이 아직 없으므로 `kkdugi_user_base`는 읽기 전용, `kkdugi_user_auth`는 권한 쪽에서 쓴다.
2. **한 기능 패키지** `app.admin.authority`(mapper/service 하나)로 만든다. 권한 저장이 세 테이블을 한 트랜잭션으로 쓰기 때문에 기능을 쪼개면 트랜잭션 경계가 흐려진다.
3. **RBAC 맵 키는 `Rbac` 숫자 코드** `"10"`~`"40"`. 기존 `Rbac.toMap`, Pragma, session 문서와 같은 형태라 변환 계층이 필요 없다.
4. **`users`는 어디서나 `{id, applyStartDate, applyEndDate}` 객체**. 컬럼이 `NOT NULL DATE`라 ID만으로는 저장할 수 없다. 생략 시 시작=오늘, 종료=`9999-12-31`.
5. **저장은 전체 교체**: 필드가 null이면 그 매핑은 그대로, 빈 배열이면 비움, 목록이 있으면 교체(없는 행 삭제 + 나머지 upsert로 등록 감사 정보 보존).
6. **`SYS_ADMIN`은 유형과 무관하게 예약된 role 코드**다. `KkdugiUserDetailsService`(메뉴 우회)와 `SessionUtils`(권한 판단)가 권한 유형을 보지 않고 role 문자열만 보기 때문에, ROLE이 아닌 유형에 `SYS_ADMIN`이 존재하면 그것만으로 우회가 생긴다. 구현된 동작: role이 `SYS_ADMIN`인 권한은 삭제·role/type 변경·비활성화(`use: "N"`)가 409(`authority.err.immutable`)이고 이름/설명만 바꿀 수 있다. 등록/저장으로 ROLE이 아닌 유형의 `SYS_ADMIN`을 만들거나 바꾸는 시도도 409 `authority.err.immutable`이다. `(ROLE, SYS_ADMIN)`으로 만들거나 바꾸는 시도는 이미 시드(V8)가 있으므로 409 `authority.err.duplicate`다. 이 보호가 없으면 바꾸거나 지웠을 때 전원이 잠기거나(ROLE 쪽), 우회 권한이 생긴다(다른 유형 쪽).
7. **`(type, role)` 유일성**: 서비스에서 사전 검사(409) + `V11` 유니크 제약으로 동시 요청 경쟁을 막는다(`DuplicateKeyException`은 409로 변환). `SessionUtils`의 role 기반 판단이 모호해지면 안 된다.
8. **`auth_val`이 0인 매핑은 저장하지 않는다**(행 없음 = 접근권 없음).
9. **알 수 없는 RBAC 키는 400**: `Rbac.fromMap`이 알 수 없는 키에서 `IllegalArgumentException`을 던져 그대로 두면 500이 된다. `null` 값도 같이 400으로 거른다.
10. **범위 밖**: 세션 즉시 반영(다음 로그인부터 적용), 사용자 관리, 프런트엔드. 접근 제어는 구현 당시에는 프로젝트에 없어 범위 밖(`permitAll`)이었다.
11. **접근 제어는 병합 시 적용**: 병합 시점에 master에 있던 `SecurityChecker` Aspect([ADR-0017](0017-menu-context-security-aspect.md))에 맞춰 `@HasRole(SYS_ADMIN)` + `@RequireAuthority(program = "admin/authority")`(조회 READ, 등록/저장 WRTE, 삭제 DELT)를 컨트롤러에 붙였다. 메뉴 RBAC만으로는 `SYS_ADMIN`을 부여할 수 있어 권한 상승 경로가 되므로 메뉴 관리 API와 같이 역할을 함께 요구한다.

## 결과

- 권한 API가 생겨 `auth_menu` 행이 처음으로 만들어질 수 있다. `kkdugi_auth_menu`가 메뉴를 FK로 참조(`ON DELETE CASCADE` 없음)하므로, 최종 리뷰에서 메뉴 삭제(`AdminMenuService.deleteOne`)가 부여된 메뉴에서 FK 위반(500)을 낸다는 점이 지적돼 같은 작업에서 고쳤다: 메뉴 삭제는 이제 대상과 하위 메뉴의 `kkdugi_auth_menu` 행을 먼저 지운 뒤(`kkdugi_auth_menu` → `kkdugi_menu_lang` → `kkdugi_menu_base`) 메뉴를 지운다. 권한 자체는 건드리지 않으며, 이 쿼리는 메뉴 도메인의 `AdminMenuMapper.deleteAuthMenusByMenuIds`에 두어 `app.admin.menu`와 `app.admin.authority`가 서로 import하지 않게 했다.
- **`SYS_ADMIN`의 사용자 매핑은 비울 수 있다(위험을 알고 남긴 결정).** 설계상 `SYS_ADMIN` 권한의 사용자 매핑 변경은 허용되므로 `"users": []`로 모든 시스템 관리자를 제거할 수 있다. 제거된 사용자는 다음 로그인부터 전체 메뉴 우회를 잃고(기존 세션은 유지), 전원이 빠지면 DB 직접 수정이 유일한 복구 수단이다. 클라이언트는 이 권한에 빈/부분 `users`를 보내면 안 된다. "바꿀 수는 있지만 비울 수는 없다" 가드는 오너 결정을 기다리는 후속 과제다.
- 사용자 관리(5.5/5.6) 구현 시 `kkdugi_user_auth`의 쓰기 경로가 둘이 된다 — 그때 한쪽으로 정리한다.
- 후보 사용자 조회는 스펙에 페이징이 없어 최대 200명으로 제한했다(초과분은 `query`로 좁힌다).
- **V11 유니크 제약이 기존 테스트와 충돌했다.** V8이 `(ROLE, SYS_ADMIN)`을 시드하므로, 같은 권한을 직접 INSERT하던 `KkdugiUserDetailsServiceTest`의 SYS_ADMIN 테스트 두 개가 제약을 위반했다. 두 테스트는 새 행을 만드는 대신 테스트 사용자를 시드된 권한에 매핑하도록 다시 연결했고, 검증(assertion)은 바꾸지 않았다.
- **Jackson 3의 다중 인자 생성자 자동 감지 문제(알려진 한계, 미해결).** 목록 검색 파라미터 클래스처럼 기본 생성자와 다중 인자 편의 생성자를 함께 가진 클래스에서, Jackson 3가 `-parameters` 정보로 다중 인자 생성자를 속성 생성자로 자동 감지한다. 그러면 `page`/`pageSize`를 생략한 요청이 `Cannot map null into type int`로 실패해 HTTP 500이 난다. `AdminAuthorityParams`는 기본 생성자에 `@JsonCreator`를 붙여 setter 기반 바인딩을 쓰게 고쳤고, `AdminAuthorityControllerTest`에 `page`/`pageSize` 생략 요청의 회귀 단언이 있다. 그러나 기존 `AdminCodeParams`와 `AdminMessageParams`는 같은 잠복 문제를 갖고 있으며 이 작업 범위 밖이라 **의도적으로 그대로 두었다** — 두 목록 엔드포인트는 `page`/`pageSize`를 생략하면 500이 난다. 후속 과제로 같은 방식(`@JsonCreator`)으로 고쳐야 한다.
- **파싱 실패는 400이 아니라 500이다.** 깨진 JSON 본문이나 해석 불가한 `applyStartDate`/`applyEndDate`는 이 컨트롤러가 매핑하지 않아 전역 `RestfulExceptionAdvice`의 catch-all로 떨어져 500(`err.default`)이 된다(기존 전역 동작). 400은 값 검증(필수값, 길이, 알 수 없는 type/use, users/menus 항목 오류, 없는 사용자/메뉴 id)에 한한다.
- 이 작업으로 새 테스트 38개가 추가됐다(`AdminAuthorityMapperTest` 9 + `AdminAuthorityServiceTest` 19 + `AdminAuthorityControllerTest` 9 + `AdminMenuServiceTest`의 메뉴 삭제 시 권한 부여 정리 테스트 1). 전체 테스트는 149개에서 187개가 됐다.
