# ADR-0017: 요청 메뉴 기반 Aspect 인가

- 상태: Accepted
- 날짜: 2026-09-19

## 배경

프런트는 로그인·로그아웃 외의 API에 `X-Menu-Id`를 전달한다. 이 값은 호출 화면의 식별자이므로, 서버가 세션 메뉴 및 요구 권한을 검증해야 한다. 프런트의 버튼 노출만으로는 API 접근을 통제할 수 없다.

## 결정

`kkdugi.core.security.SecurityChecker`를 Spring Bean/Aspect로 등록한다. 기존 빈 클래스의 `SercurityChecker` 오타는 바로잡는다. Aspect는 트랜잭션 시작보다 앞에서 실행한다.

### Annotation

| Annotation / 속성 | 의미 |
|---|---|
| `@HasRole({"OWNER", "EDITOR"})` | 정확한 역할 코드 중 하나 이상(OR). `ROLE_` 접두사를 붙이지 않는다. 빈 목록은 차단한다. |
| `@RequireAuthority({READ, EXEC})` | 해당 메뉴에 나열한 권한이 모두 필요(AND). |
| `@RequireAuthority` | 인증된 세션, 유효한 메뉴 컨텍스트, 실행 프로그램과 유효한 RBAC 비트가 있는 메뉴 소속을 검사한다. |
| `program = "admin/code"` | 세션 메뉴의 program이 정확히 일치해야 한다. 관리 API 간 권한 차용을 방지한다. |
| `batch = true` | 유일한 `AuthorityBatch` 인자의 실제 insert/update/delete 목록을 검사한다. insert/update는 WRTE, delete는 DELT이며, 섞인 경우 둘 다 필요하다. 누락되거나 지원하지 않는 인자는 차단한다. |
| `allowShell = true` | 유효한 세션의 `GET /api/v1.0/menu`에서만 `__shell__`을 허용한다. program/batch 정책과 함께 사용하면 shell을 차단한다. |

클래스와 메서드 annotation은 누적된다. 메서드가 클래스의 요구 조건을 약화시키지 않는다. HasRole과 RequireAuthority도 함께 충족해야 한다. SYS_ADMIN 우회 분기는 없다. SYS_ADMIN의 메뉴 비트는 로그인 시 생성된 세션 데이터로 판별한다.

```java
@RequireAuthority(value = Rbac.READ, program = "admin/code")
public Page<AdminCode> search(AdminCodeParams params) { ... }

@RequireAuthority(program = "admin/code", batch = true)
public void persist(AdminCodePersistRequest request) { ... }

@HasRole(Constants.SYS_ADMIN)
@RequireAuthority(value = Rbac.READ, program = "admin/menu")
public List<AdminMenu> search() { ... }
```

컨트롤러 또는 서비스 **구현 클래스와 public 메서드**에 선언한다. 현재 HTTP 진입점에는 컨트롤러 메서드에 적용했다. 서비스에 적용해도 동일하게 동작하지만 HTTP 요청과 `SessionAuthentication`이 있어야 한다. 배치 DTO는 `core.security.models.AuthorityBatch`로 조회 가능한 변경 목록을 제공한다. JSON 형식은 바뀌지 않는다.

### 경계 및 실패 처리

- SecurityFilterChain은 `/api/**`, `/pragma/**`에 인증을 요구한다. 로그인·로그아웃은 기존 필터 계약을 유지하며 메뉴 ID가 필요 없다.
- Aspect는 `SessionAuthentication`과 세션 ID를 확인한다. 신뢰되지 않는 헤더에서 역할·사용자 정보를 복원하지 않는다.
- 헤더는 정확히 하나, 최대 60자의 ASCII 원문이어야 하며 빈 값·앞뒤 공백·제어문자를 거부한다.
- 일반 메뉴 ID는 현재 사용자 세션 메뉴에서 찾는다. 미등록/다른 사용자 메뉴, 프로그램 없는 그룹, RBAC 비트가 없는 메뉴는 차단한다.
- `__shell__`은 메뉴 목록 조회에만 허용하며 실제 메뉴 또는 관리자 권한으로 취급하지 않는다.
- Pragma URL의 디코딩된 메뉴 ID와 헤더가 일치해야 한다. 세션 메뉴 검증은 Aspect가 담당하고 템플릿 경로 형식·존재 확인은 기존 컨트롤러가 수행한다.
- 미인증은 401 `auth.err.unauthorized`, 인가 실패는 403 `auth.err.access_denied`. MVC advice가 Spring Security 예외를 JSON으로 변환하므로 일반 500 응답으로 삼키지 않는다.
- 배치 요청은 실행 전에 모든 작업의 권한을 검사한다. 일부 허용된 행만 저장하는 동작은 없다.

### 적용 현황

| API | 요구 사항 |
|---|---|
| 관리자 코드 조회/저장 | program `admin/code`, READ / 작업별 WRTE·DELT |
| 관리자 메시지 조회/저장 | program `admin/message`, READ / 작업별 WRTE·DELT |
| 관리자 메뉴 조회/저장 | program `admin/menu`, SYS_ADMIN, READ / 작업별 WRTE·DELT |
| 사용자 공통코드 조회 | 호출 메뉴의 READ (공통 API이므로 program 제한 없음) |
| 내 메뉴 트리 조회 | 인증 + shell 컨텍스트 또는 호출 메뉴의 READ |
| Pragma | 인증 + 해당 메뉴 소속·프로그램·유효한 권한 비트 + URL/헤더 일치 |

## 운영 및 확장 시 주의점

- Spring 프록시를 통해 호출할 때 적용된다. `new`로 만든 객체, private/final 메서드, 같은 객체 내부의 self-invocation은 인가 경계로 삼지 않는다. 서비스 간 호출은 주입받은 별도 Bean을 사용한다.
- Annotation 없는 메서드에는 이 Aspect가 적용되지 않는다. 새 API를 추가할 때 요구 권한과 program을 반드시 선언한다. 필터의 authenticated()만으로 메뉴 인가가 완성되지 않는다.
- 특정 화면 전용 API에 program을 생략하지 않는다. 공통 API는 호출 메뉴의 권한으로 허용할 업무인지 검토한다.
- 세션 메뉴/역할은 기존 DB 세션 스냅샷을 따른다. 로그인 이후 권한 변경의 즉시 반영·세션 갱신 정책은 기존 세션 모델의 범위다.

## 검증

프록시 단위 테스트로 역할 OR, RBAC AND, 클래스·메서드 누적, 헤더 검증, program 불일치, 배치 원자적 차단, shell 제한, HTTP 컨텍스트 누락, Pragma 일치를 검증한다. 실제 Spring 필터/컨트롤러/예외 처리 통합 테스트로 401/403 JSON과 허용된 호출의 서비스 실행을 검증한다. 기존 실제 로그인·쿠키·세션·Pragma 테스트도 새 호출 규약에 맞춰 실행한다.

### 실행 결과

- Maven: 169개 통과(실패 0, 오류 0).
- JavaScript: 35개 통과.
- 새 인가 테스트: 프록시 단위 12개, 실제 필터·MVC·DB 트랜잭션 통합 8개. 혼합 배치 거부 후 DB 미변경 및 삭제 전용 권한으로 삭제 허용을 확인했다.

