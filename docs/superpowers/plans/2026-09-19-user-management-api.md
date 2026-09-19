# 사용자 관리 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 아카이브 스펙 5절(5.1~5.9)의 사용자 관리 API를 `/api/v1.0/admin/user` 아래에 구현한다.

**Architecture:** `kkdugi.app.admin.user`(models/exceptions/mapper/service) + 평평한 `kkdugi.api.admin.AdminUserController`. 기존 authority 도메인의 패턴(BaseModel/BaseParams/Page, `@RequireAuthority`+`@HasRole`, 컨트롤러 내 `@ExceptionHandler`, MyBatis XML)을 그대로 따르고 `app.admin.authority`는 import하지 않는다. 새 Flyway 마이그레이션은 없다(V5 테이블 재사용).

**Tech Stack:** Java 17, Spring Boot 4.0.8, Jackson 3, MyBatis, PostgreSQL 17, Lombok, JUnit5 + AssertJ + MockMvc.

**Spec:** [docs/user-system-design.md](../../user-system-design.md)

## Global Constraints

- 모델은 **record 금지**: DB 행/응답 콘텐츠는 `kkdugi.core.models.BaseModel`, 검색 파라미터는 `BaseParams`를 상속, 요청/결과 클래스는 `final` 필드 + 전체 필드 생성자(`@Getter @AllArgsConstructor`).
- Jackson은 Jackson 3(`tools.jackson.databind.ObjectMapper`)이고 애너테이션은 `com.fasterxml.jackson.annotation.*`를 쓴다.
- `app.admin.user`는 `app.admin.authority`를 import하지 않는다(`api → app → core`).
- JSON 필드명은 DB 컬럼명을 드러내지 않는다. `user_pwd`, `user_ci`, `user_di`, `user_set_data`, `pwd_expr_dtm`은 어떤 응답에도 싣지 않는다.
- `status`/`passwordStatus`는 `CodeEnums.getCode()` 문자열(`"20"`)로 주고받는다. MyBatis enum 매핑은 `default-enum-type-handler`에 맡기고 컬럼별 `typeHandler=`를 쓰지 않는다.
- ID는 `SerialConfig`(id `KKDUGI_USER`, 포맷 `U%s%04d`) + `SerialUtils.next(config)`. `reg_id`/`upd_id`는 `"SYSTEM"` 상수.
- 목록 쿼리는 `COUNT(*) OVER() AS total_size` + resultMap의 `totalSize` 매핑, 서비스는 `Page.of` 전에 `params.setPage(params.resolvedPage()); params.setPageSize(params.resolvedPageSize());`로 정규화한다.
- 오류 응답은 `ExceptionMessage(code)`. 메시지 키는 `messages.properties`/`messages_ko_KR.properties`/`messages_en_US.properties` 세 파일에 모두 넣는다.
- 임시 비밀번호는 `{bcrypt}`(기존 `PasswordEncoder` 빈)로 저장, `pwd_stat_cd = 10`. 메일 발송은 `TODO(mail)`로 남기고 평문을 `log.warn`으로 남긴다. API 응답에는 절대 싣지 않는다.
- 마지막 `SYS_ADMIN` 보호는 **하지 않는다**(오너 결정). 이 가드를 추가하지 말 것.
- 테스트는 작성하고 실제로 통과시킨다. `kkdugi-admin/`에서 `./mvnw.cmd -B -ntp test [-Dtest=클래스명]`, 사전 조건 `docker-compose up -d`(Postgres).
- **커밋은 사용자가 요청한 경우에만 한다.** 작업 트리에 이 작업과 무관한 미커밋 변경이 많으므로 `git add -A`/`git add .`를 쓰지 말고 각 Task의 파일만 명시적으로 stage한다. 이미 미커밋 변경이 있는 `docs/api/authority.md`는 사용자 변경이 섞이니 커밋 전에 사용자에게 확인한다.
- 코드 주석/문서는 기존 코드처럼 한국어로 쓴다.

## 파일 구조

Create (main, `kkdugi-admin/src/main/`):

| 경로 | 책임 |
|---|---|
| `java/kkdugi/app/admin/user/service/TemporaryPasswordGenerator.java` | 임시 비밀번호 생성 |
| `java/kkdugi/app/admin/user/service/AdminUserService.java` | 사용자 업무 로직 전부 |
| `java/kkdugi/app/admin/user/mapper/AdminUserMapper.java` | MyBatis 인터페이스 |
| `java/kkdugi/app/admin/user/models/UserBase.java` | `kkdugi_user_base` 행 |
| `java/kkdugi/app/admin/user/models/AdminUser.java` | 응답 콘텐츠 |
| `java/kkdugi/app/admin/user/models/AdminUserParams.java` | 목록 검색 파라미터 |
| `java/kkdugi/app/admin/user/models/AdminUserPersistRequest.java` | 등록/저장 요청 |
| `java/kkdugi/app/admin/user/models/AdminUserIds.java` | `{ "id": [...] }` 요청 |
| `java/kkdugi/app/admin/user/models/AdminUserChangeStatusRequest.java` | 상태 일괄 변경 요청 |
| `java/kkdugi/app/admin/user/models/UserAuthority.java` | `kkdugi_user_auth` 행(+권한 조인 값) |
| `java/kkdugi/app/admin/user/models/AdminUserAuthority.java` | 권한 항목(요청+응답) |
| `java/kkdugi/app/admin/user/models/AdminUserAuthoritiesRequest.java` | `{insert,update,delete}` 요청 |
| `java/kkdugi/app/admin/user/exceptions/AdminUser{Validation,NotFound,Conflict}Exception.java` | 400/404/409 |
| `java/kkdugi/api/admin/AdminUserController.java` | REST 엔드포인트 |
| `resources/mapper/postgres/app/admin/user/AdminUserMapper.xml` | SQL |

Modify: `resources/messages/messages{,_ko_KR,_en_US}.properties`, `src/test/java/kkdugi/support/TestAuthorization.java`, 문서들(Task 6).

Test (`kkdugi-admin/src/test/java/kkdugi/`): `app/admin/user/service/TemporaryPasswordGeneratorTest`, `app/admin/user/service/AdminUserServiceTest`, `app/admin/user/mapper/AdminUserMapperTest`, `api/admin/AdminUserControllerTest`.

## Task 분해

1. 임시 비밀번호 생성기
2. 조회(5.1 목록, 5.2 상세): 모델·예외·메시지·매퍼·서비스·컨트롤러
3. 등록(5.3) · 저장(5.4)
4. 비밀번호 초기화(5.7) · 삭제(5.8) · 상태 일괄 변경(5.9)
5. 사용자별 권한(5.5/5.6) + 전 엔드포인트 RBAC 매트릭스 테스트
6. 문서 + 전체 테스트

---

### Task 1: 임시 비밀번호 생성기

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/admin/user/service/TemporaryPasswordGenerator.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/admin/user/service/TemporaryPasswordGeneratorTest.java`

**Interfaces:**
- Produces: `@Component TemporaryPasswordGenerator { String generate(); }` — 길이 12, 대문자·소문자·숫자를 각각 1자 이상 포함, 혼동 문자(`0 O 1 l I`) 제외. Task 3, 4의 서비스가 주입받는다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kkdugi.app.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TemporaryPasswordGeneratorTest {

    private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();

    @Test
    void generate_hasFixedLength_andEveryCharacterClass() {
        for (int i = 0; i < 200; i++) {
            String password = generator.generate();
            assertThat(password).hasSize(12)
                    .matches("[A-Za-z0-9]+")
                    .matches(".*[A-Z].*")
                    .matches(".*[a-z].*")
                    .matches(".*[0-9].*");
        }
    }

    @Test
    void generate_excludesAmbiguousCharacters() {
        for (int i = 0; i < 200; i++) {
            assertThat(generator.generate()).doesNotContainPattern("[0O1lI]");
        }
    }

    @Test
    void generate_returnsDifferentValuesEachTime() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(50);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run (`kkdugi-admin/`에서): `./mvnw.cmd -B -ntp test -Dtest=TemporaryPasswordGeneratorTest`
Expected: 컴파일 오류(`TemporaryPasswordGenerator` 없음)로 FAIL.

- [ ] **Step 3: 구현**

```java
package kkdugi.app.admin.user.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * 사용자 등록/비밀번호 초기화 때 쓰는 임시 비밀번호 생성기. 화면에서 읽기 쉽도록 혼동되는
 * 문자(0/O, 1/l/I)를 뺀 영문 대소문자+숫자로 12자를 만들고, 세 문자 종류가 모두 들어가도록 보장한다.
 */
@Component
public class TemporaryPasswordGenerator {

    static final int LENGTH = 12;

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String ALL = UPPER + LOWER + DIGIT;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] chars = new char[LENGTH];
        chars[0] = pick(UPPER);
        chars[1] = pick(LOWER);
        chars[2] = pick(DIGIT);
        for (int i = 3; i < LENGTH; i++) {
            chars[i] = pick(ALL);
        }
        // 앞 세 자리가 종류별로 고정돼 있으므로 Fisher-Yates로 섞는다.
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char swap = chars[i];
            chars[i] = chars[j];
            chars[j] = swap;
        }
        return new String(chars);
    }

    private char pick(String source) {
        return source.charAt(random.nextInt(source.length()));
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=TemporaryPasswordGeneratorTest`
Expected: 3 tests PASS.

- [ ] **Step 5: 체크포인트**

`git status --short kkdugi-admin/src/main/java/kkdugi/app/admin/user kkdugi-admin/src/test/java/kkdugi/app/admin/user`로 이 Task 파일 두 개만 생겼는지 확인한다. 사용자가 커밋을 요청한 경우에만:

```bash
git add kkdugi-admin/src/main/java/kkdugi/app/admin/user/service/TemporaryPasswordGenerator.java kkdugi-admin/src/test/java/kkdugi/app/admin/user/service/TemporaryPasswordGeneratorTest.java
git commit -m "feat(user): add temporary password generator"
```

### Task 2: 조회 — 목록(5.1), 상세(5.2)

**Files:**
- Create: `models/{UserBase,AdminUser,AdminUserParams}.java`, `exceptions/AdminUser{Validation,NotFound,Conflict}Exception.java`, `mapper/AdminUserMapper.java`, `service/AdminUserService.java`, `kkdugi/api/admin/AdminUserController.java`, `resources/mapper/postgres/app/admin/user/AdminUserMapper.xml` (모두 `kkdugi-admin/src/main/java/kkdugi/app/admin/user/` 하위, 경로 표기가 다른 것은 위 파일 구조 표 참고)
- Modify: `resources/messages/messages.properties`, `messages_ko_KR.properties`, `messages_en_US.properties`; `src/test/java/kkdugi/support/TestAuthorization.java`
- Test: `AdminUserMapperTest`, `AdminUserServiceTest`, `AdminUserControllerTest`

**Interfaces:**
- Consumes: `kkdugi.core.models.{BaseModel,BaseParams,Page}`, `kkdugi.core.enums.{UserStatus,PasswordStatus}`, `CommonMapper.baseResultMap`.
- Produces:
  - `AdminUserMapper`: `List<UserBase> search(String username, String name, UserStatus status, int offset, int pageSize)`, `Optional<UserBase> findById(String id)`
  - `AdminUserService`: `Page<AdminUser> search(AdminUserParams)`, `AdminUser get(String id)`; 상수 `ERR_MALFORMED_REQUEST`, `ERR_NOT_FOUND`; private `findExisting(id)`, `parseStatus(code)`, `toContent(row)`, `isBlank(s)`
  - `AdminUser` getters: `id, username, name, remarks, image, email, status(String code), lastLoginAt, lastChangePasswordAt, passwordStatus(String code)`
  - 예외 클래스 3종은 `getCode()`를 가진다(메시지 = 코드).
  - `TestAuthorization.mvc(WebApplicationContext, String program, int bits, String... roles)`

- [ ] **Step 1: 메시지 키 추가 (세 파일)**

각 파일에서 `authority.err.immutable=` 줄 바로 아래에 붙인다(Edit로 그 줄을 찾아 그 줄 + 새 줄로 교체).

`messages.properties`와 `messages_en_US.properties` (영어):
```properties
user.err.malformed_request=The request is malformed
user.err.not_found=The target user could not be found
user.err.authority_not_found=A specified authority could not be found
user.err.duplicate_username=The login id is already in use
user.err.duplicate_email=The email address is already in use
user.err.immutable=The login id cannot be changed after registration
user.err.self_delete=You cannot delete your own account
```

`messages_ko_KR.properties`:
```properties
user.err.malformed_request=요청 값이 올바르지 않습니다
user.err.not_found=대상 사용자를 찾을 수 없습니다
user.err.authority_not_found=지정한 권한을 찾을 수 없습니다
user.err.duplicate_username=이미 사용 중인 로그인 ID입니다
user.err.duplicate_email=이미 사용 중인 이메일입니다
user.err.immutable=로그인 ID는 등록 후 수정할 수 없습니다
user.err.self_delete=자기 자신은 삭제할 수 없습니다
```

- [ ] **Step 2: `TestAuthorization`에 RBAC 비트/역할을 받는 오버로드 추가**

`kkdugi-admin/src/test/java/kkdugi/support/TestAuthorization.java`의 기존 `mvc(...)` 메서드(33~44행)를 아래로 교체한다.

```java
    public static MockMvc mvc(WebApplicationContext context, String program) {
        return mvc(context, program, 15, "SYS_ADMIN");
    }

    /** 지정한 RBAC 비트/역할의 세션으로 호출하는 MockMvc — 인가 어노테이션 적용을 검증할 때 쓴다. */
    public static MockMvc mvc(WebApplicationContext context, String program, int bits, String... roles) {
        return MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(MockMvcRequestBuilders.get("/").header(SecurityChecker.MENU_ID_HEADER, "M_TEST_API"))
                .addFilters((request, response, chain) -> {
                    var previous = SecurityContextHolder.getContext();
                    var security = SecurityContextHolder.createEmptyContext();
                    security.setAuthentication(session(program, bits, roles));
                    SecurityContextHolder.setContext(security);
                    try { chain.doFilter(request, response); }
                    finally { SecurityContextHolder.setContext(previous); }
                }).build();
    }
```

- [ ] **Step 3: 매퍼 테스트 작성** — `src/test/java/kkdugi/app/admin/user/mapper/AdminUserMapperTest.java`

```java
package kkdugi.app.admin.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.user.models.UserBase;
import kkdugi.core.enums.UserStatus;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminUserMapperTest {

    private static final String USER_1 = "U_TEST_USR_MAPPER_1";
    private static final String USER_2 = "U_TEST_USR_MAPPER_2";
    private static final String USER_3 = "U_TEST_USR_MAPPER_3";

    @Autowired
    private AdminUserMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        wipe();
        // reg_dtm을 고정해 "최신 등록순" 정렬을 결정적으로 검증한다.
        insertUser(USER_1, "test_usr_mapper_alpha", "Alpha Kim", "20", "2030-01-01 00:00:01");
        insertUser(USER_2, "test_usr_mapper_beta", "Beta Lee", "10", "2030-01-01 00:00:02");
        insertUser(USER_3, "test_usr_mapper_gamma", "Gamma Kim", "20", "2030-01-01 00:00:03");
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id LIKE 'U_TEST_USR_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id LIKE 'U_TEST_USR_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id LIKE 'U_TEST_USR_MAPPER_%'");
    }

    private void insertUser(String id, String loginId, String name, String status, String regDtm) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_dtm, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, Timestamp.valueOf(regDtm), "SYSTEM");
    }

    @Test
    void search_filtersByLoginIdNameAndStatus_caseInsensitively_newestFirst() {
        assertThat(mapper.search("TEST_USR_MAPPER", null, null, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_3, USER_2, USER_1);
        assertThat(mapper.search("ALPHA", null, null, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_1);
        assertThat(mapper.search("test_usr_mapper", "kim", null, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_3, USER_1);
        assertThat(mapper.search("test_usr_mapper", null, UserStatus.NORM, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_3, USER_1);
        assertThat(mapper.search("test_usr_mapper", null, UserStatus.PEND, 0, 10))
                .extracting(UserBase::getId).containsExactly(USER_2);
    }

    @Test
    void search_pagesAtQueryLevel_andReportsTheTotalOnEveryRow() {
        List<UserBase> rows = mapper.search("test_usr_mapper", null, null, 1, 1);

        assertThat(rows).extracting(UserBase::getId).containsExactly(USER_2);
        assertThat(rows.get(0).getTotalSize()).isEqualTo(3L);
    }

    @Test
    void findById_mapsColumns_neverFillsPassword_andIsEmptyForUnknownId() {
        jdbcTemplate.update(
                "UPDATE kkdugi_user_base SET user_pwd = ?, pwd_stat_cd = ?, last_login_dtm = ?, user_dc = ?, user_img_src = ? "
                        + "WHERE user_id = ?",
                "{bcrypt}secret", "30", Timestamp.valueOf("2030-01-02 03:04:05"), "memo", "img.png", USER_1);

        UserBase row = mapper.findById(USER_1).orElseThrow();

        assertThat(row.getUsername()).isEqualTo("test_usr_mapper_alpha");
        assertThat(row.getName()).isEqualTo("Alpha Kim");
        assertThat(row.getEmail()).isEqualTo("test_usr_mapper_alpha@example.com");
        assertThat(row.getRemarks()).isEqualTo("memo");
        assertThat(row.getImage()).isEqualTo("img.png");
        assertThat(row.getStatus()).isEqualTo(UserStatus.NORM);
        assertThat(row.getPasswordStatus()).isEqualTo(kkdugi.core.enums.PasswordStatus.NORM);
        assertThat(row.getLastLoginAt()).isEqualTo(java.time.LocalDateTime.of(2030, 1, 2, 3, 4, 5));
        assertThat(row.getPassword()).isNull();
        assertThat(mapper.findById("U_TEST_USR_MAPPER_MISSING")).isEmpty();
    }
}
```

- [ ] **Step 4: 서비스 테스트 작성** — `src/test/java/kkdugi/app/admin/user/service/AdminUserServiceTest.java`

(이 파일은 Task 3~5에서 테스트 메서드와 import가 추가된다. wipe는 이후 Task의 생성 데이터까지 지운다.)

```java
package kkdugi.app.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.user.exceptions.AdminUserNotFoundException;
import kkdugi.app.admin.user.exceptions.AdminUserValidationException;
import kkdugi.app.admin.user.models.AdminUser;
import kkdugi.app.admin.user.models.AdminUserParams;
import kkdugi.core.models.Page;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminUserServiceTest {

    private static final String USER_1 = "U_TEST_USR_SVC_1";
    private static final String USER_2 = "U_TEST_USR_SVC_2";

    @Autowired
    private AdminUserService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        wipe();
        insertUser(USER_1, "test_usr_svc_one", "Svc One", "20");
        insertUser(USER_2, "test_usr_svc_two", "Svc Two", "10");
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    /** 등록 API가 만든(서버 채번) 사용자와 테스트 권한까지 로그인 ID/role 접두사로 정리한다. */
    private void wipe() {
        String userIds = "SELECT user_id FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_svc_%'";
        String authIds = "SELECT auth_id FROM kkdugi_auth_base WHERE auth_role_cd LIKE 'TEST_USR_SVC_%'";
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id IN (" + userIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id IN (" + userIds + ") OR auth_id IN (" + authIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_role_cd LIKE 'TEST_USR_SVC_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_svc_%'");
    }

    private void insertUser(String id, String loginId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, "SYSTEM");
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void search_normalizesPaging_andFiltersByLoginId() {
        Page<AdminUser> page = service.search(new AdminUserParams("test_usr_svc_", null, null, 0, 0));

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getTotalItems()).isEqualTo(2L);
        assertThat(page.getContents()).extracting(AdminUser::getId).containsExactlyInAnyOrder(USER_1, USER_2);
    }

    @Test
    void search_filtersByStatusCode() {
        Page<AdminUser> page = service.search(new AdminUserParams("test_usr_svc_", null, "10", 1, 10));

        assertThat(page.getContents()).extracting(AdminUser::getId).containsExactly(USER_2);
        assertThat(page.getContents().get(0).getStatus()).isEqualTo("10");
    }

    @Test
    void search_returnsAnEmptyPageWhenNothingMatches() {
        Page<AdminUser> page = service.search(new AdminUserParams("test_usr_svc_nomatch", null, null, 1, 10));

        assertThat(page.getTotalItems()).isZero();
        assertThat(page.getContents()).isEmpty();
    }

    @Test
    void search_rejectsUnknownStatus() {
        assertThatThrownBy(() -> service.search(new AdminUserParams(null, null, "99", 1, 10)))
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
    }

    @Test
    void get_returnsTheUser_andThrowsNotFoundForUnknownId() {
        AdminUser user = service.get(USER_1);

        assertThat(user.getUsername()).isEqualTo("test_usr_svc_one");
        assertThat(user.getStatus()).isEqualTo("20");
        assertThat(user.getPasswordStatus()).isNull();
        assertThatThrownBy(() -> service.get("U_TEST_USR_SVC_MISSING"))
                .isInstanceOf(AdminUserNotFoundException.class)
                .hasMessage(AdminUserService.ERR_NOT_FOUND);
    }
}
```

- [ ] **Step 5: 컨트롤러 테스트 작성** — `src/test/java/kkdugi/api/admin/AdminUserControllerTest.java`

(이 파일도 Task 3~5에서 메서드가 추가된다.)

```java
package kkdugi.api.admin;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import kkdugi.KkdugiAdminApplication;
import kkdugi.support.TestAuthorization;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminUserControllerTest {

    private static final String URL = "/api/v1.0/admin/user";
    private static final String USER_1 = "U_TEST_USR_API_1";
    private static final String USER_2 = "U_TEST_USR_API_2";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = TestAuthorization.mvc(webApplicationContext, "admin/user");
        wipe();
        insertUser(USER_1, "test_usr_api_one", "Api One", "20");
        insertUser(USER_2, "test_usr_api_two", "Api Two", "10");
        jdbcTemplate.update(
                "UPDATE kkdugi_user_base SET user_pwd = ?, pwd_stat_cd = ?, last_login_dtm = ? WHERE user_id = ?",
                "{bcrypt}secret-hash", "30", Timestamp.valueOf("2030-01-02 03:04:05"), USER_1);
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        String userIds = "SELECT user_id FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_api_%'";
        String authIds = "SELECT auth_id FROM kkdugi_auth_base WHERE auth_role_cd LIKE 'TEST_USR_API_%'";
        jdbcTemplate.update("DELETE FROM kkdugi_session WHERE user_id IN (" + userIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE user_id IN (" + userIds + ") OR auth_id IN (" + authIds + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_role_cd LIKE 'TEST_USR_API_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_api_%'");
    }

    private void insertUser(String id, String loginId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, "SYSTEM");
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    void search_returnsPage_withoutPasswordOrAuditFields() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(json(map("username", "test_usr_api_"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(200))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.contents.length()").value(2))
                .andExpect(jsonPath("$.contents[0].password").doesNotExist())
                .andExpect(jsonPath("$.contents[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.contents[0].creatorId").doesNotExist())
                .andExpect(jsonPath("$.contents[0].totalSize").doesNotExist());
    }

    @Test
    void search_filtersByStatus() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_", "status", "10"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.contents[0].id").value(USER_2));
    }

    @Test
    void get_returnsDetail_withFormattedDateTimes_andNoSecrets() throws Exception {
        mockMvc.perform(get(URL + "/" + USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_1))
                .andExpect(jsonPath("$.username").value("test_usr_api_one"))
                .andExpect(jsonPath("$.name").value("Api One"))
                .andExpect(jsonPath("$.email").value("test_usr_api_one@example.com"))
                .andExpect(jsonPath("$.status").value("20"))
                .andExpect(jsonPath("$.passwordStatus").value("30"))
                .andExpect(jsonPath("$.lastLoginAt").value("2030-01-02 03:04:05"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.userPwd").doesNotExist());
    }

    @Test
    void get_returns404ForUnknownUser() throws Exception {
        mockMvc.perform(get(URL + "/U_TEST_USR_API_MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user.err.not_found"));
    }

    @Test
    void search_requiresTheSysAdminRole() throws Exception {
        TestAuthorization.mvc(webApplicationContext, "admin/user", 15, "OPERATOR")
                .perform(post(URL).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 6: 실패 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminUserMapperTest,AdminUserServiceTest,AdminUserControllerTest`
Expected: 컴파일 오류(`AdminUserMapper`, 모델, 예외, 서비스 없음)로 FAIL.

- [ ] **Step 7: 예외 3종 구현** — `kkdugi/app/admin/user/exceptions/`

```java
package kkdugi.app.admin.user.exceptions;

/** 요청 값이 올바르지 않을 때(400). */
public class AdminUserValidationException extends RuntimeException {

    private final String code;

    public AdminUserValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

`AdminUserNotFoundException`(Javadoc "대상 사용자가 없을 때(404)")과 `AdminUserConflictException`(Javadoc "현재 상태와 충돌할 때(409) — username/email 중복, username 변경, 자기 삭제")도 클래스명과 Javadoc만 바꿔 같은 본문으로 만든다.

- [ ] **Step 8: 모델 구현** — `kkdugi/app/admin/user/models/`

`UserBase.java`:
```java
package kkdugi.app.admin.user.models;

import java.time.LocalDateTime;

import kkdugi.core.enums.PasswordStatus;
import kkdugi.core.enums.UserStatus;
import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code kkdugi_user_base} 한 행. {@code password}는 인코딩된 값이며 INSERT에만 쓰고 조회 결과에는
 * 채우지 않는다(SELECT 목록에 {@code user_pwd}가 없다).
 */
@Getter
@Setter
public class UserBase extends BaseModel {

    private String id;
    private String username;
    private String name;
    private String remarks;
    private String image;
    private String email;
    private UserStatus status;
    private String password;
    private PasswordStatus passwordStatus;
    private LocalDateTime lastLoginAt;
    private LocalDateTime lastChangePasswordAt;

    public UserBase() {
    }

    public UserBase(String id, String username, String name, String remarks, String image, String email,
            UserStatus status) {
        this.id = id;
        this.username = username;
        this.name = name;
        this.remarks = remarks;
        this.image = image;
        this.email = email;
        this.status = status;
    }
}
```

`AdminUser.java`:
```java
package kkdugi.app.admin.user.models;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kkdugi.core.models.BaseModel;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 사용자 목록/상세/등록/저장 응답 콘텐츠. {@link kkdugi.core.models.Page}가 {@code T extends BaseModel}을
 * 요구해서 BaseModel을 상속하고 감사 필드는 숨긴다. 비밀번호·CI/DI·설정 데이터는 아예 필드가 없다.
 * {@code status}/{@code passwordStatus}는 코드 문자열이고 일시는 {@code yyyy-MM-dd HH:mm:ss}다.
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties({ "rownum", "createdAt", "creatorId", "updatedAt", "updaterId" })
public class AdminUser extends BaseModel {

    private static final String DATE_TIME = "yyyy-MM-dd HH:mm:ss";

    private final String id;
    private final String username;
    private final String name;
    private final String remarks;
    private final String image;
    private final String email;
    private final String status;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME)
    private final LocalDateTime lastLoginAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME)
    private final LocalDateTime lastChangePasswordAt;

    private final String passwordStatus;
}
```

`AdminUserParams.java`:
```java
package kkdugi.app.admin.user.models;

import com.fasterxml.jackson.annotation.JsonCreator;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminUserParams extends BaseParams {

    private String username;
    private String name;
    private String status;

    /** 요청 본문 역직렬화용 — 다중 인자 생성자가 속성 생성자로 감지되어 page/pageSize 생략이 실패하는 것을 막는다(AdminAuthorityParams와 동일). */
    @JsonCreator
    public AdminUserParams() {
    }

    public AdminUserParams(String username, String name, String status, int page, int pageSize) {
        this.username = username;
        this.name = name;
        this.status = status;
        setPage(page);
        setPageSize(pageSize);
    }
}
```

- [ ] **Step 9: 매퍼 인터페이스 + XML**

`mapper/AdminUserMapper.java`:
```java
package kkdugi.app.admin.user.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.admin.user.models.UserBase;
import kkdugi.core.enums.UserStatus;

@Mapper
public interface AdminUserMapper {

    List<UserBase> search(@Param("username") String username, @Param("name") String name,
            @Param("status") UserStatus status, @Param("offset") int offset, @Param("pageSize") int pageSize);

    Optional<UserBase> findById(@Param("id") String id);
}
```

`resources/mapper/postgres/app/admin/user/AdminUserMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.admin.user.mapper.AdminUserMapper">

  <resultMap id="userBaseResultMap" type="kkdugi.app.admin.user.models.UserBase"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="id" column="user_id"/>
    <result property="username" column="user_login_id"/>
    <result property="name" column="user_nm"/>
    <result property="remarks" column="user_dc"/>
    <result property="image" column="user_img_src"/>
    <result property="email" column="user_email"/>
    <result property="status" column="user_stat_cd"/>
    <result property="lastLoginAt" column="last_login_dtm"/>
    <result property="lastChangePasswordAt" column="last_chg_pwd_dtm"/>
    <result property="passwordStatus" column="pwd_stat_cd"/>
    <result property="totalSize" column="total_size"/>
  </resultMap>

  <!-- user_pwd는 일부러 뺐다: 조회 경로에서 인코딩된 비밀번호가 메모리에 올라오지 않게 한다. -->
  <sql id="baseColumns">
<![CDATA[
user_id, user_login_id, user_nm, user_dc, user_img_src, user_email, user_stat_cd,
last_login_dtm, last_chg_pwd_dtm, pwd_stat_cd, reg_dtm, reg_id, upd_dtm, upd_id
]]>
  </sql>

  <!--
    * QueryID=search
    * Description=Search users with optional login id / name (case-insensitive contains) and status filters (query-level paging, newest first)
    -->
  <select id="search" resultMap="userBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.search */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
, COUNT(*) OVER() AS total_size
FROM kkdugi_user_base
]]>
    <where>
      <if test="username != null and username != ''">
<![CDATA[
AND LOWER(user_login_id) LIKE '%' || LOWER(#{username}) || '%'
]]>
      </if>
      <if test="name != null and name != ''">
<![CDATA[
AND LOWER(user_nm) LIKE '%' || LOWER(#{name}) || '%'
]]>
      </if>
      <if test="status != null">
<![CDATA[
AND user_stat_cd = #{status}
]]>
      </if>
    </where>
<![CDATA[
ORDER BY reg_dtm DESC, user_id
OFFSET #{offset} LIMIT #{pageSize}
]]>
  </select>

  <!--
    * QueryID=findById
    * Description=Find one user by id
    -->
  <select id="findById" resultMap="userBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.findById */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_user_base
WHERE user_id = #{id}
]]>
  </select>

</mapper>
```

- [ ] **Step 10: 서비스 구현** — `kkdugi/app/admin/user/service/AdminUserService.java`

```java
package kkdugi.app.admin.user.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.admin.user.exceptions.AdminUserNotFoundException;
import kkdugi.app.admin.user.exceptions.AdminUserValidationException;
import kkdugi.app.admin.user.mapper.AdminUserMapper;
import kkdugi.app.admin.user.models.AdminUser;
import kkdugi.app.admin.user.models.AdminUserParams;
import kkdugi.app.admin.user.models.UserBase;
import kkdugi.core.enums.UserStatus;
import kkdugi.core.models.Page;

@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    public static final String ERR_MALFORMED_REQUEST = "user.err.malformed_request";
    public static final String ERR_NOT_FOUND = "user.err.not_found";

    private final AdminUserMapper adminUserMapper;

    public AdminUserService(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    // ---- 조회 ----------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminUser> search(AdminUserParams params) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());
        UserStatus status = isBlank(params.getStatus()) ? null : parseStatus(params.getStatus());

        List<UserBase> rows = adminUserMapper.search(trimToNull(params.getUsername()), trimToNull(params.getName()),
                status, params.getOffset(), params.getLimit());
        List<AdminUser> contents = rows.stream().map(row -> {
            AdminUser content = toContent(row);
            content.setTotalSize(row.getTotalSize());
            return content;
        }).toList();

        return Page.of(contents, params);
    }

    @Transactional(readOnly = true)
    public AdminUser get(String id) {
        return toContent(findExisting(id));
    }

    // ---- 헬퍼 ----------------------------------------------------------

    private UserBase findExisting(String id) {
        return adminUserMapper.findById(id).orElseThrow(() -> {
            log.warn("사용자를 찾을 수 없음: id={}", id);
            return new AdminUserNotFoundException(ERR_NOT_FOUND);
        });
    }

    private static UserStatus parseStatus(String code) {
        try {
            return UserStatus.fromCode(code);
        } catch (IllegalArgumentException e) {
            log.warn("사용자 요청 검증 실패 - 알 수 없는 status: {}", code);
            throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static AdminUser toContent(UserBase row) {
        return new AdminUser(row.getId(), row.getUsername(), row.getName(), row.getRemarks(), row.getImage(),
                row.getEmail(), row.getStatus().getCode(), row.getLastLoginAt(), row.getLastChangePasswordAt(),
                row.getPasswordStatus() == null ? null : row.getPasswordStatus().getCode());
    }
}
```

- [ ] **Step 11: 컨트롤러 구현** — `kkdugi/api/admin/AdminUserController.java`

```java
package kkdugi.api.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.user.exceptions.AdminUserConflictException;
import kkdugi.app.admin.user.exceptions.AdminUserNotFoundException;
import kkdugi.app.admin.user.exceptions.AdminUserValidationException;
import kkdugi.app.admin.user.models.AdminUser;
import kkdugi.app.admin.user.models.AdminUserParams;
import kkdugi.app.admin.user.service.AdminUserService;
import kkdugi.core.Constants;
import kkdugi.core.enums.Rbac;
import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.models.Page;
import kkdugi.core.security.annotation.HasRole;
import kkdugi.core.security.annotation.RequireAuthority;

/**
 * 사용자 관리 API. 권한 관리 API와 같은 기준으로 SYS_ADMIN 역할과 {@code admin/user} 메뉴의 RBAC를
 * 요구한다 — 사용자에게 권한을 부여하고 비밀번호를 초기화할 수 있어 메뉴 RBAC만으로는 권한 상승
 * 경로가 된다. 조회는 READ, 등록/저장/초기화/상태 변경/권한 저장은 WRTE, 삭제는 DELT.
 */
@RestController
@RequestMapping("/api/v1.0/admin/user")
public class AdminUserController {

    private static final String PROGRAM = "admin/user";

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping
    public Page<AdminUser> search(@RequestBody AdminUserParams params) {
        return service.search(params);
    }

    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @GetMapping("/{id}")
    public AdminUser get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @ExceptionHandler(AdminUserValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleValidation(AdminUserValidationException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminUserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ExceptionMessage handleNotFound(AdminUserNotFoundException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminUserConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ExceptionMessage handleConflict(AdminUserConflictException e) {
        return new ExceptionMessage(e.getCode());
    }
}
```

- [ ] **Step 12: 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminUserMapperTest,AdminUserServiceTest,AdminUserControllerTest`
Expected: 모든 테스트 PASS. 실패하면 원인을 고치고 다시 실행한다(메시지 키 누락은 `$.code`만 확인하므로 통과하지만, 세 properties 파일에 키가 있는지 눈으로 확인한다).

- [ ] **Step 13: 체크포인트**

`git status --short`로 이 Task에서 만든/고친 파일(메시지 3개, TestAuthorization, user 패키지, 컨트롤러, XML, 테스트 3개)만 확인한다. 사용자가 커밋을 요청한 경우에만 그 파일들을 경로로 명시해 stage하고 `git commit -m "feat(user): add user list and detail API"`.

### Task 3: 등록(5.3) · 저장(5.4)

**Files:**
- Create: `models/AdminUserPersistRequest.java`
- Modify: `mapper/AdminUserMapper.java`, `AdminUserMapper.xml`, `service/AdminUserService.java`, `AdminUserController.java`, `AdminUserServiceTest`, `AdminUserControllerTest`

**Interfaces:**
- Consumes (Task 1, 2): `TemporaryPasswordGenerator.generate()`, `AdminUserService.findExisting/parseStatus/isBlank/toContent/get`, 예외 3종, `PasswordEncoder`(기존 빈, `kkdugi.core.security.config.SecurityConfigurer`).
- Produces:
  - `AdminUserPersistRequest(String username, String name, String remarks, String image, String email, String status)` (`@Getter @AllArgsConstructor`, `ignoreUnknown`)
  - `AdminUserMapper`: `Optional<UserBase> findByUsername(String)`, `Optional<UserBase> findByEmail(String)`, `int insert(UserBase)`, `int update(UserBase)`
  - `AdminUserService`: `AdminUser regist(AdminUserPersistRequest)`, `AdminUser save(String id, AdminUserPersistRequest)`; 상수 `ERR_DUPLICATE_USERNAME`, `ERR_DUPLICATE_EMAIL`, `ERR_IMMUTABLE`; private `logTemporaryPassword(String userId, String temporaryPassword)` — 로그 형식 `"TODO(mail) 임시 비밀번호 발급 - 메일 발송 구현 시 이 로그를 제거한다: userId={}, temporaryPassword={}"`(Task 4가 재사용, 테스트는 `getArgumentArray()[0]`=userId, `[1]`=비밀번호로 읽는다)

- [ ] **Step 1: 서비스 테스트 추가** — `AdminUserServiceTest`

import 추가:
```java
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import kkdugi.app.admin.user.exceptions.AdminUserConflictException;
import kkdugi.app.admin.user.models.AdminUserPersistRequest;
```

필드/픽스처 추가(기존 `setUp`/`cleanUp`에 로그 어펜더 연결·해제를 넣는다):
```java
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    // setUp() 끝에 추가
        serviceLogger = (Logger) LoggerFactory.getLogger(AdminUserService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);

    // cleanUp() 맨 앞에 추가
        serviceLogger.detachAppender(appender);
```

헬퍼 추가:
```java
    private static AdminUserPersistRequest request(String username, String name, String email, String status) {
        return new AdminUserPersistRequest(username, name, "memo", "img.png", email, status);
    }

    /** TODO(mail) 로그에서 (userId → 평문 임시 비밀번호)를 꺼낸다. */
    private Map<String, String> loggedTemporaryPasswords() {
        Map<String, String> result = new LinkedHashMap<>();
        for (ILoggingEvent event : appender.list) {
            if (event.getFormattedMessage().contains("TODO(mail)")) {
                Object[] args = event.getArgumentArray();
                result.put((String) args[0], (String) args[1]);
            }
        }
        return result;
    }

    private String storedPassword(String userId) {
        return jdbcTemplate.queryForObject("SELECT user_pwd FROM kkdugi_user_base WHERE user_id = ?", String.class, userId);
    }
```

테스트 추가:
```java
    @Test
    void regist_createsUserWithSerialId_encodedTemporaryPassword_andLogsIt() {
        AdminUser created = service.regist(request("test_usr_svc_new", "New User", "test_usr_svc_new@example.com", null));

        assertThat(created.getId()).matches("U\\d{12}\\d{4}");
        assertThat(created.getStatus()).isEqualTo("20");
        assertThat(created.getPasswordStatus()).isEqualTo("10");
        assertThat(created.getRemarks()).isEqualTo("memo");
        assertThat(created.getImage()).isEqualTo("img.png");

        String stored = storedPassword(created.getId());
        assertThat(stored).startsWith("{bcrypt}");
        Map<String, String> logged = loggedTemporaryPasswords();
        assertThat(logged).containsOnlyKeys(created.getId());
        assertThat(passwordEncoder.matches(logged.get(created.getId()), stored)).isTrue();
    }

    @Test
    void regist_appliesTheGivenStatus() {
        AdminUser created = service.regist(request("test_usr_svc_pend", "Pending", "test_usr_svc_pend@example.com", "10"));

        assertThat(created.getStatus()).isEqualTo("10");
    }

    @Test
    void regist_rejectsMalformedRequests_andWritesNothing() {
        String tooLong = "x".repeat(101);
        for (AdminUserPersistRequest bad : java.util.List.of(
                request(null, "Name", "test_usr_svc_a@example.com", null),
                request("  ", "Name", "test_usr_svc_a@example.com", null),
                request("test_usr_svc_a", null, "test_usr_svc_a@example.com", null),
                request("test_usr_svc_a", "Name", null, null),
                request(tooLong, "Name", "test_usr_svc_a@example.com", null),
                request("test_usr_svc_a", "Name", "test_usr_svc_a@example.com", "99"))) {
            assertThatThrownBy(() -> service.regist(bad))
                    .isInstanceOf(AdminUserValidationException.class)
                    .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
        }
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_login_id LIKE 'test_usr_svc_a%'")).isZero();
        assertThat(loggedTemporaryPasswords()).isEmpty();
    }

    @Test
    void regist_rejectsDuplicateUsernameAndEmail() {
        assertThatThrownBy(() -> service.regist(request("test_usr_svc_one", "Dup", "test_usr_svc_dup@example.com", null)))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_DUPLICATE_USERNAME);
        assertThatThrownBy(() -> service.regist(request("test_usr_svc_dup", "Dup", "test_usr_svc_one@example.com", null)))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_DUPLICATE_EMAIL);
    }

    @Test
    void save_updatesEditableFields_keepsStatusWhenOmitted_andNeverTouchesThePassword() {
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_pwd = 'stored-hash' WHERE user_id = ?", USER_1);

        AdminUser saved = service.save(USER_1,
                new AdminUserPersistRequest(null, "Renamed", "new memo", null, "test_usr_svc_renamed@example.com", null));

        assertThat(saved.getName()).isEqualTo("Renamed");
        assertThat(saved.getRemarks()).isEqualTo("new memo");
        assertThat(saved.getImage()).isNull();
        assertThat(saved.getEmail()).isEqualTo("test_usr_svc_renamed@example.com");
        assertThat(saved.getUsername()).isEqualTo("test_usr_svc_one");
        assertThat(saved.getStatus()).isEqualTo("20");
        assertThat(storedPassword(USER_1)).isEqualTo("stored-hash");
    }

    @Test
    void save_appliesAGivenStatus_andAllowsResavingTheOwnEmail() {
        AdminUser saved = service.save(USER_1,
                request("test_usr_svc_one", "Svc One", "test_usr_svc_one@example.com", "30"));

        assertThat(saved.getStatus()).isEqualTo("30");
    }

    @Test
    void save_rejectsChangingTheUsername() {
        assertThatThrownBy(() -> service.save(USER_1, request("test_usr_svc_other", "Svc One", "test_usr_svc_one@example.com", null)))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_IMMUTABLE);
        assertThat(service.get(USER_1).getUsername()).isEqualTo("test_usr_svc_one");
    }

    @Test
    void save_rejectsAnotherUsersEmail_unknownUser_andMalformedBody() {
        assertThatThrownBy(() -> service.save(USER_1, request(null, "Svc One", "test_usr_svc_two@example.com", null)))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_DUPLICATE_EMAIL);
        assertThatThrownBy(() -> service.save("U_TEST_USR_SVC_MISSING", request(null, "Name", "test_usr_svc_x@example.com", null)))
                .isInstanceOf(AdminUserNotFoundException.class)
                .hasMessage(AdminUserService.ERR_NOT_FOUND);
        assertThatThrownBy(() -> service.save(USER_1, request(null, "", "test_usr_svc_x@example.com", null)))
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
    }
```

- [ ] **Step 2: 컨트롤러 테스트 추가** — `AdminUserControllerTest`

테스트 추가:
```java
    @Test
    void regist_returnsCreatedUser_andNeverExposesThePassword() throws Exception {
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("id", "ignored", "username", "test_usr_api_new", "name", "New Api",
                                "email", "test_usr_api_new@example.com", "remarks", "r", "image", "i.png"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.matchesPattern("U\\d{16}")))
                .andExpect(jsonPath("$.username").value("test_usr_api_new"))
                .andExpect(jsonPath("$.status").value("20"))
                .andExpect(jsonPath("$.passwordStatus").value("10"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void regist_returns400ForMalformedBody_and409ForDuplicates() throws Exception {
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_bad", "email", "test_usr_api_bad@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.err.malformed_request"));

        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_one", "name", "Dup", "email", "test_usr_api_dup@example.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.err.duplicate_username"));

        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_dup", "name", "Dup", "email", "test_usr_api_one@example.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.err.duplicate_email"));
    }

    @Test
    void save_updatesTheUser_rejectsUsernameChange_and404ForUnknownUser() throws Exception {
        mockMvc.perform(post(URL + "/" + USER_1).contentType(APPLICATION_JSON)
                        .content(json(map("id", USER_1, "username", "test_usr_api_one", "name", "Saved",
                                "email", "test_usr_api_one@example.com", "status", "30"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Saved"))
                .andExpect(jsonPath("$.status").value("30"))
                .andExpect(jsonPath("$.passwordStatus").value("30"));

        mockMvc.perform(post(URL + "/" + USER_1).contentType(APPLICATION_JSON)
                        .content(json(map("username", "test_usr_api_changed", "name", "Saved",
                                "email", "test_usr_api_one@example.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.err.immutable"));

        mockMvc.perform(post(URL + "/U_TEST_USR_API_MISSING").contentType(APPLICATION_JSON)
                        .content(json(map("name", "X", "email", "test_usr_api_x@example.com"))))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Step 3: 실패 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminUserServiceTest,AdminUserControllerTest`
Expected: 컴파일 오류(`AdminUserPersistRequest`, `regist`, `save`, 상수 없음)로 FAIL.

- [ ] **Step 4: 요청 모델** — `models/AdminUserPersistRequest.java`

```java
package kkdugi.app.admin.user.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * regist/save 요청 본문. {@code id}·{@code lastLoginAt} 같은 서버 관리 필드는 실려 와도 무시한다
 * ({@code ignoreUnknown}) — 상세 응답을 그대로 save 요청으로 돌려보내도 된다. save에서는 본문 id가 아니라
 * 경로 id를 쓰고, {@code username}은 생략하거나 기존 값과 같아야 한다.
 *
 * <p>Jackson 3는 Lombok 전체 필드 생성자 + final 필드 클래스를 컴파일러 {@code -parameters}에 의존해
 * 역직렬화한다({@code AdminAuthorityPersistRequest}와 동일).
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserPersistRequest {

    private final String username;
    private final String name;
    private final String remarks;
    private final String image;
    private final String email;
    private final String status;
}
```

- [ ] **Step 5: 매퍼 확장**

`AdminUserMapper.java`에 추가:
```java
    Optional<UserBase> findByUsername(@Param("username") String username);

    Optional<UserBase> findByEmail(@Param("email") String email);

    int insert(UserBase row);

    int update(UserBase row);
```

`AdminUserMapper.xml`의 `</mapper>` 바로 위에 추가:
```xml
  <!--
    * QueryID=findByUsername
    * Description=Find one user by its unique login id
    -->
  <select id="findByUsername" resultMap="userBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.findByUsername */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_user_base
WHERE user_login_id = #{username}
]]>
  </select>

  <!--
    * QueryID=findByEmail
    * Description=Find one user by its unique email address
    -->
  <select id="findByEmail" resultMap="userBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.findByEmail */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_user_base
WHERE user_email = #{email}
]]>
  </select>

  <!--
    * QueryID=insert
    * Description=Insert one user row (password is already encoded)
    -->
  <insert id="insert" parameterType="kkdugi.app.admin.user.models.UserBase">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.insert */
INSERT INTO kkdugi_user_base
    (user_id, user_login_id, user_pwd, user_nm, user_dc, user_img_src, user_email,
     pwd_stat_cd, user_stat_cd, reg_dtm, reg_id)
VALUES
    (#{id}, #{username}, #{password}, #{name}, #{remarks}, #{image}, #{email},
     #{passwordStatus}, #{status}, #{createdAt}, #{creatorId})
]]>
  </insert>

  <!--
    * QueryID=update
    * Description=Update the editable columns of one user (login id and password are never touched)
    -->
  <update id="update" parameterType="kkdugi.app.admin.user.models.UserBase">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.update */
UPDATE kkdugi_user_base
SET user_nm      = #{name},
    user_dc      = #{remarks},
    user_img_src = #{image},
    user_email   = #{email},
    user_stat_cd = #{status},
    upd_dtm      = #{updatedAt},
    upd_id       = #{updaterId}
WHERE user_id = #{id}
]]>
  </update>
```

- [ ] **Step 6: 서비스 확장** — `AdminUserService.java`

import 추가:
```java
import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import kkdugi.app.admin.user.exceptions.AdminUserConflictException;
import kkdugi.app.admin.user.models.AdminUserPersistRequest;
import kkdugi.core.enums.PasswordStatus;
import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;
```

상수·필드·생성자를 아래로 교체(기존 `ERR_*` 두 줄은 유지하고 그 아래에 이어 붙인다):
```java
    public static final String ERR_DUPLICATE_USERNAME = "user.err.duplicate_username";
    public static final String ERR_DUPLICATE_EMAIL = "user.err.duplicate_email";
    public static final String ERR_IMMUTABLE = "user.err.immutable";

    private static final String SYSTEM_USER_ID = "SYSTEM";
    private static final UserStatus DEFAULT_STATUS = UserStatus.NORM;
    private static final int MAX_USERNAME_LENGTH = 100;
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_EMAIL_LENGTH = 200;
    private static final int MAX_REMARKS_LENGTH = 1000;
    private static final int MAX_IMAGE_LENGTH = 500;

    /** ID 형식 {@code U{yyyyMMddHHmm}{0000}} — V8 시드가 쓰는 KKDUGI_USER 카운터와 같다. */
    private static final SerialConfig SERIAL_CONFIG = new SerialConfig() {

        @Override
        public String getId() {
            return "KKDUGI_USER";
        }

        @Override
        public String getValueFormatter() {
            return "U%s%04d";
        }
    };

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;

    public AdminUserService(AdminUserMapper adminUserMapper, PasswordEncoder passwordEncoder,
            TemporaryPasswordGenerator temporaryPasswordGenerator) {
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
    }
```
(기존 `private final AdminUserMapper adminUserMapper;`와 한 인자 생성자는 삭제한다.)

`// ---- 조회 ----` 블록 뒤, `// ---- 헬퍼 ----` 앞에 추가:
```java
    // ---- 쓰기 ----------------------------------------------------------

    @Transactional
    public AdminUser regist(AdminUserPersistRequest request) {
        validate(request, true);
        UserStatus status = isBlank(request.getStatus()) ? DEFAULT_STATUS : parseStatus(request.getStatus());
        checkDuplicateUsername(request.getUsername());
        checkDuplicateEmail(request.getEmail(), null);

        LocalDateTime now = LocalDateTime.now();
        String id = SerialUtils.next(SERIAL_CONFIG);
        String temporaryPassword = temporaryPasswordGenerator.generate();

        UserBase row = new UserBase(id, request.getUsername(), request.getName(), request.getRemarks(),
                request.getImage(), request.getEmail(), status);
        row.setPassword(passwordEncoder.encode(temporaryPassword));
        row.setPasswordStatus(PasswordStatus.NEWP);
        row.setCreatedAt(now);
        row.setCreatorId(SYSTEM_USER_ID);
        try {
            adminUserMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw duplicateOf(e);
        }
        logTemporaryPassword(id, temporaryPassword);
        return get(id);
    }

    @Transactional
    public AdminUser save(String id, AdminUserPersistRequest request) {
        validate(request, false);
        UserBase existing = findExisting(id);
        if (!isBlank(request.getUsername()) && !request.getUsername().equals(existing.getUsername())) {
            log.warn("사용자 저장 실패 - username 변경 시도: id={}", id);
            throw new AdminUserConflictException(ERR_IMMUTABLE);
        }
        UserStatus status = isBlank(request.getStatus()) ? existing.getStatus() : parseStatus(request.getStatus());
        checkDuplicateEmail(request.getEmail(), id);

        UserBase row = new UserBase(id, existing.getUsername(), request.getName(), request.getRemarks(),
                request.getImage(), request.getEmail(), status);
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdaterId(SYSTEM_USER_ID);
        try {
            adminUserMapper.update(row);
        } catch (DuplicateKeyException e) {
            throw duplicateOf(e);
        }
        return get(id);
    }

    // ---- 검증 ----------------------------------------------------------

    private void validate(AdminUserPersistRequest request, boolean usernameRequired) {
        boolean invalid = isBlank(request.getName()) || isBlank(request.getEmail())
                || (usernameRequired && isBlank(request.getUsername()))
                || tooLong(request.getUsername(), MAX_USERNAME_LENGTH)
                || tooLong(request.getName(), MAX_NAME_LENGTH)
                || tooLong(request.getEmail(), MAX_EMAIL_LENGTH)
                || tooLong(request.getRemarks(), MAX_REMARKS_LENGTH)
                || tooLong(request.getImage(), MAX_IMAGE_LENGTH);
        if (invalid) {
            log.warn("사용자 저장 검증 실패 - 필수값 누락/길이 초과: username={}", request.getUsername());
            throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
        }
    }

    private void checkDuplicateUsername(String username) {
        adminUserMapper.findByUsername(username).ifPresent(found -> {
            log.warn("사용자 등록 실패 - username 중복: {}", username);
            throw new AdminUserConflictException(ERR_DUPLICATE_USERNAME);
        });
    }

    private void checkDuplicateEmail(String email, String excludeId) {
        adminUserMapper.findByEmail(email).ifPresent(found -> {
            if (!found.getId().equals(excludeId)) {
                log.warn("사용자 저장 실패 - email 중복: {}", email);
                throw new AdminUserConflictException(ERR_DUPLICATE_EMAIL);
            }
        });
    }

    /** 사전 조회를 통과한 동시 요청은 유니크 제약 위반으로 잡힌다 — 어느 제약인지 이름으로 구분한다. */
    private static AdminUserConflictException duplicateOf(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        log.warn("사용자 저장 실패 - 동시 등록으로 유니크 제약 위반: {}", message);
        return new AdminUserConflictException(
                message != null && message.contains("udx_email") ? ERR_DUPLICATE_EMAIL : ERR_DUPLICATE_USERNAME);
    }
```

`// ---- 헬퍼 ----` 블록에 추가:
```java
    /**
     * TODO(mail): 메일 발송이 구현되면 이 로그를 제거하고 임시 비밀번호를 메일로 전달한다. 그때까지는
     * 운영자가 로그에서 임시 비밀번호를 확인한다 — 평문이 로그에 남는 임시 조치다.
     */
    private void logTemporaryPassword(String userId, String temporaryPassword) {
        log.warn("TODO(mail) 임시 비밀번호 발급 - 메일 발송 구현 시 이 로그를 제거한다: userId={}, temporaryPassword={}",
                userId, temporaryPassword);
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }
```

- [ ] **Step 7: 컨트롤러 확장** — `AdminUserController.java`

import 추가: `kkdugi.app.admin.user.models.AdminUserPersistRequest`. `get` 메서드 뒤에 추가:
```java
    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/regist")
    public AdminUser regist(@RequestBody AdminUserPersistRequest request) {
        return service.regist(request);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}")
    public AdminUser save(@PathVariable("id") String id, @RequestBody AdminUserPersistRequest request) {
        return service.save(id, request);
    }
```

- [ ] **Step 8: 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminUserMapperTest,AdminUserServiceTest,AdminUserControllerTest`
Expected: 모두 PASS. `regist_...`의 `matchesPattern`이 Hamcrest 의존성 문제로 컴파일되지 않으면 그 줄을 `jsonPath("$.id").exists()`로 바꾸고 서비스 테스트의 `matches("U\\d{12}\\d{4}")`가 형식을 검증하는 것으로 갈음한다.

- [ ] **Step 9: 체크포인트**

이 Task 파일만 stage 대상으로 확인한다. 사용자가 커밋을 요청한 경우에만 `git commit -m "feat(user): add user register and save API"`.

### Task 4: 비밀번호 초기화(5.7) · 삭제(5.8) · 상태 일괄 변경(5.9)

**Files:**
- Create: `models/AdminUserIds.java`, `models/AdminUserChangeStatusRequest.java`
- Modify: `mapper/AdminUserMapper.java`, `AdminUserMapper.xml`, `service/AdminUserService.java`, `AdminUserController.java`, `AdminUserMapperTest`, `AdminUserServiceTest`, `AdminUserControllerTest`

**Interfaces:**
- Consumes (Task 3): `logTemporaryPassword(userId, password)`, `passwordEncoder`, `temporaryPasswordGenerator`, `SYSTEM_USER_ID`, `parseStatus`, `isBlank`, `findExisting`; `kkdugi.core.util.SessionUtils.getUser().getId()`; 상수 `ERR_MALFORMED_REQUEST`, `ERR_NOT_FOUND`.
- Produces:
  - `AdminUserIds(List<String> id)`, `AdminUserChangeStatusRequest(List<String> id, String status)` — 둘 다 `@JsonCreator(mode = PROPERTIES)` 명시
  - `AdminUserMapper`: `List<String> findExistingIds(List<String> ids)`, `int updatePassword(String id, String password, PasswordStatus passwordStatus, LocalDateTime changedAt, String updaterId)`, `int updateStatus(List<String> ids, UserStatus status, LocalDateTime updatedAt, String updaterId)`, `int deleteSessionsByUserId(String userId)`, `int deleteUserAuthsByUserId(String userId)`, `int deleteById(String id)`
  - `AdminUserService`: `void resetPassword(AdminUserIds)`, `void delete(String id)`, `void changeStatus(AdminUserChangeStatusRequest)`; 상수 `ERR_SELF_DELETE = "user.err.self_delete"`

- [ ] **Step 1: 매퍼 테스트 추가** — `AdminUserMapperTest`

import 추가: `java.time.LocalDateTime`, `kkdugi.core.enums.PasswordStatus`.

테스트 추가:
```java
    @Test
    void findByUsernameAndEmail_matchExactly() {
        assertThat(mapper.findByUsername("test_usr_mapper_alpha")).map(UserBase::getId).contains(USER_1);
        assertThat(mapper.findByUsername("TEST_USR_MAPPER_ALPHA")).isEmpty();
        assertThat(mapper.findByEmail("test_usr_mapper_beta@example.com")).map(UserBase::getId).contains(USER_2);
        assertThat(mapper.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void findExistingIds_returnsOnlyTheIdsThatExist() {
        assertThat(mapper.findExistingIds(List.of(USER_1, USER_3, "U_TEST_USR_MAPPER_MISSING")))
                .containsExactlyInAnyOrder(USER_1, USER_3);
    }

    @Test
    void updatePassword_setsPasswordStatusAndChangeTimestamp_forOneUserOnly() {
        LocalDateTime changedAt = LocalDateTime.of(2031, 2, 3, 4, 5, 6);

        assertThat(mapper.updatePassword(USER_1, "{bcrypt}new", PasswordStatus.NEWP, changedAt, "SYSTEM")).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("SELECT user_pwd FROM kkdugi_user_base WHERE user_id = ?", String.class, USER_1))
                .isEqualTo("{bcrypt}new");
        assertThat(jdbcTemplate.queryForObject("SELECT pwd_stat_cd FROM kkdugi_user_base WHERE user_id = ?", String.class, USER_1))
                .isEqualTo("10");
        assertThat(mapper.findById(USER_1).orElseThrow().getLastChangePasswordAt()).isEqualTo(changedAt);
        assertThat(mapper.findById(USER_2).orElseThrow().getPasswordStatus()).isNull();
    }

    @Test
    void updateStatus_changesEveryGivenUser() {
        assertThat(mapper.updateStatus(List.of(USER_1, USER_2), UserStatus.SUPD, LocalDateTime.now(), "SYSTEM")).isEqualTo(2);

        assertThat(mapper.findById(USER_1).orElseThrow().getStatus()).isEqualTo(UserStatus.SUPD);
        assertThat(mapper.findById(USER_2).orElseThrow().getStatus()).isEqualTo(UserStatus.SUPD);
        assertThat(mapper.findById(USER_3).orElseThrow().getStatus()).isEqualTo(UserStatus.NORM);
    }

    @Test
    void deleteQueries_removeSessionsAuthorityMappingsAndTheUserRow() {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                + "VALUES ('A_TEST_USR_MAPPER_1', 'TEST_USR_MAPPER_ROLE', 'ROLE', 'Mapper Role', 'SYSTEM')");
        jdbcTemplate.update("INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                + "VALUES (?, 'A_TEST_USR_MAPPER_1', CURRENT_DATE, DATE '9999-12-31', 'SYSTEM')", USER_1);
        jdbcTemplate.update("INSERT INTO kkdugi_session (sess_id, user_id, exp_dtm) VALUES ('S_TEST_USR_MAPPER_1', ?, ?)",
                USER_1, Timestamp.valueOf("2099-01-01 00:00:00"));
        try {
            assertThat(mapper.deleteSessionsByUserId(USER_1)).isEqualTo(1);
            assertThat(mapper.deleteUserAuthsByUserId(USER_1)).isEqualTo(1);
            assertThat(mapper.deleteById(USER_1)).isEqualTo(1);

            assertThat(mapper.findById(USER_1)).isEmpty();
            assertThat(mapper.findById(USER_2)).isPresent();
        } finally {
            jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_id = 'A_TEST_USR_MAPPER_1'");
        }
    }
```

- [ ] **Step 2: 서비스 테스트 추가** — `AdminUserServiceTest`

import 추가:
```java
import java.util.List;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import kkdugi.app.admin.user.models.AdminUserChangeStatusRequest;
import kkdugi.app.admin.user.models.AdminUserIds;
import kkdugi.core.security.authentication.SessionAuthentication;
import kkdugi.core.security.models.SessionUser;
import kkdugi.support.TestAuthorization;
```
(Task 3의 `java.util.List.of(...)` 완전수식 사용은 그대로 둬도 된다.)

`cleanUp()` 안에 `SecurityContextHolder.clearContext();`를 추가한다.

테스트 추가:
```java
    @Test
    void resetPassword_replacesEveryPassword_marksItNew_andLogsEachOne() {
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_pwd = 'old-hash', pwd_stat_cd = '30' WHERE user_id IN (?, ?)",
                USER_1, USER_2);

        service.resetPassword(new AdminUserIds(List.of(USER_1, USER_2, USER_1)));

        Map<String, String> logged = loggedTemporaryPasswords();
        assertThat(logged).containsOnlyKeys(USER_1, USER_2);
        for (String id : List.of(USER_1, USER_2)) {
            String stored = storedPassword(id);
            assertThat(stored).startsWith("{bcrypt}");
            assertThat(passwordEncoder.matches(logged.get(id), stored)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT pwd_stat_cd FROM kkdugi_user_base WHERE user_id = ?", String.class, id))
                    .isEqualTo("10");
            assertThat(service.get(id).getLastChangePasswordAt()).isNotNull();
        }
    }

    @Test
    void resetPassword_unknownId_throwsNotFound_andChangesNothing() {
        jdbcTemplate.update("UPDATE kkdugi_user_base SET user_pwd = 'old-hash' WHERE user_id = ?", USER_1);

        assertThatThrownBy(() -> service.resetPassword(new AdminUserIds(List.of(USER_1, "U_TEST_USR_SVC_MISSING"))))
                .isInstanceOf(AdminUserNotFoundException.class)
                .hasMessage(AdminUserService.ERR_NOT_FOUND);

        assertThat(storedPassword(USER_1)).isEqualTo("old-hash");
        assertThat(loggedTemporaryPasswords()).isEmpty();
    }

    @Test
    void resetPassword_andChangeStatus_rejectEmptyOrBlankIds() {
        for (List<String> bad : java.util.Arrays.asList(null, List.<String>of(), List.of(" "))) {
            assertThatThrownBy(() -> service.resetPassword(new AdminUserIds(bad)))
                    .isInstanceOf(AdminUserValidationException.class)
                    .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
            assertThatThrownBy(() -> service.changeStatus(new AdminUserChangeStatusRequest(bad, "20")))
                    .isInstanceOf(AdminUserValidationException.class)
                    .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
        }
    }

    @Test
    void changeStatus_updatesEveryUser_andIgnoresDuplicateIds() {
        service.changeStatus(new AdminUserChangeStatusRequest(List.of(USER_1, USER_2, USER_2), "50"));

        assertThat(service.get(USER_1).getStatus()).isEqualTo("50");
        assertThat(service.get(USER_2).getStatus()).isEqualTo("50");
    }

    @Test
    void changeStatus_unknownIdOrStatus_changesNothing() {
        assertThatThrownBy(() -> service.changeStatus(new AdminUserChangeStatusRequest(List.of(USER_1, "U_TEST_USR_SVC_MISSING"), "50")))
                .isInstanceOf(AdminUserNotFoundException.class);
        assertThatThrownBy(() -> service.changeStatus(new AdminUserChangeStatusRequest(List.of(USER_1), "99")))
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
        assertThatThrownBy(() -> service.changeStatus(new AdminUserChangeStatusRequest(List.of(USER_1), null)))
                .isInstanceOf(AdminUserValidationException.class);

        assertThat(service.get(USER_1).getStatus()).isEqualTo("20");
    }

    @Test
    void delete_removesTheUserItsSessionsAndItsAuthorityMappings_butNotTheAuthority() {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                + "VALUES ('A_TEST_USR_SVC_1', 'TEST_USR_SVC_ROLE', 'ROLE', 'Svc Role', 'SYSTEM')");
        jdbcTemplate.update("INSERT INTO kkdugi_user_auth (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_id) "
                + "VALUES (?, 'A_TEST_USR_SVC_1', CURRENT_DATE, DATE '9999-12-31', 'SYSTEM')", USER_1);
        jdbcTemplate.update("INSERT INTO kkdugi_session (sess_id, user_id, exp_dtm) VALUES ('S_TEST_USR_SVC_1', ?, ?)",
                USER_1, java.sql.Timestamp.valueOf("2099-01-01 00:00:00"));

        service.delete(USER_1);

        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_id = ?", USER_1)).isZero();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_auth WHERE user_id = ?", USER_1)).isZero();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_session WHERE sess_id = 'S_TEST_USR_SVC_1'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_base WHERE auth_id = 'A_TEST_USR_SVC_1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_id = ?", USER_2)).isEqualTo(1);
    }

    @Test
    void delete_isIdempotentForAnAlreadyMissingUser() {
        service.delete("U_TEST_USR_SVC_MISSING");
    }

    @Test
    void delete_rejectsDeletingTheCurrentSessionUser() {
        SessionAuthentication authentication = TestAuthorization.session("admin/user", 15, "SYS_ADMIN");
        ((SessionUser) authentication.getPrincipal()).setId(USER_1);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        assertThatThrownBy(() -> service.delete(USER_1))
                .isInstanceOf(AdminUserConflictException.class)
                .hasMessage(AdminUserService.ERR_SELF_DELETE);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_id = ?", USER_1)).isEqualTo(1);
    }
```

- [ ] **Step 3: 컨트롤러 테스트 추가** — `AdminUserControllerTest`

import 추가: `java.util.List`.

테스트 추가:
```java
    @Test
    void resetPassword_returns200_404ForUnknown_and400ForEmpty() throws Exception {
        mockMvc.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of(USER_1, USER_2)))))
                .andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + USER_1))
                .andExpect(jsonPath("$.passwordStatus").value("10"))
                .andExpect(jsonPath("$.lastChangePasswordAt").isNotEmpty());

        mockMvc.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of("U_TEST_USR_API_MISSING")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user.err.not_found"));

        mockMvc.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.err.malformed_request"));
    }

    @Test
    void changeStatus_updatesUsers_andReturns400ForAnUnknownStatus() throws Exception {
        mockMvc.perform(post(URL + "/change-status").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of(USER_1, USER_2), "status", "50"))))
                .andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + USER_2))
                .andExpect(jsonPath("$.status").value("50"));

        mockMvc.perform(post(URL + "/change-status").contentType(APPLICATION_JSON)
                        .content(json(map("id", List.of(USER_1), "status", "99"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_removesTheUser_andIsIdempotent() throws Exception {
        mockMvc.perform(post(URL + "/" + USER_2 + "/delete")).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + USER_2)).andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/" + USER_2 + "/delete")).andExpect(status().isOk());
    }
```
- [ ] **Step 4: 실패 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminUserMapperTest,AdminUserServiceTest,AdminUserControllerTest`
Expected: 컴파일 오류(`AdminUserIds`, `AdminUserChangeStatusRequest`, 새 매퍼/서비스 메서드, `ERR_SELF_DELETE` 없음)로 FAIL.

- [ ] **Step 5: 요청 모델 2종**

`models/AdminUserIds.java`:
```java
package kkdugi.app.admin.user.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * {@code { "id": ["U...", ...] }} 요청 본문(비밀번호 초기화). 인자가 하나뿐인 생성자는 Jackson이 위임
 * 생성자로 오인할 수 있어 {@code PROPERTIES} 모드를 명시한다.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserIds {

    private final List<String> id;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public AdminUserIds(@JsonProperty("id") List<String> id) {
        this.id = id;
    }
}
```

`models/AdminUserChangeStatusRequest.java`:
```java
package kkdugi.app.admin.user.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/** {@code { "id": [...], "status": "20" }} 요청 본문(상태 일괄 변경). */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserChangeStatusRequest {

    private final List<String> id;
    private final String status;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public AdminUserChangeStatusRequest(@JsonProperty("id") List<String> id, @JsonProperty("status") String status) {
        this.id = id;
        this.status = status;
    }
}
```

- [ ] **Step 6: 매퍼 확장**

`AdminUserMapper.java` import에 `java.time.LocalDateTime`, `kkdugi.core.enums.PasswordStatus` 추가 후 메서드 추가:
```java
    /** {@code ids}는 비어 있으면 안 된다(빈 목록은 {@code IN ()} SQL 구문 오류). */
    List<String> findExistingIds(@Param("ids") List<String> ids);

    int updatePassword(@Param("id") String id, @Param("password") String password,
            @Param("passwordStatus") PasswordStatus passwordStatus, @Param("changedAt") LocalDateTime changedAt,
            @Param("updaterId") String updaterId);

    /** {@code ids}는 비어 있으면 안 된다. */
    int updateStatus(@Param("ids") List<String> ids, @Param("status") UserStatus status,
            @Param("updatedAt") LocalDateTime updatedAt, @Param("updaterId") String updaterId);

    int deleteSessionsByUserId(@Param("userId") String userId);

    int deleteUserAuthsByUserId(@Param("userId") String userId);

    int deleteById(@Param("id") String id);
```

`AdminUserMapper.xml`의 `</mapper>` 위에 추가:
```xml
  <!--
    * QueryID=findExistingIds
    * Description=Return the subset of the given (non-empty) user ids that exist
    -->
  <select id="findExistingIds" resultType="string">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.findExistingIds */
SELECT user_id
FROM kkdugi_user_base
WHERE user_id IN
]]>
    <foreach item="id" collection="ids" open="(" separator="," close=")">
      #{id}
    </foreach>
  </select>

  <!--
    * QueryID=updatePassword
    * Description=Replace one user's (already encoded) password and mark it as new (change required)
    -->
  <update id="updatePassword">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.updatePassword */
UPDATE kkdugi_user_base
SET user_pwd         = #{password},
    pwd_stat_cd      = #{passwordStatus},
    last_chg_pwd_dtm = #{changedAt},
    upd_dtm          = #{changedAt},
    upd_id           = #{updaterId}
WHERE user_id = #{id}
]]>
  </update>

  <!--
    * QueryID=updateStatus
    * Description=Set the status of every given (non-empty) user id
    -->
  <update id="updateStatus">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.updateStatus */
UPDATE kkdugi_user_base
SET user_stat_cd = #{status},
    upd_dtm      = #{updatedAt},
    upd_id       = #{updaterId}
WHERE user_id IN
]]>
    <foreach item="id" collection="ids" open="(" separator="," close=")">
      #{id}
    </foreach>
  </update>

  <!--
    * QueryID=deleteSessionsByUserId
    * Description=Delete every session of one user (kkdugi_session.user_id is a FK)
    -->
  <delete id="deleteSessionsByUserId">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.deleteSessionsByUserId */
DELETE FROM kkdugi_session
WHERE user_id = #{userId}
]]>
  </delete>

  <!--
    * QueryID=deleteUserAuthsByUserId
    * Description=Delete every authority mapping of one user (kkdugi_user_auth.user_id is a FK)
    -->
  <delete id="deleteUserAuthsByUserId">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.deleteUserAuthsByUserId */
DELETE FROM kkdugi_user_auth
WHERE user_id = #{userId}
]]>
  </delete>

  <!--
    * QueryID=deleteById
    * Description=Delete one user row (callers delete its sessions/authority mappings first)
    -->
  <delete id="deleteById">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.deleteById */
DELETE FROM kkdugi_user_base
WHERE user_id = #{id}
]]>
  </delete>
```

- [ ] **Step 7: 서비스 확장** — `AdminUserService.java`

import 추가:
```java
import kkdugi.app.admin.user.models.AdminUserChangeStatusRequest;
import kkdugi.app.admin.user.models.AdminUserIds;
import kkdugi.core.util.SessionUtils;
```

상수 추가(`ERR_IMMUTABLE` 아래): `public static final String ERR_SELF_DELETE = "user.err.self_delete";`

`save` 메서드 뒤(`// ---- 검증 ----` 앞)에 추가:
```java
    /** 대상마다 새 임시 비밀번호를 만든다. 없는 id가 하나라도 있으면 아무것도 바꾸지 않는다(404). */
    @Transactional
    public void resetPassword(AdminUserIds request) {
        List<String> ids = distinctIds(request.getId());
        requireAllExist(ids);

        LocalDateTime now = LocalDateTime.now();
        for (String id : ids) {
            String temporaryPassword = temporaryPasswordGenerator.generate();
            adminUserMapper.updatePassword(id, passwordEncoder.encode(temporaryPassword), PasswordStatus.NEWP, now,
                    SYSTEM_USER_ID);
            logTemporaryPassword(id, temporaryPassword);
        }
    }

    /** 물리 삭제. 이미 없으면 아무것도 하지 않고(200), 현재 로그인한 자기 자신은 지울 수 없다(409). */
    @Transactional
    public void delete(String id) {
        if (id.equals(SessionUtils.getUser().getId())) {
            log.warn("사용자 삭제 실패 - 자기 자신 삭제 시도: id={}", id);
            throw new AdminUserConflictException(ERR_SELF_DELETE);
        }
        if (adminUserMapper.findById(id).isEmpty()) {
            log.info("사용자 삭제 - 이미 없는 사용자라 아무것도 하지 않음: id={}", id);
            return;
        }
        adminUserMapper.deleteSessionsByUserId(id);
        adminUserMapper.deleteUserAuthsByUserId(id);
        adminUserMapper.deleteById(id);
    }

    /** 전부 성공하거나 전부 실패한다. 없는 id가 있으면 404, 빈 목록/빈 id/알 수 없는 status는 400. */
    @Transactional
    public void changeStatus(AdminUserChangeStatusRequest request) {
        List<String> ids = distinctIds(request.getId());
        if (isBlank(request.getStatus())) {
            log.warn("사용자 상태 변경 검증 실패 - status 누락");
            throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
        }
        UserStatus status = parseStatus(request.getStatus());
        requireAllExist(ids);

        adminUserMapper.updateStatus(ids, status, LocalDateTime.now(), SYSTEM_USER_ID);
    }
```

`// ---- 헬퍼 ----` 블록에 추가:
```java
    /** 빈 목록/빈 id는 400, 중복은 한 번만 처리한다. */
    private static List<String> distinctIds(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(AdminUserService::isBlank)) {
            log.warn("사용자 요청 검증 실패 - id 목록이 비었거나 빈 id가 있음");
            throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
        }
        return ids.stream().distinct().toList();
    }

    private void requireAllExist(List<String> ids) {
        if (adminUserMapper.findExistingIds(ids).size() != ids.size()) {
            log.warn("사용자 요청 실패 - 존재하지 않는 사용자 id가 포함됨");
            throw new AdminUserNotFoundException(ERR_NOT_FOUND);
        }
    }
```

- [ ] **Step 8: 컨트롤러 확장** — `AdminUserController.java`

import 추가: `kkdugi.app.admin.user.models.AdminUserChangeStatusRequest`, `kkdugi.app.admin.user.models.AdminUserIds`. `save` 뒤에 추가:
```java
    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/reset-password")
    public void resetPassword(@RequestBody AdminUserIds request) {
        service.resetPassword(request);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/change-status")
    public void changeStatus(@RequestBody AdminUserChangeStatusRequest request) {
        service.changeStatus(request);
    }

    @RequireAuthority(value = Rbac.DELT, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/delete")
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }
```
`/reset-password`와 `/change-status`는 고정 경로라 `/{id}`(POST)보다 우선한다.

- [ ] **Step 9: 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminUserMapperTest,AdminUserServiceTest,AdminUserControllerTest`
Expected: 모두 PASS.

- [ ] **Step 10: 체크포인트**

이 Task 파일만 확인. 사용자가 커밋을 요청한 경우에만 `git commit -m "feat(user): add password reset, delete and bulk status change API"`.

### Task 5: 사용자별 권한 조회/저장(5.5/5.6) + RBAC 매트릭스

**Files:**
- Create: `models/UserAuthority.java`, `models/AdminUserAuthority.java`, `models/AdminUserAuthoritiesRequest.java`
- Modify: `mapper/AdminUserMapper.java`, `AdminUserMapper.xml`, `service/AdminUserService.java`, `AdminUserController.java`, `AdminUserMapperTest`, `AdminUserServiceTest`, `AdminUserControllerTest`

**Interfaces:**
- Consumes: `findExisting`, `isBlank`, `SYSTEM_USER_ID`, `ERR_MALFORMED_REQUEST`, 예외 3종. `kkdugi.core.enums.AuthorityType`(`getCode()`).
- Produces:
  - `AdminUserAuthority(String id, String role, String type, String name, String remarks, String use, LocalDate applyStartDate, LocalDate applyEndDate)` — 요청/응답 공용, `ignoreUnknown`. 요청에서는 `id`와 두 날짜만 읽는다.
  - `AdminUserAuthoritiesRequest(List<AdminUserAuthority> insert, List<AdminUserAuthority> update, List<AdminUserAuthority> delete)`
  - `UserAuthority extends BaseModel` — `userId, authorityId, applyStartDate, applyEndDate` + 조인 값 `role, type(AuthorityType), name, remarks, use`
  - `AdminUserMapper`: `List<UserAuthority> findAuthoritiesByUserId(String)`, `List<String> findExistingAuthorityIds(List<String>)`, `int upsertAuthority(UserAuthority)`, `int deleteAuthorities(String userId, List<String> authorityIds)`
  - `AdminUserService`: `List<AdminUserAuthority> authorities(String id)`, `List<AdminUserAuthority> saveAuthorities(String id, AdminUserAuthoritiesRequest)`; 상수 `ERR_AUTHORITY_NOT_FOUND = "user.err.authority_not_found"`

- [ ] **Step 1: 매퍼 테스트 추가** — `AdminUserMapperTest`

import 추가: `java.time.LocalDate`, `kkdugi.app.admin.user.models.UserAuthority`, `kkdugi.core.enums.AuthorityType`.

`wipe()`의 첫 줄 앞에 권한 픽스처 정리를 추가한다:
```java
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE auth_id LIKE 'A_TEST_USR_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_id LIKE 'A_TEST_USR_MAPPER_%'");
```
(Task 4의 `deleteQueries_...` 테스트가 만드는 `A_TEST_USR_MAPPER_1`도 이 정리에 걸린다. `finally` 삭제는 그대로 둬도 된다.)

테스트 추가:
```java
    private void insertAuthority(String id, String role, String use) {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, auth_dc, use_yn, reg_id) "
                + "VALUES (?, ?, 'ROLE', ?, 'desc', ?, 'SYSTEM')", id, role, "Name " + role, use);
    }

    @Test
    void authorityQueries_upsertListAndDeleteMappings() {
        insertAuthority("A_TEST_USR_MAPPER_1", "TEST_USR_MAPPER_A", "Y");
        insertAuthority("A_TEST_USR_MAPPER_2", "TEST_USR_MAPPER_B", "N");

        UserAuthority first = new UserAuthority(USER_1, "A_TEST_USR_MAPPER_1", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31));
        first.setCreatedAt(LocalDateTime.now());
        first.setCreatorId("SYSTEM");
        mapper.upsertAuthority(first);
        UserAuthority second = new UserAuthority(USER_1, "A_TEST_USR_MAPPER_2", LocalDate.of(2030, 2, 1), LocalDate.of(9999, 12, 31));
        second.setCreatedAt(LocalDateTime.now());
        second.setCreatorId("SYSTEM");
        mapper.upsertAuthority(second);
        // 같은 매핑을 다시 넣으면 적용기간만 바뀐다(upsert)
        UserAuthority renewed = new UserAuthority(USER_1, "A_TEST_USR_MAPPER_1", LocalDate.of(2031, 1, 1), LocalDate.of(2031, 6, 30));
        renewed.setCreatedAt(LocalDateTime.now());
        renewed.setCreatorId("SYSTEM");
        mapper.upsertAuthority(renewed);

        List<UserAuthority> rows = mapper.findAuthoritiesByUserId(USER_1);
        assertThat(rows).extracting(UserAuthority::getAuthorityId).containsExactly("A_TEST_USR_MAPPER_1", "A_TEST_USR_MAPPER_2");
        assertThat(rows.get(0).getRole()).isEqualTo("TEST_USR_MAPPER_A");
        assertThat(rows.get(0).getType()).isEqualTo(AuthorityType.ROLE);
        assertThat(rows.get(0).getName()).isEqualTo("Name TEST_USR_MAPPER_A");
        assertThat(rows.get(0).getRemarks()).isEqualTo("desc");
        assertThat(rows.get(0).getUse()).isEqualTo("Y");
        assertThat(rows.get(0).getApplyStartDate()).isEqualTo(LocalDate.of(2031, 1, 1));
        assertThat(rows.get(0).getApplyEndDate()).isEqualTo(LocalDate.of(2031, 6, 30));
        assertThat(rows.get(1).getUse()).isEqualTo("N");
        assertThat(mapper.findAuthoritiesByUserId(USER_2)).isEmpty();

        assertThat(mapper.findExistingAuthorityIds(List.of("A_TEST_USR_MAPPER_1", "A_TEST_USR_MAPPER_MISSING")))
                .containsExactly("A_TEST_USR_MAPPER_1");

        assertThat(mapper.deleteAuthorities(USER_1, List.of("A_TEST_USR_MAPPER_2"))).isEqualTo(1);
        assertThat(mapper.findAuthoritiesByUserId(USER_1)).extracting(UserAuthority::getAuthorityId)
                .containsExactly("A_TEST_USR_MAPPER_1");
    }
```

- [ ] **Step 2: 서비스 테스트 추가** — `AdminUserServiceTest`

import 추가:
```java
import java.time.LocalDate;

import kkdugi.app.admin.user.models.AdminUserAuthoritiesRequest;
import kkdugi.app.admin.user.models.AdminUserAuthority;
```

헬퍼 추가:
```java
    private void insertAuthority(String id, String role) {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                + "VALUES (?, ?, 'ROLE', ?, 'SYSTEM')", id, role, "Name " + role);
    }

    /** 요청 항목 — 이름/role 등은 응답 전용이라 비워 둔다. */
    private static AdminUserAuthority item(String authorityId, LocalDate start, LocalDate end) {
        return new AdminUserAuthority(authorityId, null, null, null, null, null, start, end);
    }

    private static void assertAuthorityMalformed(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_MALFORMED_REQUEST);
    }
```

테스트 추가:
```java
    @Test
    void saveAuthorities_insertsWithDefaultDates_updatesPeriods_deletesMappings_andReturnsTheList() {
        insertAuthority("A_TEST_USR_SVC_1", "TEST_USR_SVC_A");
        insertAuthority("A_TEST_USR_SVC_2", "TEST_USR_SVC_B");
        insertAuthority("A_TEST_USR_SVC_3", "TEST_USR_SVC_C");

        List<AdminUserAuthority> first = service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(
                List.of(item("A_TEST_USR_SVC_1", null, null),
                        item("A_TEST_USR_SVC_2", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31))),
                null, null));

        assertThat(first).extracting(AdminUserAuthority::getId).containsExactly("A_TEST_USR_SVC_1", "A_TEST_USR_SVC_2");
        assertThat(first.get(0).getApplyStartDate()).isEqualTo(LocalDate.now());
        assertThat(first.get(0).getApplyEndDate()).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(first.get(0).getRole()).isEqualTo("TEST_USR_SVC_A");
        assertThat(first.get(0).getType()).isEqualTo("ROLE");
        assertThat(first.get(0).getUse()).isEqualTo("Y");

        List<AdminUserAuthority> second = service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(
                List.of(item("A_TEST_USR_SVC_3", null, null)),
                List.of(item("A_TEST_USR_SVC_2", LocalDate.of(2031, 1, 1), LocalDate.of(2031, 3, 31))),
                List.of(item("A_TEST_USR_SVC_1", null, null), item("A_TEST_USR_SVC_MISSING_MAPPING", null, null))));

        assertThat(second).extracting(AdminUserAuthority::getId).containsExactly("A_TEST_USR_SVC_2", "A_TEST_USR_SVC_3");
        assertThat(second.get(0).getApplyStartDate()).isEqualTo(LocalDate.of(2031, 1, 1));
        assertThat(second.get(0).getApplyEndDate()).isEqualTo(LocalDate.of(2031, 3, 31));
        assertThat(service.authorities(USER_1)).hasSize(2);
        assertThat(service.authorities(USER_2)).isEmpty();
    }

    @Test
    void saveAuthorities_anEmptyRequestChangesNothing() {
        insertAuthority("A_TEST_USR_SVC_1", "TEST_USR_SVC_A");
        service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(List.of(item("A_TEST_USR_SVC_1", null, null)), null, null));

        assertThat(service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(null, null, null))).hasSize(1);
    }

    @Test
    void saveAuthorities_unknownAuthority_throwsAuthorityNotFound_andWritesNothing() {
        insertAuthority("A_TEST_USR_SVC_1", "TEST_USR_SVC_A");

        assertThatThrownBy(() -> service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(
                List.of(item("A_TEST_USR_SVC_1", null, null), item("A_TEST_USR_SVC_MISSING", null, null)), null, null)))
                .isInstanceOf(AdminUserValidationException.class)
                .hasMessage(AdminUserService.ERR_AUTHORITY_NOT_FOUND);

        assertThat(service.authorities(USER_1)).isEmpty();
    }

    @Test
    void saveAuthorities_rejectsMalformedItems() {
        insertAuthority("A_TEST_USR_SVC_1", "TEST_USR_SVC_A");
        AdminUserAuthority ok = item("A_TEST_USR_SVC_1", null, null);

        // 빈 id, null 항목, 같은 id가 두 번(같은 목록/다른 목록), 적용기간 역전
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1,
                new AdminUserAuthoritiesRequest(List.of(item(" ", null, null)), null, null)));
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1,
                new AdminUserAuthoritiesRequest(java.util.Arrays.asList((AdminUserAuthority) null), null, null)));
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1,
                new AdminUserAuthoritiesRequest(List.of(ok, ok), null, null)));
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1,
                new AdminUserAuthoritiesRequest(List.of(ok), null, List.of(ok))));
        assertAuthorityMalformed(() -> service.saveAuthorities(USER_1, new AdminUserAuthoritiesRequest(
                List.of(item("A_TEST_USR_SVC_1", LocalDate.of(2030, 2, 1), LocalDate.of(2030, 1, 1))), null, null)));

        assertThat(service.authorities(USER_1)).isEmpty();
    }

    @Test
    void authorities_andSaveAuthorities_throwNotFoundForAnUnknownUser() {
        assertThatThrownBy(() -> service.authorities("U_TEST_USR_SVC_MISSING"))
                .isInstanceOf(AdminUserNotFoundException.class)
                .hasMessage(AdminUserService.ERR_NOT_FOUND);
        assertThatThrownBy(() -> service.saveAuthorities("U_TEST_USR_SVC_MISSING", new AdminUserAuthoritiesRequest(null, null, null)))
                .isInstanceOf(AdminUserNotFoundException.class);
    }
```

- [ ] **Step 3: 컨트롤러 테스트 추가** — `AdminUserControllerTest`

import 추가:
```java
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jayway.jsonpath.JsonPath;

import kkdugi.core.Constants;
import kkdugi.core.enums.Rbac;
```

테스트 추가:
```java
    private void insertAuthority(String id, String role) {
        jdbcTemplate.update("INSERT INTO kkdugi_auth_base (auth_id, auth_role_cd, auth_tp_cd, auth_nm, reg_id) "
                + "VALUES (?, ?, 'ROLE', ?, 'SYSTEM')", id, role, "Name " + role);
    }

    @Test
    void authorities_saveThenGet_ignoresResponseOnlyFields_andFormatsDates() throws Exception {
        insertAuthority("A_TEST_USR_API_1", "TEST_USR_API_A");

        mockMvc.perform(post(URL + "/" + USER_1 + "/authorities").contentType(APPLICATION_JSON)
                        .content(json(map("insert", List.of(map("id", "A_TEST_USR_API_1", "name", "ignored", "role", "IGNORED",
                                "applyStartDate", "2030-01-01", "applyEndDate", "2030-12-31"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("A_TEST_USR_API_1"))
                .andExpect(jsonPath("$[0].role").value("TEST_USR_API_A"))
                .andExpect(jsonPath("$[0].type").value("ROLE"))
                .andExpect(jsonPath("$[0].name").value("Name TEST_USR_API_A"))
                .andExpect(jsonPath("$[0].use").value("Y"))
                .andExpect(jsonPath("$[0].applyStartDate").value("2030-01-01"))
                .andExpect(jsonPath("$[0].applyEndDate").value("2030-12-31"));

        mockMvc.perform(get(URL + "/" + USER_1 + "/authorities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 응답을 그대로 delete 항목으로 돌려보내도 동작한다
        mockMvc.perform(post(URL + "/" + USER_1 + "/authorities").contentType(APPLICATION_JSON)
                        .content(json(map("delete", List.of(map("id", "A_TEST_USR_API_1", "role", "TEST_USR_API_A"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void authorities_returns400ForUnknownAuthority_and404ForUnknownUser() throws Exception {
        mockMvc.perform(post(URL + "/" + USER_1 + "/authorities").contentType(APPLICATION_JSON)
                        .content(json(map("insert", List.of(map("id", "A_TEST_USR_API_MISSING"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.err.authority_not_found"));

        mockMvc.perform(get(URL + "/U_TEST_USR_API_MISSING/authorities"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user.err.not_found"));
    }

    @Test
    void endpoints_requireAuthentication_theSysAdminRole_andTheMatchingRbacBit() throws Exception {
        String missing = URL + "/U_TEST_USR_API_MISSING";
        String userBody = json(map("username", "test_usr_api_rbac", "name", "Rbac", "email", "test_usr_api_rbac@example.com"));
        String idsBody = json(map("id", List.of(USER_2)));

        // 미인증 → 401
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                .perform(post(URL).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        // SYS_ADMIN 역할이 없으면 모든 비트가 있어도 403 — 등록/초기화/삭제는 권한 상승 경로다
        MockMvc operator = TestAuthorization.mvc(webApplicationContext, "admin/user", 15, "OPERATOR");
        operator.perform(post(URL).contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        operator.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON).content(idsBody))
                .andExpect(status().isForbidden());

        // READ만 있으면 조회는 되지만 쓰기 계열은 전부 403
        MockMvc readOnly = TestAuthorization.mvc(webApplicationContext, "admin/user", Rbac.READ.getValue(), Constants.SYS_ADMIN);
        readOnly.perform(post(URL).contentType(APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        readOnly.perform(get(missing + "/authorities")).andExpect(status().isNotFound());
        readOnly.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(userBody)).andExpect(status().isForbidden());
        readOnly.perform(post(missing).contentType(APPLICATION_JSON).content(userBody)).andExpect(status().isForbidden());
        readOnly.perform(post(URL + "/reset-password").contentType(APPLICATION_JSON).content(idsBody)).andExpect(status().isForbidden());
        readOnly.perform(post(URL + "/change-status").contentType(APPLICATION_JSON).content("{\"id\":[\"x\"],\"status\":\"20\"}"))
                .andExpect(status().isForbidden());
        readOnly.perform(post(missing + "/authorities").contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        readOnly.perform(post(missing + "/delete")).andExpect(status().isForbidden());

        // WRTE가 있으면 등록은 되지만 삭제는 DELT가 따로 필요하다
        MockMvc writer = TestAuthorization.mvc(webApplicationContext, "admin/user",
                Rbac.READ.getValue() | Rbac.WRTE.getValue(), Constants.SYS_ADMIN);
        String response = writer.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(userBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.id");
        writer.perform(post(URL + "/" + id + "/delete")).andExpect(status().isForbidden());

        MockMvc deleter = TestAuthorization.mvc(webApplicationContext, "admin/user",
                Rbac.READ.getValue() | Rbac.DELT.getValue(), Constants.SYS_ADMIN);
        deleter.perform(post(URL + "/" + id + "/delete")).andExpect(status().isOk());
        deleter.perform(get(URL + "/" + id)).andExpect(status().isNotFound());
    }
```
- [ ] **Step 4: 실패 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminUserMapperTest,AdminUserServiceTest,AdminUserControllerTest`
Expected: 컴파일 오류(`AdminUserAuthority`, `UserAuthority`, 새 메서드, `ERR_AUTHORITY_NOT_FOUND` 없음)로 FAIL.

- [ ] **Step 5: 모델 3종**

`models/UserAuthority.java`:
```java
package kkdugi.app.admin.user.models;

import java.time.LocalDate;

import kkdugi.core.enums.AuthorityType;
import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code kkdugi_user_auth} 한 행 — 사용자↔권한 매핑과 적용 기간. {@code role}/{@code type}/{@code name}/
 * {@code remarks}/{@code use}는 조회 때 {@code kkdugi_auth_base}를 조인해 채우는 읽기 전용 값이고 쓰기(upsert)에는
 * 쓰이지 않는다.
 */
@Getter
@Setter
public class UserAuthority extends BaseModel {

    private String userId;
    private String authorityId;
    private LocalDate applyStartDate;
    private LocalDate applyEndDate;
    private String role;
    private AuthorityType type;
    private String name;
    private String remarks;
    private String use;

    public UserAuthority() {
    }

    public UserAuthority(String userId, String authorityId, LocalDate applyStartDate, LocalDate applyEndDate) {
        this.userId = userId;
        this.authorityId = authorityId;
        this.applyStartDate = applyStartDate;
        this.applyEndDate = applyEndDate;
    }
}
```

`models/AdminUserAuthority.java`:
```java
package kkdugi.app.admin.user.models;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 사용자에게 붙는 권한 한 건과 적용 기간 — 요청/응답 공용. 날짜는 {@code yyyy-MM-dd}.
 * 요청에서는 {@code id}와 {@code applyStartDate}/{@code applyEndDate}만 읽는다. 나머지
 * ({@code role}/{@code type}/{@code name}/{@code remarks}/{@code use})는 응답 전용이라 실려 와도 무시한다
 * — 응답 항목을 그대로 요청으로 돌려보내도 동작한다.
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserAuthority {

    private final String id;
    private final String role;
    private final String type;
    private final String name;
    private final String remarks;
    private final String use;
    private final LocalDate applyStartDate;
    private final LocalDate applyEndDate;
}
```

`models/AdminUserAuthoritiesRequest.java`:
```java
package kkdugi.app.admin.user.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 사용자별 권한 저장 요청. 각 목록은 생략(null)하면 빈 목록과 같다. {@code insert}/{@code update}는
 * 둘 다 upsert(없으면 추가, 있으면 적용기간 갱신)이고 {@code delete}는 매핑을 지운다(없는 매핑은 무시).
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserAuthoritiesRequest {

    private final List<AdminUserAuthority> insert;
    private final List<AdminUserAuthority> update;
    private final List<AdminUserAuthority> delete;
}
```

- [ ] **Step 6: 매퍼 확장**

`AdminUserMapper.java`에 import(`kkdugi.app.admin.user.models.UserAuthority`) 추가 후 메서드 추가:
```java
    List<UserAuthority> findAuthoritiesByUserId(@Param("userId") String userId);

    /** {@code authorityIds}는 비어 있으면 안 된다. */
    List<String> findExistingAuthorityIds(@Param("authorityIds") List<String> authorityIds);

    int upsertAuthority(UserAuthority row);

    /** {@code authorityIds}는 비어 있으면 안 된다. */
    int deleteAuthorities(@Param("userId") String userId, @Param("authorityIds") List<String> authorityIds);
```

`AdminUserMapper.xml`: `userBaseResultMap` 아래에 resultMap 추가, `</mapper>` 위에 쿼리 추가.
```xml
  <resultMap id="userAuthorityResultMap" type="kkdugi.app.admin.user.models.UserAuthority"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="userId" column="user_id"/>
    <id property="authorityId" column="auth_id"/>
    <result property="applyStartDate" column="apl_st_dtm"/>
    <result property="applyEndDate" column="apl_ed_dtm"/>
    <result property="role" column="auth_role_cd"/>
    <result property="type" column="auth_tp_cd"/>
    <result property="name" column="auth_nm"/>
    <result property="remarks" column="auth_dc"/>
    <result property="use" column="use_yn"/>
  </resultMap>
```
```xml
  <!--
    * QueryID=findAuthoritiesByUserId
    * Description=Every authority mapping of one user together with the authority's role/type/name/remarks/use
    -->
  <select id="findAuthoritiesByUserId" resultMap="userAuthorityResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.findAuthoritiesByUserId */
SELECT ua.user_id, ua.auth_id, ua.apl_st_dtm, ua.apl_ed_dtm, ua.reg_dtm, ua.reg_id, ua.upd_dtm, ua.upd_id,
       ab.auth_role_cd, ab.auth_tp_cd, ab.auth_nm, ab.auth_dc, ab.use_yn
FROM kkdugi_user_auth ua
JOIN kkdugi_auth_base ab ON ab.auth_id = ua.auth_id
WHERE ua.user_id = #{userId}
ORDER BY ua.auth_id
]]>
  </select>

  <!--
    * QueryID=findExistingAuthorityIds
    * Description=Return the subset of the given (non-empty) authority ids that exist
    -->
  <select id="findExistingAuthorityIds" resultType="string">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.findExistingAuthorityIds */
SELECT auth_id
FROM kkdugi_auth_base
WHERE auth_id IN
]]>
    <foreach item="authorityId" collection="authorityIds" open="(" separator="," close=")">
      #{authorityId}
    </foreach>
  </select>

  <!--
    * QueryID=upsertAuthority
    * Description=Insert a user-authority mapping, or update its apply period when it already exists
    -->
  <insert id="upsertAuthority" parameterType="kkdugi.app.admin.user.models.UserAuthority">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.upsertAuthority */
INSERT INTO kkdugi_user_auth
    (user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_dtm, reg_id)
VALUES
    (#{userId}, #{authorityId}, #{applyStartDate}, #{applyEndDate}, #{createdAt}, #{creatorId})
ON CONFLICT (user_id, auth_id) DO UPDATE
SET apl_st_dtm = EXCLUDED.apl_st_dtm,
    apl_ed_dtm = EXCLUDED.apl_ed_dtm,
    upd_dtm    = EXCLUDED.reg_dtm,
    upd_id     = EXCLUDED.reg_id
]]>
  </insert>

  <!--
    * QueryID=deleteAuthorities
    * Description=Delete the given (non-empty) authority mappings of one user
    -->
  <delete id="deleteAuthorities">
<![CDATA[
/* QueryID=kkdugi.app.admin.user.mapper.AdminUserMapper.deleteAuthorities */
DELETE FROM kkdugi_user_auth
WHERE user_id = #{userId}
  AND auth_id IN
]]>
    <foreach item="authorityId" collection="authorityIds" open="(" separator="," close=")">
      #{authorityId}
    </foreach>
  </delete>
```

- [ ] **Step 7: 서비스 확장** — `AdminUserService.java`

import 추가:
```java
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import kkdugi.app.admin.user.models.AdminUserAuthoritiesRequest;
import kkdugi.app.admin.user.models.AdminUserAuthority;
import kkdugi.app.admin.user.models.UserAuthority;
```

상수 추가: `public static final String ERR_AUTHORITY_NOT_FOUND = "user.err.authority_not_found";` 와 `private static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);`

`changeStatus` 뒤(`// ---- 검증 ----` 앞)에 추가:
```java
    // ---- 사용자별 권한 ---------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdminUserAuthority> authorities(String id) {
        findExisting(id);
        return adminUserMapper.findAuthoritiesByUserId(id).stream().map(AdminUserService::toAuthorityContent).toList();
    }

    /**
     * {@code delete}를 먼저 적용하고 {@code insert}/{@code update}를 upsert한다. 요청 항목에서는 id와 적용기간만
     * 읽는다(날짜 생략 시 시작=오늘, 종료=9999-12-31). 하나라도 잘못되면 아무것도 쓰지 않는다.
     */
    @Transactional
    public List<AdminUserAuthority> saveAuthorities(String id, AdminUserAuthoritiesRequest request) {
        findExisting(id);
        List<AdminUserAuthority> inserts = orEmpty(request.getInsert());
        List<AdminUserAuthority> updates = orEmpty(request.getUpdate());
        List<AdminUserAuthority> deletes = orEmpty(request.getDelete());
        List<AdminUserAuthority> writes = Stream.concat(inserts.stream(), updates.stream()).toList();
        validateAuthorityItems(writes, deletes);

        List<String> deleteIds = deletes.stream().map(AdminUserAuthority::getId).toList();
        if (!deleteIds.isEmpty()) {
            adminUserMapper.deleteAuthorities(id, deleteIds);
        }
        LocalDateTime now = LocalDateTime.now();
        for (AdminUserAuthority item : writes) {
            UserAuthority row = new UserAuthority(id, item.getId(), resolveStart(item), resolveEnd(item));
            row.setCreatedAt(now);
            row.setCreatorId(SYSTEM_USER_ID);
            adminUserMapper.upsertAuthority(row);
        }
        return authorities(id);
    }
```

`// ---- 검증 ----` 블록에 추가:
```java
    private void validateAuthorityItems(List<AdminUserAuthority> writes, List<AdminUserAuthority> deletes) {
        Set<String> seen = new HashSet<>();
        for (AdminUserAuthority item : Stream.concat(writes.stream(), deletes.stream()).toList()) {
            if (item == null || isBlank(item.getId()) || !seen.add(item.getId())) {
                log.warn("사용자 권한 저장 검증 실패 - 항목 오류(null/빈 id/중복 id): {}", item == null ? null : item.getId());
                throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
            }
        }
        for (AdminUserAuthority item : writes) {
            if (resolveEnd(item).isBefore(resolveStart(item))) {
                log.warn("사용자 권한 저장 검증 실패 - 적용기간 역전: {}", item.getId());
                throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
            }
        }
        List<String> writeIds = writes.stream().map(AdminUserAuthority::getId).toList();
        if (!writeIds.isEmpty() && adminUserMapper.findExistingAuthorityIds(writeIds).size() != writeIds.size()) {
            log.warn("사용자 권한 저장 검증 실패 - 존재하지 않는 권한 id가 포함됨");
            throw new AdminUserValidationException(ERR_AUTHORITY_NOT_FOUND);
        }
    }
```

`// ---- 헬퍼 ----` 블록에 추가:
```java
    private static List<AdminUserAuthority> orEmpty(List<AdminUserAuthority> items) {
        return items == null ? List.of() : items;
    }

    private static LocalDate resolveStart(AdminUserAuthority item) {
        return item.getApplyStartDate() != null ? item.getApplyStartDate() : LocalDate.now();
    }

    private static LocalDate resolveEnd(AdminUserAuthority item) {
        return item.getApplyEndDate() != null ? item.getApplyEndDate() : OPEN_ENDED;
    }

    private static AdminUserAuthority toAuthorityContent(UserAuthority row) {
        return new AdminUserAuthority(row.getAuthorityId(), row.getRole(), row.getType().getCode(), row.getName(),
                row.getRemarks(), row.getUse(), row.getApplyStartDate(), row.getApplyEndDate());
    }
```

- [ ] **Step 8: 컨트롤러 확장** — `AdminUserController.java`

import 추가: `java.util.List`, `kkdugi.app.admin.user.models.AdminUserAuthoritiesRequest`, `kkdugi.app.admin.user.models.AdminUserAuthority`. `delete` 뒤에 추가:
```java
    @RequireAuthority(value = Rbac.READ, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @GetMapping("/{id}/authorities")
    public List<AdminUserAuthority> authorities(@PathVariable("id") String id) {
        return service.authorities(id);
    }

    @RequireAuthority(value = Rbac.WRTE, program = PROGRAM)
    @HasRole(Constants.SYS_ADMIN)
    @PostMapping("/{id}/authorities")
    public List<AdminUserAuthority> saveAuthorities(@PathVariable("id") String id,
            @RequestBody AdminUserAuthoritiesRequest request) {
        return service.saveAuthorities(id, request);
    }
```

- [ ] **Step 9: 통과 확인**

Run: `./mvnw.cmd -B -ntp test -Dtest=AdminUserMapperTest,AdminUserServiceTest,AdminUserControllerTest`
Expected: 모두 PASS. `Stream.concat(...).toList()` 안의 `item == null` 검사가 NPE 없이 동작하는지(null 항목 테스트) 확인한다.

- [ ] **Step 10: 체크포인트**

이 Task 파일만 확인. 사용자가 커밋을 요청한 경우에만 `git commit -m "feat(user): add per-user authority list and save API"`.

### Task 6: 문서 + 전체 테스트

**Files:**
- Create: `docs/api/user.md`
- Modify: `docs/api/README.md`, `docs/api/authority.md`(알려진 한계 한 줄), `docs/user-system-design.md`(상태), `CLAUDE.md`

- [ ] **Step 1: `docs/api/user.md` 작성**

아래 내용으로 새로 만든다(코드가 최종 근거이므로, 구현 중 동작이 달라진 부분이 있으면 코드에 맞춰 고친다).

````markdown
# 사용자 관리 API

설계 배경과 결정은 [사용자 관리 설계](../user-system-design.md)를 본다. 모든 엔드포인트는 `/api/v1.0/admin/user` 아래에 있고
`SYS_ADMIN` 역할과 `admin/user` 메뉴의 RBAC를 요구한다(조회 READ, 등록/저장/초기화/상태 변경/권한 저장 WRTE, 삭제 DELT).
`X-Menu-Id` 헤더 규약은 [request-context.md](request-context.md)를 따른다.

## 공통

- **사용자 객체**: `{ id, username, name, remarks, image, email, status, lastLoginAt, lastChangePasswordAt, passwordStatus }`.
  `status`는 `UserStatus` 코드(`10` 대기, `20` 정상, `30` 휴면, `40` 탈퇴, `50` 정지), `passwordStatus`는 `PasswordStatus`
  코드(`10` 신규·변경 필요, `20` 만료, `30` 정상, 값이 없으면 `null`). 일시는 `yyyy-MM-dd HH:mm:ss`, 없으면 `null`.
  비밀번호·CI/DI·설정 데이터는 어떤 응답에도 없다.
- **권한 항목**: `{ id, role, type, name, remarks, use, applyStartDate, applyEndDate }`, 날짜는 `yyyy-MM-dd`.
  요청에서는 `id`와 두 날짜만 읽고 나머지는 응답 전용이라 무시한다.
- **오류 응답**: `{ "code": "...", "message": "..." }`.

| 상태 | 코드 | 경우 |
|---|---|---|
| 400 | `user.err.malformed_request` | 필수값(username/name/email) 누락, 길이 초과(username 100, name 200, email 200, remarks 1000, image 500), 알 수 없는 status, 빈 id 목록·빈 id, 권한 항목 오류(null/빈 id/중복 id/적용기간 역전) |
| 400 | `user.err.authority_not_found` | 5.6에서 존재하지 않는 권한 id |
| 404 | `user.err.not_found` | 없는 사용자 |
| 409 | `user.err.duplicate_username` / `user.err.duplicate_email` | 로그인 ID / 이메일 충돌 |
| 409 | `user.err.immutable` | 저장에서 `username` 변경 시도 |
| 409 | `user.err.self_delete` | 자기 자신 삭제 시도 |
| 401 / 403 | | 미인증 / 역할·RBAC 부족 |

## 1. 목록 — `POST /api/v1.0/admin/user`
```javascript
Request  // 모두 선택. username/name은 대소문자 무시 부분 일치, status는 코드 일치
{ "username": "adm", "name": "kim", "status": "20", "page": 1, "pageSize": 200 }
Response // kkdugi.core.models.Page — 최신 등록순
{ "page": 1, "pageSize": 200, "totalItems": 1, "totalPages": 1, "contents": [ { /* 사용자 객체 */ } ] }
```

## 2. 상세 — `GET /api/v1.0/admin/user/{id}`
사용자 객체. 없으면 404.

## 3. 등록 — `POST /api/v1.0/admin/user/regist`
```javascript
Request  // id는 보내지 않는다(서버 채번, 접두사 U). status 생략 시 "20"
{ "username": "...", "name": "...", "email": "...", "remarks": "...", "image": "...", "status": "20" }
Response // 사용자 객체 (passwordStatus = "10")
```
서버가 임시 비밀번호를 생성해 인코딩 저장한다. **메일 발송은 아직 없다 — `TODO(mail)`. 임시로 평문이 애플리케이션 로그(WARN,
`TODO(mail) 임시 비밀번호 발급 ...`)에 남는다.** 메일 발송이 구현되면 이 로그를 제거한다. 응답에는 비밀번호가 없다.

## 4. 저장 — `POST /api/v1.0/admin/user/{id}`
요청은 등록과 같다. 본문 `id`는 무시하고 경로 id를 쓴다. `username`은 생략하거나 기존 값과 같아야 한다(다르면 409). `name`/`remarks`/`image`/`email`은
요청 값으로 교체되고(`remarks`/`image` 생략은 비움), `status` 생략 시 기존 값을 유지한다. 비밀번호는 건드리지 않는다.

## 5. 사용자별 권한 조회 — `GET /api/v1.0/admin/user/{id}/authorities`
권한 항목 배열(권한 id 순). 사용자가 없으면 404.

## 6. 사용자별 권한 저장 — `POST /api/v1.0/admin/user/{id}/authorities`
```javascript
Request  // 각 목록은 생략 가능. insert/update는 둘 다 upsert(없으면 추가, 있으면 적용기간 갱신), delete는 매핑 삭제(없는 매핑은 무시)
{ "insert": [ { "id": "A...", "applyStartDate": "2026-10-01", "applyEndDate": "2026-12-31" } ],
  "update": [ ... ], "delete": [ { "id": "A..." } ] }
Response // 저장 후의 권한 항목 배열
```
날짜를 생략하면 시작=오늘, 종료=`9999-12-31`. 같은 id가 요청 안에 두 번 나오면 400. 하나라도 잘못되면 아무것도 저장하지 않는다.

## 7. 비밀번호 초기화 — `POST /api/v1.0/admin/user/reset-password`
`{ "id": ["U...", ...] }` → 200(본문 없음). 대상마다 새 임시 비밀번호를 생성해 저장하고 `passwordStatus = "10"`, `lastChangePasswordAt`을 갱신한다
(로그 방식은 3번과 같다). 없는 id가 하나라도 있으면 404이고 아무것도 바뀌지 않는다.

## 8. 삭제 — `POST /api/v1.0/admin/user/{id}/delete`
물리 삭제(사용자의 세션과 권한 매핑도 함께 삭제, 권한 자체는 유지). 이미 없는 사용자는 200. 자기 자신은 409.

## 9. 상태 일괄 변경 — `POST /api/v1.0/admin/user/change-status`
`{ "id": ["U...", ...], "status": "50" }` → 200. 전부 성공하거나 전부 실패한다. 없는 id가 있으면 404, 빈 목록·알 수 없는 status는 400. 상태 전이 제약은 없다.

## 알려진 한계
- **마지막 `SYS_ADMIN` 사용자 보호가 없다(오너 결정).** 삭제(8), 상태 변경(9), 권한 저장(6), 그리고 [권한 API](authority.md)의 `users`로
  마지막 시스템 관리자를 없앨 수 있고, 그러면 DB를 직접 고치는 것 외에 복구 방법이 없다.
- **사용자 상태는 아직 로그인에 반영되지 않는다.** 정지·탈퇴·휴면 사용자도 로그인된다. 9번은 값을 저장할 뿐 강제하지 않는다.
- 임시 비밀번호 평문 로그는 메일 발송이 구현될 때까지의 임시 조치다.
- 사용자 본인의 비밀번호 변경, 로그인 실패 잠금, 비밀번호 정책은 범위 밖이다.
````

- [ ] **Step 2: `docs/api/README.md` 수정**

(a) 머리말 문단(7~9행)의 아래 세 줄을 교체한다.
```
현재 API의 유일한 살아있는 근거다. 아직 구현되지 않은 사용자 스펙은
위 아카이브 문서에만 남아 있고, 착수 시점에 오너에게 재확인이 필요하다
(아카이브 문서 상단 참고).
```
→
```
현재 API의 유일한 살아있는 근거다. 아카이브 스펙의 절(공통코드/메시지/메뉴/권한/사용자)은
모두 이 폴더에 구현·문서화되어 있고, 아카이브 문서는 원문 기록으로만 남는다.
```
(b) 목차 표의 `authority.md` 행 바로 아래에 행을 추가한다.
```
| [user.md](user.md) | 사용자 관리 — 목록/상세/등록/저장, 사용자별 권한, 비밀번호 초기화, 삭제, 상태 일괄 변경 |
```
(c) "공통 사항 > Base URL"의 `(공통코드/메시지/메뉴/권한 CRUD)`를 `(공통코드/메시지/메뉴/권한/사용자 CRUD)`로 고친다.

- [ ] **Step 3: `docs/api/authority.md`의 "알려진 한계" 한 줄 교체**

`- 사용자 관리(원본 스펙 5.5/5.6)가 구현되면 ...` 줄을 아래로 교체한다. (이 파일에는 사용자의 미커밋 변경이 이미 있다 — 그 줄만 고치고 나머지는 건드리지 않는다.)
```
- `kkdugi_user_auth`를 쓰는 경로가 두 곳이다 — 권한 저장(이 문서)과 사용자별 권한 저장([user.md](user.md) 6번). `app.admin.<기능>`끼리 import하지 않는 규칙(ADR-0016) 때문에 SQL은 각자 갖고, 두 경로가 같은 검증 규칙(적용기간 기본값·역전, 대상 존재)을 쓰도록 유지한다.
```

- [ ] **Step 4: `docs/user-system-design.md` 상태 갱신**

5행 `- 상태: 설계 확정 (2026-09-19), 구현 전 — ...`을 아래로 교체한다.
```
- 상태: 구현 완료 (2026-09-19) — API 문서는 [api/user.md](api/user.md)
```
그리고 "문서 작업" 절은 완료 표시로 바꾸지 말고 그대로 둔다(이력).

- [ ] **Step 5: `CLAUDE.md` 갱신**

`CLAUDE.md`를 읽고 아래 세 곳을 고친다(문구가 줄바꿈으로 나뉘어 있으니 의미 단위로 찾는다).
1. "Project overview" 문단의 "Authority/users are still TODO (see Scope notes) — ... confirm with the owner before implementing against it" 부분을, 권한과 사용자가 구현됐고 현재 계약은 [docs/api/authority.md](docs/api/authority.md), [docs/api/user.md](docs/api/user.md)라는 문장으로 바꾼다. 아카이브 문서가 더 이상 구현 대상이 아니라는 설명은 유지한다.
2. "Package structure"의 `app > admin` 항목에 `user` 줄을 추가한다: `user — models(UserBase, AdminUser, AdminUserParams, AdminUserPersistRequest, AdminUserIds, AdminUserChangeStatusRequest, UserAuthority, AdminUserAuthority, AdminUserAuthoritiesRequest), exceptions(AdminUser*), mapper(AdminUserMapper), service(AdminUserService, TemporaryPasswordGenerator — SerialUtils.next(config), 접두사 U)`. `api > admin` 목록에 `AdminUserController (/api/v1.0/admin/user)`를 추가한다.
3. "Scope notes"의 마지막에서 두 번째 불릿("Authority and users (sections 4, 5 ...) are still not started ...")을, 사용자 관리가 2026-09-19에 구현됐고(임시 비밀번호는 `TODO(mail)`로 로그에 평문이 남으며, 마지막 `SYS_ADMIN` 보호는 오너 결정으로 하지 않는다) 상세는 [docs/user-system-design.md](docs/user-system-design.md)와 [docs/api/user.md](docs/api/user.md)에 있다는 내용으로 바꾼다. 세션 시스템이 별도 다음 단계라는 불릿은 유지한다.

- [ ] **Step 6: 전체 테스트 실행**

Run (`kkdugi-admin/`): `./mvnw.cmd -B -ntp test`
Expected: BUILD SUCCESS. 이 작업과 무관한 기존 실패가 나오면 고치지 말고 실패한 테스트명과 출력을 사용자에게 그대로 보고한다. 이 작업이 만든 테스트가 실패하면 원인을 고친다.

- [ ] **Step 7: 완료 검증**

`superpowers:verification-before-completion`을 따른다: 위 `mvn test` 출력에서 `Tests run:`/`BUILD SUCCESS`를 확인한 뒤에만 완료를 보고한다. `git status --short`로 이 작업이 만든 파일과 무관한 미커밋 변경이 섞이지 않았는지 확인해 보고에 포함한다.

- [ ] **Step 8: 체크포인트**

사용자가 커밋을 요청한 경우에만, 무관한 미커밋 변경을 섞지 않도록 파일을 명시해 stage하고(`docs/api/authority.md`는 사용자에게 먼저 확인) `git commit -m "docs(user): document the user management API"`.

---

## Self-Review (계획 작성자 점검 기록)

- **스펙 커버리지**: 5.1/5.2 → Task 2, 5.3/5.4 → Task 3, 5.7/5.8/5.9 → Task 4, 5.5/5.6 → Task 5, 문서·`admin/user` 메뉴 기존 시드 확인 → Task 6/파일 구조. 임시 비밀번호(생성·인코딩·`pwd_stat_cd=10`·`TODO(mail)` 로그) → Task 1/3/4, 물리 삭제·하위 행 정리·멱등·자기 삭제 409 → Task 4, `username` 불변·email 중복 409 → Task 3, 응답 비밀번호 미노출 → Task 2/3 컨트롤러 테스트, RBAC(401/403 매트릭스) → Task 5. 마지막 `SYS_ADMIN` 보호는 스펙상 "하지 않음"이라 구현·테스트 대상이 아니고 문서 한계로만 남긴다(Task 6).
- **타입 일관성**: `logTemporaryPassword(String userId, String temporaryPassword)`(Task 3)를 Task 4 `resetPassword`가 같은 시그니처로 쓰고 테스트가 `getArgumentArray()[0]/[1]`로 읽는다. `AdminUserIds.getId()`/`AdminUserChangeStatusRequest.getId()/getStatus()`, 매퍼 `updatePassword(id, password, passwordStatus, changedAt, updaterId)` 순서가 Task 4 인터페이스·XML·서비스 호출에서 일치한다. `AdminUserAuthority`는 8개 인자 생성자(`id, role, type, name, remarks, use, applyStartDate, applyEndDate`)로 Task 5 테스트/서비스/문서가 같다.
- **알려진 실행 위험**: (1) Task 3 컨트롤러 테스트의 Hamcrest `matchesPattern` 사용은 의존성이 없으면 대체 방법을 Step 8에 적어 두었다. (2) `AdminUserServiceTest`의 로그 캡처는 Logback 클래스(`ch.qos.logback.*`)에 의존하며 Boot 기본 로깅에서 사용 가능하다. (3) `Page`/`BaseModel`의 `totalSize`는 `Long` 타입이라 매퍼 테스트는 `3L`과 비교한다.
