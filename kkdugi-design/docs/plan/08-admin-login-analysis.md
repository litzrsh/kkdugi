# kkdugi-admin 로그인 구현 준비 분석

2026-09-17 / 대상: `C:\projects\kkdugi\kkdugi-admin` / 읽기 전용 소스 분석

## 결론

로그인은 JSON API와 JWT Bearer + DB 세션 구조다. 중복 로그인 거부·강제 교체는 구현돼 있다. 최초/만료 비밀번호 변경 강제, 인증 진행 상태, HTML 화면 컨트롤러, 최종 접근 제한은 추가 구현이 필요하다. 기존 퍼블리싱의 일반 form POST + challengeId는 확정된 서버 계약이 아니므로 그대로 복사해 연결할 수 없다.

PragmaController 없이 로그인 화면부터 구현할 수 있다. 다만 로그인 이후 HTML 요청을 무엇으로 인증할지 먼저 정해야 한다. 일반 브라우저 페이지 이동에는 JS가 지정하는 Bearer 헤더가 자동으로 포함되지 않는다.

## 1. 프로젝트 구조

| 영역 | 현재 역할 |
| --- | --- |
| pom.xml | Java 17, Spring Boot 4.0.8 선언. Web/Security/Thymeleaf/JDBC/Flyway/MyBatis/PostgreSQL/JJWT 의존성 |
| api.admin | 공통코드·메시지 REST controller |
| app.admin | 코드·메시지 업무 서비스, 요청/응답 모델 및 검증 예외 |
| core.security | 인증 필터, 사용자 조회, JWT, DB 세션, 로그아웃 |
| core.i18n | DB 메시지 캐시 + properties fallback |
| resources/mapper/postgres | MyBatis XML |
| resources/db/migration | 사용자·권한·세션 등을 만드는 Flyway V1~V7 |
| resources/templates, static | 분석 시점에는 없음. 로그인 화면부터 새로 연결해야 함 |

의존성 버전은 소스 선언을 확인한 것이며 다운로드/실행 검증 결과가 아니다. 프런트 Vue 미사용 요구는 vanilla JS fetch 방식과도 양립한다.

## 2. 현재 로그인 흐름

1. POST `/api/v1.0/admin/auth/login` → `AuthenticationProcessingFilter`.
2. JSON `{username,password,force}`를 `LoginRequest`로 읽고 빈 값 검사.
3. AuthenticationManager → UserDetailsService 기반 자격 증명 검증. `KkdugiUserDetailsService`가 사용자 및 현재 적용 권한을 MyBatis로 조회.
4. 인증된 principal로 `SessionService.createSession(user, force)` 호출.
5. 마지막 로그인 시각 기록.
6. DB 세션 ID와 만료 시각을 사용해 JWT 발급.
7. 200 `{token,userId,username,name,authorities}` 응답.

실패 응답은 `{code,message}` 구조. 중복은 `409 / session.err.duplicate`, 자격 증명 실패는 `401 / auth.err.bad_credentials`, 잘못된 요청은 현재 `401 / auth.err.malformed_request` 처리다.

근거: `core/security/authentication/filter/AuthenticationProcessingFilter.java:54,77,107,122`, `core/security/models/LoginRequest.java`, `LoginResponse.java`.

### 인증 유지와 로그아웃

- BearerTokenAuthenticationFilter는 Authorization 헤더만 읽는다. 쿠키를 읽지 않는다.
- JWT의 `sess_id`를 검증한 다음 매 요청 DB 세션 존재·만료를 확인하고 저장된 사용자 스냅샷으로 SecurityContext를 구성한다.
- POST `/api/v1.0/admin/auth/logout`은 해당 요청의 DB 세션만 삭제하고 204를 반환한다.
- DB 세션을 제거하면 이전 JWT는 이후 인증에 사용될 수 없다. 다만 현재 permitAll 설정에서는 인증 실패가 곧 API 접근 차단을 뜻하지 않는다.
- SessionCreationPolicy.STATELESS는 HTTP 세션을 사용하지 않는다는 의미다. 이 프로젝트의 DB 세션까지 없다는 뜻은 아니다.

## 3. 중복 로그인

`allowMultiple` 기본값은 false, 세션 시간 기본값은 1시간이다. 설정 클래스의 기본값이며 배포 설정에 의해 변경될 수 있다.

- 활성 DB 세션이 있고 force=false → 409. 이 경우 기존 세션을 삭제하지 않는다.
- force=true → 해당 사용자 세션 전체 삭제 후 새 세션 생성.
- allowMultiple=true → 기존 세션을 유지하고 추가 세션 생성.
- 현재 로그인 요청은 challengeId 재개가 아니라 **아이디·비밀번호·force를 다시 보내는 계약**이다.
- 머신 구분 로직은 없다. 같은 계정의 활성 세션 여부를 검사하므로 같은 기기의 다른 로그인 시도도 중복으로 취급할 수 있다.

연동 시 기존 계약을 유지한다면 동일 페이지 메모리에서만 자격 증명을 유지해 재요청하거나 다시 입력받아야 한다. 현재처럼 별도 HTML 페이지로 전환하면 원래 비밀번호는 전달되지 않으므로 challenge 방식으로 서버를 확장하거나 화면 흐름을 변경해야 한다. 비밀번호를 URL/스토리지로 넘기는 방식은 사용하지 않는다.

## 4. 필수 비밀번호 변경: 아직 연결되지 않음

- PasswordStatus: NEWP=10, EXPR=20, NORM=30.
- SessionUser에 isNewPassword/isPasswordExpired가 있지만 로그인 흐름에서 호출되지 않는다.
- UserDetails의 isCredentialsNonExpired 등 상태 메서드를 재정의하지 않는다. 사용자 상태 PEND/DORM/RESN/SUPD 검사도 로그인 경로에 연결돼 있지 않다.
- `updatePassword`는 UserDetailsPasswordService의 **해시 업그레이드**용이다. 사용자의 실제 비밀번호 변경 API가 아니다. SQL도 해시/upd_dtm만 바꾸며 상태와 변경일을 갱신하지 않는다.
- 새 비밀번호 변경 API, 상태 전환, 정책 검증, 만료 판정/갱신, 보류 인증 challenge는 없다.
- 따라서 현재 소스 흐름으로는 비밀번호가 맞으면 NEWP/EXPR 상태에서도 세션 생성·JWT 발급으로 진행할 수 있다. 강제 변경을 UI에만 붙여서는 막을 수 없다.
- PasswordStatus.EXPR 주석의 '변경 기한 연장'은 사용자의 최신 요구(변경 필수)와 다르다. 연장 동작은 이번 범위에 넣지 않는다.

## 5. 퍼블리싱과 서버의 연결 차이

| 항목 | 퍼블리싱 제안 | 현재 서버 | 필요한 정리 |
| --- | --- | --- | --- |
| 로그인 | form POST /login 예시 | JSON POST auth/login | vanilla fetch 또는 웹용 form 처리 계층 |
| 상태 이어가기 | challengeId | username/password/force | 서버 challenge 도입 또는 같은 페이지 재입력/재요청 |
| 인증 유지 | 연결 지점만 제공 | Bearer 헤더 | HTML 진입 인증과 토큰 수명/보관 계약 |
| CRUD API 호출 | same-origin credentials | Authorization Bearer 요구 | 기존 api.mjs에 인증 계약 반영 |
| CSRF | hidden/header 연결 준비 | 전체 disable | 쿠키 인증 채택 시 CSRF 정책 재설계 |
| 접근 제한 | 서버에서 보장 예정 | anyRequest().permitAll() | 공개/인증/보류 단계 경로 구분 |
| 다국어 | login-ui 번들, ?lang | messages/messages fallback | 번들 병합 또는 basename 추가, LocaleResolver 연결 |

현재 401 entry point는 JSON 응답이다. HTML 요청을 로그인 화면으로 유도할지 별도 분기도 필요하다. static 리소스와 로그인 진입은 공개, 보호 HTML/API는 인증 완료 상태만 허용해야 한다.

## 6. 구현 전 해결할 구체적인 지점

1. **접근 제한 및 사용자 상태 검증**: permitAll을 경로별 정책으로 바꾸고 비정상 계정/보류 로그인에 관리자 접근을 허용하지 않는다.
2. **비밀번호 변경 순서**: 자격 증명 확인 → 필수 변경 판정 → 변경 → 중복 확인 → 세션/JWT 발급. 완료 전 최종 토큰을 발급하지 않는다.
3. **동시 로그인 경합**: createSession은 조회→삭제→삽입이다. @Transactional만으로 동일 사용자 동시 요청 직렬화가 보장되지는 않는다. V7에는 사용자 ID의 일반 인덱스만 있고 유일 제약/사용자 잠금 처리가 없다. 사용자 행 잠금 등으로 정책을 보장할 필요가 있다.
4. **단계의 원자성**: 세션 생성 트랜잭션 이후 로그인 시각 기록/토큰 발급이 별도로 수행된다. 후속 실패 시 DB 세션만 남을 가능성을 고려한다.
5. **요청 null 처리**: readLoginRequest는 JSON null 반환 뒤 username()을 호출한다. 이 경로를 명시적으로 잘못된 요청으로 처리할 필요가 있다.
6. **세션 스냅샷**: SessionUser 전체를 JSONB로 저장하며 password 필드도 포함된다. 인증 후 세션용 모델에는 해시를 보관하지 않는 분리가 적절하다. 또한 권한/계정 상태를 DB 스냅샷에서 읽으므로 변경 시 기존 세션 갱신/폐기 정책도 필요하다.
7. **권한 기간**: 현재 SQL은 CURRENT_DATE BETWEEN 시작/종료다. 프런트의 빈 종료일을 무기한으로 취급하려면 null 종료일 처리와 날짜/시간 단위를 맞춰야 한다.

3~7은 소스에서 확인한 보완 지점이며 재현 테스트를 실행한 결과로 주장하지 않는다.

## 7. 권장 구현 순서

1. 인증 전달 방식 확정: 기존 API Bearer는 유지하되 Thymeleaf HTML 요청용 웹 인증 경로를 설계한다. 기존 DB 세션 서비스를 재사용하는 웹용 HttpOnly 쿠키 방식이 후보이며, 채택하면 CSRF와 공개 경로를 함께 정의한다. 아직 변경 결정/구현을 하지 않았다.
2. 로그인 업무를 서비스로 모아 사용자 상태·필수 변경·중복 확인·최종 세션 발급을 분리한다. 필터는 요청/응답 처리를 담당한다.
3. 짧은 수명의 로그인 challenge와 비밀번호 변경/계속/취소 계약을 구현한다. 기존 force 재로그인 계약을 유지할지 확장할지 명시한다.
4. 실제 로그인 템플릿과 vanilla JS를 `kkdugi-admin`에 옮기고 MessageSource/Locale 및 오류 응답을 연결한다.
5. 보호된 임시 진입 화면으로 로그인 전체 흐름을 검증한다. PragmaController 개발은 그 다음 단계로 진행할 수 있다.

## 8. 확인한 테스트와 검증 범위

AuthenticationProcessingFilterTest: 정상 로그인, 잘못된 비밀번호, 없는 사용자, 중복 409, force 세션 교체, 로그아웃. SessionServiceTest: 생성/조회, 중복, 강제 교체, 폐기, 없는 세션. 최초/만료 변경 강제, 보류 상태 접근 차단, 동시 로그인 경합, HTML 진입 인증 테스트는 검색 범위에서 찾지 못했다.

기존 통합 테스트는 JdbcTemplate으로 사용자/세션을 삽입·삭제한다. 이번 요청은 구조 분석이므로 DB 연동 테스트나 애플리케이션 실행을 하지 않았다. pom.xml XML 구조는 재확인 결과 정상이며, 빌드 성공 여부는 검증하지 않았다. 초기 읽기 출력에서 일부 본문이 빠져 보였으나 재확인한 현재 파일에는 본문이 존재하므로 소스 손상으로 판단하지 않는다.

**kkdugi-admin 소스·설정·브랜치·DB는 변경하지 않았다.** 이 문서만 kkdugi-design의 작업 계획에 추가한다.
