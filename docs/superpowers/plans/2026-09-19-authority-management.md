# 권한 관리(Authority Management) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 아카이브 스펙 4절(권한 관리) 전체 — 권한 CRUD, 권한↔메뉴 RBAC 매핑, 권한↔사용자 매핑, 후보 사용자 조회 — 를 REST API(`/api/v1.0/admin/authority`)까지 구현한다.

**Architecture:** `kkdugi.app.admin.authority` 한 기능 패키지(models/exceptions/mapper/service)에 `AdminAuthorityMapper`(읽기+쓰기)와 `AdminAuthorityService`를 두고, `kkdugi.api.admin.AdminAuthorityController`가 평평하게 노출한다. 테이블(`kkdugi_auth_base`/`kkdugi_user_auth`/`kkdugi_auth_menu`)은 이미 있으므로 마이그레이션은 `(auth_tp_cd, auth_role_cd)` 유니크 제약 하나(V11)만 추가한다. 저장은 전체 교체(요청에 포함된 매핑 목록으로 교체)이며 한 트랜잭션이다.

**Tech Stack:** Java 17, Spring Boot 4.0.8, MyBatis(XML mapper), Flyway, PostgreSQL 17, Lombok, JUnit 5 + AssertJ + MockMvc(실제 Postgres에 붙는 `@SpringBootTest`), Jackson 3(`tools.jackson.databind.ObjectMapper`).

**Spec:** [docs/authority-system-design.md](../../authority-system-design.md) — 실행자는 이 문서와 [docs/conventions/common-base-model.md](../../conventions/common-base-model.md)를 함께 읽는다.

## Global Constraints

스펙/규약에서 그대로 가져온 프로젝트 전역 요구사항. 모든 Task의 요구사항에 암묵적으로 포함된다.

- **경로/패키지**: 코드는 `kkdugi-admin/src/main/java/kkdugi/...`, 리소스는 `kkdugi-admin/src/main/resources/...`, 테스트는 `kkdugi-admin/src/test/java/kkdugi/...`. Maven 명령은 `kkdugi-admin/`에서 `./mvnw.cmd -B -ntp test`로 실행한다(Postgres 필요 — 저장소 루트에서 `docker-compose up -d`).
- **RBAC 맵 키는 `Rbac` 숫자 코드** `"10"`(조회)/`"20"`(등록)/`"30"`(삭제)/`"40"`(실행). `READ`/`WRITE` 같은 이름 키는 쓰지 않는다.
- **`users`는 어디서나 객체** `{id, applyStartDate, applyEndDate}`, 날짜 형식 `yyyy-MM-dd`. 요청에서 날짜를 생략하면 시작=오늘, 종료=`9999-12-31`.
- **record 금지.** DB 행 모델은 `kkdugi.core.models.BaseModel` 상속(setter 기반), 목록 검색 파라미터는 `kkdugi.core.models.BaseParams` 상속, 그 외 커맨드/결과는 `final` 필드 + 전체 필드 생성자 플레인 클래스(Lombok `@Getter`/`@AllArgsConstructor`). 예외는 `{package}.exceptions`.
- **필드명은 DB 컬럼명을 드러내지 않는다**(`Cd`/`Dtm`/`Val`/`Nm` 축약 금지): `role`/`type`/`name`/`remarks`/`use`/`applyStartDate`/`applyEndDate`/`rbac`.
- **단건(0~1개) 조회 mapper 메서드는 `Optional<T>`**를 반환한다. 목록 응답은 `core.models.Page<T>`, 서비스가 쿼리 전에 `params.setPage(params.resolvedPage()); params.setPageSize(params.resolvedPageSize());`로 정규화한 뒤 `Page.of(contents, params)`. 목록 쿼리는 `COUNT(*) OVER() AS total_size`를 반환하고 `BaseModel.totalSize`에 매핑한다(별도 count 쿼리 없음). `Page<T>`의 `T`는 `BaseModel`을 상속하고 `@JsonIgnoreProperties({"rownum","createdAt","creatorId","updatedAt","updaterId"})`를 붙인다.
- **채번**: `SerialConfig`(`getId()`=`"KKDUGI_AUTH"`, `getValueFormatter()`=`"A%s%04d"`) + `SerialUtils.next(config)`. Java에서 카운터를 읽고 쓰지 않는다.
- **코드성 enum**(`AuthorityType`, `Rbac`, `UserStatus`)은 이미 `kkdugi.core.enums`에 있다. MyBatis 컬럼에 `typeHandler=`를 붙이지 않는다(`default-enum-type-handler`가 처리).
- **매퍼 XML 서식**: XML 선언 `<?xml version="1.0" encoding="UTF-8"?>`; `<mapper>` 아래 2-space 들여쓰기; 모든 SQL 본문은 `<![CDATA[ ... ]]>`(CDATA와 안의 SQL은 들여쓰기 없이 컬럼 0); 동적 태그(`<where>`/`<if>`/`<foreach>`/`<include>`)는 CDATA 밖; 각 statement 위에 `<!-- * QueryID=... * Description=... -->` 주석, 첫 CDATA 맨 위에 `/* QueryID=<namespace>.<statementId> */`; **주석 안에 `#{...}` 금지**(바인드 파라미터로 잡힌다); `resultMap`은 setter 기반 `<id>`/`<result>`, 감사 컬럼이 있으면 `extends="kkdugi.core.models.CommonMapper.baseResultMap"`; 위치는 `mapper/postgres/app/admin/authority/AdminAuthorityMapper.xml`.
- **Jackson 3**: `ObjectMapper`는 반드시 `tools.jackson.databind.ObjectMapper`. 어노테이션은 `com.fasterxml.jackson.annotation.*` 그대로.
- **감사 필드**는 `AdminMenuService`와 같은 `"SYSTEM"` 상수로 채운다(`creatorId`/`updaterId`).
- **커밋**: 저장소에 이 작업과 무관한 미커밋 변경 파일이 있다. 각 Task의 커밋은 그 Task가 만든/고친 파일만 경로로 `git add` 한다(`git add -A`/`.` 금지). 사용자가 커밋을 승인하지 않았다면 커밋 Step에서 먼저 확인한다. 커밋 메시지는 `feat(authority): ...` 형식이고 세션의 attribution 트레일러로 끝낸다.
- **범위 밖**: 접근 제어(401/403), 사용자 관리(5절), 세션 즉시 반영(권한 변경은 다음 로그인부터 적용), 프런트엔드.

## File Structure

**Create (main)**

| 파일 | 책임 |
|---|---|
| `db/migration/V11__add_auth_role_unique.sql` | `(auth_tp_cd, auth_role_cd)` 유니크 제약 |
| `app/admin/authority/models/AuthorityBase.java` | `kkdugi_auth_base` 행 |
| `app/admin/authority/models/AuthorityUser.java` | `kkdugi_user_auth` 행 |
| `app/admin/authority/models/AuthorityMenu.java` | `kkdugi_auth_menu` 행 |
| `app/admin/authority/models/AuthorityMenuRow.java` | 메뉴 + 이 권한의 RBAC 값(조인 결과) |
| `app/admin/authority/models/AuthorityMenuLang.java` | 메뉴 언어 행(읽기 전용) |
| `app/admin/authority/models/CandidateUser.java` | 후보 사용자 조회 행 |
| `app/admin/authority/mapper/AdminAuthorityMapper.java` (+ XML) | 읽기+쓰기 쿼리 전부 |
| `app/admin/authority/models/AdminAuthority.java` | 목록/상세 응답 콘텐츠(`Page<T>`의 `T`) |
| `app/admin/authority/models/AdminAuthorityParams.java` | 목록 검색 파라미터 |
| `app/admin/authority/models/AdminAuthorityUser.java` | `users` 항목 `{id, applyStartDate, applyEndDate}` |
| `app/admin/authority/models/AdminAuthorityMenu.java` | 요청 `menus` 항목 `{id, authorities}` |
| `app/admin/authority/models/AdminAuthorityPersistRequest.java` | regist/save 요청 본문 |
| `app/admin/authority/models/AdminAuthorityCandidate.java` | 후보 사용자 응답 항목 |
| `app/admin/authority/models/AdminAuthorityMenuLocale.java` | 메뉴 노드의 언어별 라벨 |
| `app/admin/authority/models/AdminAuthorityMenuNode.java` | 메뉴 트리 노드(`Tree` 구현) |
| `app/admin/authority/models/AdminAuthorityCandidateQuery.java` | 후보 조회 요청 본문 `{query?}` |
| `app/admin/authority/exceptions/AdminAuthority{Validation,Conflict,NotFound}Exception.java` | 400/409/404 |
| `app/admin/authority/service/AdminAuthorityService.java` | 비즈니스 로직 전부 |
| `api/admin/AdminAuthorityController.java` | REST 엔드포인트 |

**Modify (main)**: `messages/messages.properties`, `messages/messages_en_US.properties`, `messages/messages_ko_KR.properties` (에러 메시지 6개 추가).

**Create (test)**: `app/admin/authority/mapper/AdminAuthorityMapperTest.java`, `app/admin/authority/service/AdminAuthorityServiceTest.java`, `api/admin/AdminAuthorityControllerTest.java`.

**Create/Modify (docs)**: `docs/api/authority.md`, `docs/api/README.md`, `docs/adr/0017-authority-management-system.md`, `docs/adr/README.md`, `docs/authority-system-design.md`, `CLAUDE.md`.

(`main`/`test` 경로의 공통 접두사는 `kkdugi-admin/src/{main,test}/java/kkdugi/`.)

---

### Task 1: 영속 계층 — V11 유니크 제약, 행 모델, `AdminAuthorityMapper`

**Files:**
- Create: `kkdugi-admin/src/main/resources/db/migration/V11__add_auth_role_unique.sql`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/models/{AuthorityBase,AuthorityUser,AuthorityMenu,AuthorityMenuRow,AuthorityMenuLang,CandidateUser}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/mapper/AdminAuthorityMapper.java`
- Create: `kkdugi-admin/src/main/resources/mapper/postgres/app/admin/authority/AdminAuthorityMapper.xml`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/admin/authority/mapper/AdminAuthorityMapperTest.java`

**Interfaces:**
- Consumes: `kkdugi.core.models.BaseModel`, `kkdugi.core.enums.{AuthorityType,UserStatus}`, `kkdugi.core.models.CommonMapper.baseResultMap`.
- Produces (Task 2가 그대로 쓴다):
  - `AuthorityBase(String id, String role, AuthorityType type, String name, String remarks, String use)` + 기본 생성자, getter/setter, `BaseModel` 감사 setter
  - `AuthorityUser(String userId, String authorityId, LocalDate applyStartDate, LocalDate applyEndDate)`
  - `AuthorityMenu(String authorityId, String menuId, Integer rbac)`
  - `AuthorityMenuRow`(기본 생성자 + setter): `id, parentId, icon, program, level(Integer), path, sort(Integer), use, rbac(Integer)`
  - `AuthorityMenuLang`(기본 생성자 + setter): `menuId, langCode, label, remarks`
  - `CandidateUser`(기본 생성자 + setter): `id, username, name, image`
  - `AdminAuthorityMapper` 메서드 전부(아래 Step 6 인터페이스 그대로)

- [ ] **Step 0: 사전 조건 확인** — Postgres가 떠 있어야 한다.

Run (저장소 루트): `docker-compose up -d`
Expected: `kkdugi-postgres`(또는 compose에 정의된 이름) 컨테이너가 `Running`/`Started`.

- [ ] **Step 1: 실패하는 mapper 테스트 작성**

Create `kkdugi-admin/src/test/java/kkdugi/app/admin/authority/mapper/AdminAuthorityMapperTest.java`:

```java
package kkdugi.app.admin.authority.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.authority.models.AuthorityBase;
import kkdugi.app.admin.authority.models.AuthorityMenu;
import kkdugi.app.admin.authority.models.AuthorityMenuLang;
import kkdugi.app.admin.authority.models.AuthorityMenuRow;
import kkdugi.app.admin.authority.models.AuthorityUser;
import kkdugi.app.admin.authority.models.CandidateUser;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.enums.UserStatus;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminAuthorityMapperTest {

    private static final String ROLE_PREFIX = "TEST_AUTHZ_MAPPER_";
    private static final String AUTH_1 = "A_TEST_AUTHZ_MAPPER_1";
    private static final String AUTH_2 = "A_TEST_AUTHZ_MAPPER_2";
    private static final String AUTH_3 = "A_TEST_AUTHZ_MAPPER_3";
    private static final String USER_1 = "U_TEST_AUTHZ_MAPPER_1";
    private static final String USER_2 = "U_TEST_AUTHZ_MAPPER_2";
    private static final String USER_3 = "U_TEST_AUTHZ_MAPPER_3";
    private static final String MENU_1 = "M_TEST_AUTHZ_MAPPER_1";
    private static final String MENU_2 = "M_TEST_AUTHZ_MAPPER_2";

    @Autowired
    private AdminAuthorityMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        wipe();
        insertUser(USER_1, "test_authz_mapper_one", "Mapper One", "20");
        insertUser(USER_2, "test_authz_mapper_two", "Mapper Two", "20");
        insertUser(USER_3, "test_authz_mapper_three", "Mapper Three", "10");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "authz_pgm_1", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_parent_id, menu_pgm, sort_seq, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                MENU_2, MENU_1, "authz_pgm_2", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "ko_KR", "테스트 메뉴", "SYSTEM");
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        jdbcTemplate.update("DELETE FROM kkdugi_auth_menu WHERE auth_id LIKE 'A_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE auth_id LIKE 'A_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_id LIKE 'A_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id LIKE 'M_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id LIKE 'M_TEST_AUTHZ_MAPPER_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id LIKE 'U_TEST_AUTHZ_MAPPER_%'");
    }

    private void insertUser(String id, String loginId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, "SYSTEM");
    }

    private void insertAuthority(String id, String role, AuthorityType type) {
        AuthorityBase row = new AuthorityBase(id, role, type, "Name " + role, "remarks", "Y");
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatorId("SYSTEM");
        mapper.insert(row);
    }

    private AuthorityUser userRow(String userId, String authId, LocalDate start, LocalDate end) {
        AuthorityUser row = new AuthorityUser(userId, authId, start, end);
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatorId("SYSTEM");
        return row;
    }

    private AuthorityMenu menuRow(String authId, String menuId, int rbac) {
        AuthorityMenu row = new AuthorityMenu(authId, menuId, rbac);
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatorId("SYSTEM");
        return row;
    }

    @Test
    void insertAndFindById_roundTripsEnumAndFields() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "A", AuthorityType.PLAN);

        AuthorityBase found = mapper.findById(AUTH_1).orElseThrow();

        assertThat(found.getRole()).isEqualTo(ROLE_PREFIX + "A");
        assertThat(found.getType()).isEqualTo(AuthorityType.PLAN);
        assertThat(found.getName()).isEqualTo("Name " + ROLE_PREFIX + "A");
        assertThat(found.getRemarks()).isEqualTo("remarks");
        assertThat(found.getUse()).isEqualTo("Y");
        assertThat(found.getCreatorId()).isEqualTo("SYSTEM");
        assertThat(mapper.findById("A_TEST_AUTHZ_MAPPER_MISSING")).isEmpty();
    }

    @Test
    void insert_sameTypeAndRole_violatesUniqueConstraint_butOtherTypeIsAllowed() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "DUP", AuthorityType.ROLE);

        assertThatThrownBy(() -> insertAuthority(AUTH_2, ROLE_PREFIX + "DUP", AuthorityType.ROLE))
                .isInstanceOf(DuplicateKeyException.class);

        insertAuthority(AUTH_3, ROLE_PREFIX + "DUP", AuthorityType.PLAN);
        assertThat(mapper.findByTypeAndRole(AuthorityType.ROLE, ROLE_PREFIX + "DUP").orElseThrow().getId())
                .isEqualTo(AUTH_1);
        assertThat(mapper.findByTypeAndRole(AuthorityType.PLAN, ROLE_PREFIX + "DUP").orElseThrow().getId())
                .isEqualTo(AUTH_3);
        assertThat(mapper.findByTypeAndRole(AuthorityType.PLAN, ROLE_PREFIX + "NONE")).isEmpty();
    }

    @Test
    void search_filtersByRoleAndType_pagesAndReportsTotalSize() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "C", AuthorityType.ROLE);
        insertAuthority(AUTH_2, ROLE_PREFIX + "A", AuthorityType.ROLE);
        insertAuthority(AUTH_3, ROLE_PREFIX + "B", AuthorityType.PLAN);

        List<AuthorityBase> firstPage = mapper.search(ROLE_PREFIX, null, null, 0, 2);
        assertThat(firstPage).extracting(AuthorityBase::getRole)
                .containsExactly(ROLE_PREFIX + "A", ROLE_PREFIX + "B");
        assertThat(firstPage.get(0).getTotalSize()).isEqualTo(3L);

        List<AuthorityBase> secondPage = mapper.search(ROLE_PREFIX, null, null, 2, 2);
        assertThat(secondPage).extracting(AuthorityBase::getRole).containsExactly(ROLE_PREFIX + "C");

        List<AuthorityBase> plans = mapper.search(ROLE_PREFIX, "PLAN", null, 0, 10);
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getType()).isEqualTo(AuthorityType.PLAN);

        List<AuthorityBase> byName = mapper.search(null, null, "Name " + ROLE_PREFIX + "A", 0, 10);
        assertThat(byName).extracting(AuthorityBase::getId).containsExactly(AUTH_2);
    }

    @Test
    void update_changesFieldsAndAuditColumns() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "OLD", AuthorityType.ROLE);

        AuthorityBase changed = new AuthorityBase(AUTH_1, ROLE_PREFIX + "NEW", AuthorityType.PLAN, "New name", null, "N");
        changed.setUpdatedAt(LocalDateTime.now());
        changed.setUpdaterId("SYSTEM");
        assertThat(mapper.update(changed)).isEqualTo(1);

        AuthorityBase found = mapper.findById(AUTH_1).orElseThrow();
        assertThat(found.getRole()).isEqualTo(ROLE_PREFIX + "NEW");
        assertThat(found.getType()).isEqualTo(AuthorityType.PLAN);
        assertThat(found.getName()).isEqualTo("New name");
        assertThat(found.getRemarks()).isNull();
        assertThat(found.getUse()).isEqualTo("N");
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getUpdaterId()).isEqualTo("SYSTEM");
    }

    @Test
    void userMapping_upsertUpdatesPeriod_deleteNotInKeepsListed_deleteAllClears() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "U", AuthorityType.ROLE);
        LocalDate start = LocalDate.of(2030, 1, 1);

        mapper.upsertUser(userRow(USER_1, AUTH_1, start, LocalDate.of(2030, 6, 30)));
        mapper.upsertUser(userRow(USER_2, AUTH_1, start, LocalDate.of(9999, 12, 31)));
        mapper.upsertUser(userRow(USER_1, AUTH_1, start, LocalDate.of(2031, 6, 30)));

        List<AuthorityUser> rows = mapper.findUsersByAuthorityId(AUTH_1);
        assertThat(rows).extracting(AuthorityUser::getUserId).containsExactly(USER_1, USER_2);
        assertThat(rows.get(0).getApplyStartDate()).isEqualTo(start);
        assertThat(rows.get(0).getApplyEndDate()).isEqualTo(LocalDate.of(2031, 6, 30));
        assertThat(rows.get(0).getUpdatedAt()).isNotNull();
        assertThat(rows.get(1).getUpdatedAt()).isNull();

        assertThat(mapper.deleteUsersNotIn(AUTH_1, List.of(USER_2))).isEqualTo(1);
        assertThat(mapper.findUsersByAuthorityId(AUTH_1)).extracting(AuthorityUser::getUserId)
                .containsExactly(USER_2);

        assertThat(mapper.deleteUsersByAuthorityId(AUTH_1)).isEqualTo(1);
        assertThat(mapper.findUsersByAuthorityId(AUTH_1)).isEmpty();
    }

    @Test
    void findExistingUserIds_returnsOnlyExistingIds() {
        assertThat(mapper.findExistingUserIds(List.of(USER_1, USER_2, "U_TEST_AUTHZ_MAPPER_MISSING")))
                .containsExactlyInAnyOrder(USER_1, USER_2);
    }

    @Test
    void searchCandidates_excludesMappedAndNonNormalUsers_andMatchesCaseInsensitively() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "C", AuthorityType.ROLE);
        mapper.upsertUser(userRow(USER_1, AUTH_1, LocalDate.of(2030, 1, 1), LocalDate.of(9999, 12, 31)));

        List<CandidateUser> candidates = mapper.searchCandidates(AUTH_1, UserStatus.NORM, "test_authz_mapper", 50);
        assertThat(candidates).extracting(CandidateUser::getId).containsExactly(USER_2);
        assertThat(candidates.get(0).getUsername()).isEqualTo("test_authz_mapper_two");
        assertThat(candidates.get(0).getName()).isEqualTo("Mapper Two");

        assertThat(mapper.searchCandidates(AUTH_1, UserStatus.NORM, "MAPPER TWO", 50))
                .extracting(CandidateUser::getId).containsExactly(USER_2);
        assertThat(mapper.searchCandidates(AUTH_1, UserStatus.NORM, "MAPPER_TWO@EXAMPLE", 50))
                .extracting(CandidateUser::getId).containsExactly(USER_2);
        assertThat(mapper.searchCandidates(AUTH_1, UserStatus.NORM, "no-such-user-xyz", 50)).isEmpty();
        assertThat(mapper.searchCandidates(AUTH_1, UserStatus.PEND, "test_authz_mapper", 50))
                .extracting(CandidateUser::getId).containsExactly(USER_3);
    }

    @Test
    void menuMapping_grantJoin_upsert_deleteNotIn_deleteAll() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "M", AuthorityType.ROLE);

        mapper.upsertMenu(menuRow(AUTH_1, MENU_1, 3));
        mapper.upsertMenu(menuRow(AUTH_1, MENU_1, 7));

        List<AuthorityMenuRow> rows = mapper.findMenusWithGrant(AUTH_1);
        AuthorityMenuRow root = rows.stream().filter(r -> MENU_1.equals(r.getId())).findFirst().orElseThrow();
        AuthorityMenuRow child = rows.stream().filter(r -> MENU_2.equals(r.getId())).findFirst().orElseThrow();
        assertThat(root.getRbac()).isEqualTo(7);
        assertThat(root.getProgram()).isEqualTo("authz_pgm_1");
        assertThat(root.getParentId()).isNull();
        assertThat(child.getRbac()).isEqualTo(0);
        assertThat(child.getParentId()).isEqualTo(MENU_1);
        assertThat(child.getUse()).isEqualTo("Y");

        List<AuthorityMenuLang> langs = mapper.findAllMenuLangs();
        assertThat(langs).anySatisfy(lang -> {
            assertThat(lang.getMenuId()).isEqualTo(MENU_1);
            assertThat(lang.getLangCode()).isEqualTo("ko_KR");
            assertThat(lang.getLabel()).isEqualTo("테스트 메뉴");
        });

        assertThat(mapper.findExistingMenuIds(List.of(MENU_1, MENU_2, "M_TEST_AUTHZ_MAPPER_MISSING")))
                .containsExactlyInAnyOrder(MENU_1, MENU_2);

        mapper.upsertMenu(menuRow(AUTH_1, MENU_2, 1));
        assertThat(mapper.deleteMenusNotIn(AUTH_1, List.of(MENU_2))).isEqualTo(1);
        assertThat(mapper.deleteMenusByAuthorityId(AUTH_1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", Integer.class, AUTH_1)).isZero();
    }

    @Test
    void deleteById_removesRow() {
        insertAuthority(AUTH_1, ROLE_PREFIX + "D", AuthorityType.ROLE);

        assertThat(mapper.deleteById(AUTH_1)).isEqualTo(1);
        assertThat(mapper.findById(AUTH_1)).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 단계에서 실패하는지 확인**

Run (PowerShell): `cd kkdugi-admin; ./mvnw.cmd -B -ntp test "-Dtest=AdminAuthorityMapperTest"`
Expected: `COMPILATION ERROR` — `AuthorityBase`/`AdminAuthorityMapper` 등 `cannot find symbol`.

- [ ] **Step 3: V11 마이그레이션 작성**

Create `kkdugi-admin/src/main/resources/db/migration/V11__add_auth_role_unique.sql`:

```sql
-- 같은 유형(auth_tp_cd) 안에서 권한 ROLE 코드(auth_role_cd)는 유일해야 한다.
-- SessionUtils의 hasRole 판단이 role 코드 기준이라 중복되면 모호해지고,
-- 서비스의 사전 검사(409)와 별개로 동시 요청 경쟁을 DB가 막게 한다.
ALTER TABLE kkdugi_auth_base
    ADD CONSTRAINT kkdugi_auth_base_udx_tp_role UNIQUE (auth_tp_cd, auth_role_cd);
```

- [ ] **Step 4: 행 모델 6개 작성**

Create `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/models/AuthorityBase.java`:

```java
package kkdugi.app.admin.authority.models;

import kkdugi.core.enums.AuthorityType;
import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_auth_base} 한 행. */
@Getter
@Setter
public class AuthorityBase extends BaseModel {

    private String id;
    private String role;
    private AuthorityType type;
    private String name;
    private String remarks;
    private String use;

    public AuthorityBase() {
    }

    public AuthorityBase(String id, String role, AuthorityType type, String name, String remarks, String use) {
        this.id = id;
        this.role = role;
        this.type = type;
        this.name = name;
        this.remarks = remarks;
        this.use = use;
    }
}
```

Create `.../models/AuthorityUser.java`:

```java
package kkdugi.app.admin.authority.models;

import java.time.LocalDate;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_user_auth} 한 행 — 사용자↔권한 매핑과 적용 기간. */
@Getter
@Setter
public class AuthorityUser extends BaseModel {

    private String userId;
    private String authorityId;
    private LocalDate applyStartDate;
    private LocalDate applyEndDate;

    public AuthorityUser() {
    }

    public AuthorityUser(String userId, String authorityId, LocalDate applyStartDate, LocalDate applyEndDate) {
        this.userId = userId;
        this.authorityId = authorityId;
        this.applyStartDate = applyStartDate;
        this.applyEndDate = applyEndDate;
    }
}
```

Create `.../models/AuthorityMenu.java`:

```java
package kkdugi.app.admin.authority.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_auth_menu} 한 행 — 권한이 메뉴에 갖는 RBAC 비트마스크({@code Rbac} 값의 OR). */
@Getter
@Setter
public class AuthorityMenu extends BaseModel {

    private String authorityId;
    private String menuId;
    private Integer rbac;

    public AuthorityMenu() {
    }

    public AuthorityMenu(String authorityId, String menuId, Integer rbac) {
        this.authorityId = authorityId;
        this.menuId = menuId;
        this.rbac = rbac;
    }
}
```

Create `.../models/AuthorityMenuRow.java`:

```java
package kkdugi.app.admin.authority.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** 메뉴 한 행 + 특정 권한이 그 메뉴에 갖는 RBAC 값(부여가 없으면 0). */
@Getter
@Setter
public class AuthorityMenuRow extends BaseModel {

    private String id;
    private String parentId;
    private String icon;
    private String program;
    private Integer level;
    private String path;
    private Integer sort;
    private String use;
    private Integer rbac;
}
```

Create `.../models/AuthorityMenuLang.java`:

```java
package kkdugi.app.admin.authority.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_menu_lang} 한 행 — 권한 화면의 메뉴 트리 라벨용 읽기 전용 프로젝션. */
@Getter
@Setter
public class AuthorityMenuLang extends BaseModel {

    private String menuId;
    private String langCode;
    private String label;
    private String remarks;
}
```

Create `.../models/CandidateUser.java`:

```java
package kkdugi.app.admin.authority.models;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/** 후보 사용자 조회 행 — {@code kkdugi_user_base}에서 화면에 필요한 컬럼만 읽는다(비밀번호 등 제외). */
@Getter
@Setter
public class CandidateUser extends BaseModel {

    private String id;
    private String username;
    private String name;
    private String image;
}
```

- [ ] **Step 5: mapper 인터페이스 작성**

Create `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/mapper/AdminAuthorityMapper.java`:

```java
package kkdugi.app.admin.authority.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.admin.authority.models.AuthorityBase;
import kkdugi.app.admin.authority.models.AuthorityMenu;
import kkdugi.app.admin.authority.models.AuthorityMenuLang;
import kkdugi.app.admin.authority.models.AuthorityMenuRow;
import kkdugi.app.admin.authority.models.AuthorityUser;
import kkdugi.app.admin.authority.models.CandidateUser;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.enums.UserStatus;

@Mapper
public interface AdminAuthorityMapper {

    // ---- 권한 본체 -----------------------------------------------------

    List<AuthorityBase> search(@Param("role") String role, @Param("type") String type, @Param("name") String name,
            @Param("offset") int offset, @Param("pageSize") int pageSize);

    Optional<AuthorityBase> findById(@Param("id") String id);

    Optional<AuthorityBase> findByTypeAndRole(@Param("type") AuthorityType type, @Param("role") String role);

    int insert(AuthorityBase row);

    int update(AuthorityBase row);

    int deleteById(@Param("id") String id);

    // ---- 사용자 매핑 ---------------------------------------------------

    List<AuthorityUser> findUsersByAuthorityId(@Param("authorityId") String authorityId);

    List<String> findExistingUserIds(@Param("userIds") List<String> userIds);

    int upsertUser(AuthorityUser row);

    int deleteUsersNotIn(@Param("authorityId") String authorityId, @Param("userIds") List<String> userIds);

    int deleteUsersByAuthorityId(@Param("authorityId") String authorityId);

    List<CandidateUser> searchCandidates(@Param("authorityId") String authorityId,
            @Param("status") UserStatus status, @Param("query") String query, @Param("limit") int limit);

    // ---- 메뉴 RBAC 매핑 ------------------------------------------------

    List<AuthorityMenuRow> findMenusWithGrant(@Param("authorityId") String authorityId);

    List<AuthorityMenuLang> findAllMenuLangs();

    List<String> findExistingMenuIds(@Param("menuIds") List<String> menuIds);

    int upsertMenu(AuthorityMenu row);

    int deleteMenusNotIn(@Param("authorityId") String authorityId, @Param("menuIds") List<String> menuIds);

    int deleteMenusByAuthorityId(@Param("authorityId") String authorityId);
}
```

- [ ] **Step 6: mapper XML 작성**

Create `kkdugi-admin/src/main/resources/mapper/postgres/app/admin/authority/AdminAuthorityMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.admin.authority.mapper.AdminAuthorityMapper">

  <resultMap id="authorityBaseResultMap" type="kkdugi.app.admin.authority.models.AuthorityBase"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="id" column="auth_id"/>
    <result property="role" column="auth_role_cd"/>
    <result property="type" column="auth_tp_cd"/>
    <result property="name" column="auth_nm"/>
    <result property="remarks" column="auth_dc"/>
    <result property="use" column="use_yn"/>
    <result property="totalSize" column="total_size"/>
  </resultMap>

  <resultMap id="authorityUserResultMap" type="kkdugi.app.admin.authority.models.AuthorityUser"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="userId" column="user_id"/>
    <id property="authorityId" column="auth_id"/>
    <result property="applyStartDate" column="apl_st_dtm"/>
    <result property="applyEndDate" column="apl_ed_dtm"/>
  </resultMap>

  <resultMap id="candidateUserResultMap" type="kkdugi.app.admin.authority.models.CandidateUser">
    <id property="id" column="user_id"/>
    <result property="username" column="user_login_id"/>
    <result property="name" column="user_nm"/>
    <result property="image" column="user_img_src"/>
  </resultMap>

  <resultMap id="authorityMenuRowResultMap" type="kkdugi.app.admin.authority.models.AuthorityMenuRow">
    <id property="id" column="menu_id"/>
    <result property="parentId" column="menu_parent_id"/>
    <result property="icon" column="menu_ico"/>
    <result property="program" column="menu_pgm"/>
    <result property="level" column="menu_lvl"/>
    <result property="path" column="menu_path"/>
    <result property="sort" column="sort_seq"/>
    <result property="use" column="use_yn"/>
    <result property="rbac" column="auth_val"/>
  </resultMap>

  <resultMap id="authorityMenuLangResultMap" type="kkdugi.app.admin.authority.models.AuthorityMenuLang">
    <id property="menuId" column="menu_id"/>
    <id property="langCode" column="lang_cd"/>
    <result property="label" column="menu_nm"/>
    <result property="remarks" column="menu_dc"/>
  </resultMap>

  <sql id="baseColumns">
<![CDATA[
auth_id, auth_role_cd, auth_tp_cd, auth_nm, auth_dc, use_yn, reg_dtm, reg_id, upd_dtm, upd_id
]]>
  </sql>

  <!--
    * QueryID=search
    * Description=Search authorities with optional role/type/name filters (query-level paging)
    -->
  <select id="search" resultMap="authorityBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.search */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
, COUNT(*) OVER() AS total_size
FROM kkdugi_auth_base
]]>
    <where>
      <if test="role != null and role != ''">
<![CDATA[
AND auth_role_cd LIKE '%' || #{role} || '%'
]]>
      </if>
      <if test="type != null and type != ''">
<![CDATA[
AND auth_tp_cd = #{type}
]]>
      </if>
      <if test="name != null and name != ''">
<![CDATA[
AND auth_nm LIKE '%' || #{name} || '%'
]]>
      </if>
    </where>
<![CDATA[
ORDER BY auth_role_cd, auth_id
OFFSET #{offset} LIMIT #{pageSize}
]]>
  </select>

  <!--
    * QueryID=findById
    * Description=Find one authority by id
    -->
  <select id="findById" resultMap="authorityBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.findById */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_auth_base
WHERE auth_id = #{id}
]]>
  </select>

  <!--
    * QueryID=findByTypeAndRole
    * Description=Find one authority by its unique (type, role) pair
    -->
  <select id="findByTypeAndRole" resultMap="authorityBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.findByTypeAndRole */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_auth_base
WHERE auth_tp_cd = #{type} AND auth_role_cd = #{role}
]]>
  </select>

  <!--
    * QueryID=insert
    * Description=Insert one authority row
    -->
  <insert id="insert" parameterType="kkdugi.app.admin.authority.models.AuthorityBase">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.insert */
INSERT INTO kkdugi_auth_base
    (auth_id, auth_role_cd, auth_tp_cd, auth_nm, auth_dc, use_yn, reg_dtm, reg_id)
VALUES
    (#{id}, #{role}, #{type}, #{name}, #{remarks}, #{use}, #{createdAt}, #{creatorId})
]]>
  </insert>

  <!--
    * QueryID=update
    * Description=Update one authority row
    -->
  <update id="update" parameterType="kkdugi.app.admin.authority.models.AuthorityBase">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.update */
UPDATE kkdugi_auth_base
SET auth_role_cd = #{role},
    auth_tp_cd   = #{type},
    auth_nm      = #{name},
    auth_dc      = #{remarks},
    use_yn       = #{use},
    upd_dtm      = #{updatedAt},
    upd_id       = #{updaterId}
WHERE auth_id = #{id}
]]>
  </update>

  <!--
    * QueryID=deleteById
    * Description=Delete one authority row (callers delete its user/menu mappings first)
    -->
  <delete id="deleteById">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.deleteById */
DELETE FROM kkdugi_auth_base
WHERE auth_id = #{id}
]]>
  </delete>

  <!--
    * QueryID=findUsersByAuthorityId
    * Description=Find every user mapping of one authority
    -->
  <select id="findUsersByAuthorityId" resultMap="authorityUserResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.findUsersByAuthorityId */
SELECT user_id, auth_id, apl_st_dtm, apl_ed_dtm, reg_dtm, reg_id, upd_dtm, upd_id
FROM kkdugi_user_auth
WHERE auth_id = #{authorityId}
ORDER BY user_id
]]>
  </select>

  <!--
    * QueryID=findExistingUserIds
    * Description=Return the subset of the given user ids that exist
    -->
  <select id="findExistingUserIds" resultType="string">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.findExistingUserIds */
SELECT user_id
FROM kkdugi_user_base
WHERE user_id IN
]]>
    <foreach item="userId" collection="userIds" open="(" separator="," close=")">
      #{userId}
    </foreach>
  </select>

  <!--
    * QueryID=upsertUser
    * Description=Insert a user mapping, or update its apply period when it already exists
    -->
  <insert id="upsertUser" parameterType="kkdugi.app.admin.authority.models.AuthorityUser">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.upsertUser */
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
    * QueryID=deleteUsersNotIn
    * Description=Delete the authority's user mappings that are not in the given (non-empty) id list
    -->
  <delete id="deleteUsersNotIn">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.deleteUsersNotIn */
DELETE FROM kkdugi_user_auth
WHERE auth_id = #{authorityId}
  AND user_id NOT IN
]]>
    <foreach item="userId" collection="userIds" open="(" separator="," close=")">
      #{userId}
    </foreach>
  </delete>

  <!--
    * QueryID=deleteUsersByAuthorityId
    * Description=Delete every user mapping of one authority
    -->
  <delete id="deleteUsersByAuthorityId">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.deleteUsersByAuthorityId */
DELETE FROM kkdugi_user_auth
WHERE auth_id = #{authorityId}
]]>
  </delete>

  <!--
    * QueryID=searchCandidates
    * Description=Users with the given status who are not yet mapped to the authority, optionally filtered by login id / name / email (case-insensitive)
    -->
  <select id="searchCandidates" resultMap="candidateUserResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.searchCandidates */
SELECT ub.user_id, ub.user_login_id, ub.user_nm, ub.user_img_src
FROM kkdugi_user_base ub
WHERE ub.user_stat_cd = #{status}
  AND NOT EXISTS (
      SELECT 1
      FROM kkdugi_user_auth ua
      WHERE ua.user_id = ub.user_id AND ua.auth_id = #{authorityId}
  )
]]>
    <if test="query != null and query != ''">
<![CDATA[
  AND (LOWER(ub.user_login_id) LIKE '%' || LOWER(#{query}) || '%'
       OR LOWER(ub.user_nm) LIKE '%' || LOWER(#{query}) || '%'
       OR LOWER(ub.user_email) LIKE '%' || LOWER(#{query}) || '%')
]]>
    </if>
<![CDATA[
ORDER BY ub.user_nm, ub.user_id
LIMIT #{limit}
]]>
  </select>

  <!--
    * QueryID=findMenusWithGrant
    * Description=Every menu with the authority's RBAC value (0 when the authority has no grant on it)
    -->
  <select id="findMenusWithGrant" resultMap="authorityMenuRowResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.findMenusWithGrant */
SELECT mb.menu_id, mb.menu_parent_id, mb.menu_ico, mb.menu_pgm, mb.menu_lvl, mb.menu_path,
       mb.sort_seq, mb.use_yn, COALESCE(am.auth_val, 0) AS auth_val
FROM kkdugi_menu_base mb
LEFT JOIN kkdugi_auth_menu am ON am.menu_id = mb.menu_id AND am.auth_id = #{authorityId}
ORDER BY mb.menu_lvl NULLS LAST, mb.sort_seq NULLS LAST, mb.menu_id
]]>
  </select>

  <!--
    * QueryID=findAllMenuLangs
    * Description=Every menu-language row (labels for the authority menu tree)
    -->
  <select id="findAllMenuLangs" resultMap="authorityMenuLangResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.findAllMenuLangs */
SELECT menu_id, lang_cd, menu_nm, menu_dc
FROM kkdugi_menu_lang
ORDER BY menu_id, lang_cd
]]>
  </select>

  <!--
    * QueryID=findExistingMenuIds
    * Description=Return the subset of the given menu ids that exist
    -->
  <select id="findExistingMenuIds" resultType="string">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.findExistingMenuIds */
SELECT menu_id
FROM kkdugi_menu_base
WHERE menu_id IN
]]>
    <foreach item="menuId" collection="menuIds" open="(" separator="," close=")">
      #{menuId}
    </foreach>
  </select>

  <!--
    * QueryID=upsertMenu
    * Description=Insert an authority-menu grant, or update its RBAC value when it already exists
    -->
  <insert id="upsertMenu" parameterType="kkdugi.app.admin.authority.models.AuthorityMenu">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.upsertMenu */
INSERT INTO kkdugi_auth_menu
    (auth_id, menu_id, auth_val, reg_dtm, reg_id)
VALUES
    (#{authorityId}, #{menuId}, #{rbac}, #{createdAt}, #{creatorId})
ON CONFLICT (auth_id, menu_id) DO UPDATE
SET auth_val = EXCLUDED.auth_val,
    upd_dtm  = EXCLUDED.reg_dtm,
    upd_id   = EXCLUDED.reg_id
]]>
  </insert>

  <!--
    * QueryID=deleteMenusNotIn
    * Description=Delete the authority's menu grants that are not in the given (non-empty) menu id list
    -->
  <delete id="deleteMenusNotIn">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.deleteMenusNotIn */
DELETE FROM kkdugi_auth_menu
WHERE auth_id = #{authorityId}
  AND menu_id NOT IN
]]>
    <foreach item="menuId" collection="menuIds" open="(" separator="," close=")">
      #{menuId}
    </foreach>
  </delete>

  <!--
    * QueryID=deleteMenusByAuthorityId
    * Description=Delete every menu grant of one authority
    -->
  <delete id="deleteMenusByAuthorityId">
<![CDATA[
/* QueryID=kkdugi.app.admin.authority.mapper.AdminAuthorityMapper.deleteMenusByAuthorityId */
DELETE FROM kkdugi_auth_menu
WHERE auth_id = #{authorityId}
]]>
  </delete>

</mapper>
```

- [ ] **Step 7: mapper 테스트 통과 확인**

Run: `cd kkdugi-admin; ./mvnw.cmd -B -ntp test "-Dtest=AdminAuthorityMapperTest"`
Expected: `Tests run: 9, Failures: 0, Errors: 0` / `BUILD SUCCESS`. (Flyway가 V11을 적용하는 로그도 보인다.)

실패 시 점검: 바인딩 개수 오류면 XML **주석**에 `#{...}`가 섞였는지 확인. enum 파라미터(`type`/`status`)가 실패하면 `application.yml`의 `mybatis.configuration.default-enum-type-handler`가 살아 있는지 확인(`CodeEnumTypeHandlerTest`가 이미 검증하는 설정).

- [ ] **Step 8: 기존 테스트 회귀 확인** — V11이 다른 테스트의 권한 행 삽입과 충돌하지 않는지.

Run: `cd kkdugi-admin; ./mvnw.cmd -B -ntp test`
Expected: 전체 `BUILD SUCCESS`. (기존 테스트는 role 코드를 테스트마다 다르게 쓰므로 통과해야 한다. 실패하면 그 테스트의 `(auth_tp_cd, auth_role_cd)`가 겹친 것 — 테스트 쪽 role 값을 고친다.)

- [ ] **Step 9: 커밋**

```bash
git add kkdugi-admin/src/main/resources/db/migration/V11__add_auth_role_unique.sql \
        kkdugi-admin/src/main/java/kkdugi/app/admin/authority \
        kkdugi-admin/src/main/resources/mapper/postgres/app/admin/authority \
        kkdugi-admin/src/test/java/kkdugi/app/admin/authority/mapper
git commit -m "feat(authority): add persistence layer (V11 unique constraint, row models, AdminAuthorityMapper)"
```

---

### Task 2: 서비스 — 검증, 전체 교체 저장, SYS_ADMIN 보호

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/exceptions/{AdminAuthorityValidationException,AdminAuthorityConflictException,AdminAuthorityNotFoundException}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/models/{AdminAuthority,AdminAuthorityParams,AdminAuthorityUser,AdminAuthorityMenu,AdminAuthorityPersistRequest,AdminAuthorityCandidate,AdminAuthorityMenuLocale,AdminAuthorityMenuNode}.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/service/AdminAuthorityService.java`
- Modify: `kkdugi-admin/src/main/resources/messages/messages.properties`, `messages_en_US.properties`, `messages_ko_KR.properties`
- Test: `kkdugi-admin/src/test/java/kkdugi/app/admin/authority/service/AdminAuthorityServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `AdminAuthorityMapper`(전체)와 행 모델 6개(시그니처는 Task 1 Interfaces 참고), `kkdugi.core.Constants.SYS_ADMIN`, `kkdugi.core.util.{SerialUtils,TreeUtils}`, `kkdugi.core.models.{Page,Tree}`, `Rbac.fromMap(Map<String,Boolean>)`/`Rbac.toMap(int)`.
- Produces (Task 3 컨트롤러가 그대로 쓴다):
  - `AdminAuthorityService`:
    - `Page<AdminAuthority> search(AdminAuthorityParams params)`
    - `AdminAuthority get(String id)`
    - `AdminAuthority regist(AdminAuthorityPersistRequest request)`
    - `AdminAuthority save(String id, AdminAuthorityPersistRequest request)`
    - `void delete(String id)`
    - `List<AdminAuthorityCandidate> searchCandidates(String id, String query)`
    - `List<AdminAuthorityMenuNode> menus(String id)`
  - 예외 세 종(모두 `RuntimeException`, 생성자 `(String code)`, `getCode()`; 메시지 == code)
  - 응답 타입: `AdminAuthority`(getter `getId/getRole/getType(String)/getName/getRemarks/getUse/getUsers`), `AdminAuthorityUser(String id, LocalDate applyStartDate, LocalDate applyEndDate)`, `AdminAuthorityCandidate(String id, String username, String name, String image)`, `AdminAuthorityMenuNode`(getter `getId/getParentId/getLocale/getIcon/getProgram/getUse/getPath/getLevel/getSort/getAuthorities/getChildren`)
  - 요청 타입: `AdminAuthorityPersistRequest(String role, String type, String name, String remarks, String use, List<AdminAuthorityUser> users, List<AdminAuthorityMenu> menus)`, `AdminAuthorityMenu(String id, Map<String, Boolean> authorities)`, `AdminAuthorityParams(String role, String type, String name, int page, int pageSize)` + 기본 생성자
  - 에러 코드 상수: `ERR_MALFORMED_REQUEST`(400), `ERR_USER_NOT_FOUND`(400), `ERR_MENU_NOT_FOUND`(400), `ERR_NOT_FOUND`(404), `ERR_DUPLICATE`(409), `ERR_IMMUTABLE`(409)

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

Create `kkdugi-admin/src/test/java/kkdugi/app/admin/authority/service/AdminAuthorityServiceTest.java`:

```java
package kkdugi.app.admin.authority.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityConflictException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityNotFoundException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityValidationException;
import kkdugi.app.admin.authority.mapper.AdminAuthorityMapper;
import kkdugi.app.admin.authority.models.AdminAuthority;
import kkdugi.app.admin.authority.models.AdminAuthorityCandidate;
import kkdugi.app.admin.authority.models.AdminAuthorityMenu;
import kkdugi.app.admin.authority.models.AdminAuthorityMenuNode;
import kkdugi.app.admin.authority.models.AdminAuthorityParams;
import kkdugi.app.admin.authority.models.AdminAuthorityPersistRequest;
import kkdugi.app.admin.authority.models.AdminAuthorityUser;
import kkdugi.app.admin.authority.models.AuthorityBase;
import kkdugi.core.Constants;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.enums.Rbac;
import kkdugi.core.models.Page;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminAuthorityServiceTest {

    private static final String ROLE_PREFIX = "TEST_AUTHZ_SVC_";
    private static final String USER_1 = "U_TEST_AUTHZ_SVC_1";
    private static final String USER_2 = "U_TEST_AUTHZ_SVC_2";
    private static final String USER_3 = "U_TEST_AUTHZ_SVC_3";
    private static final String MENU_1 = "M_TEST_AUTHZ_SVC_1";
    private static final String MENU_2 = "M_TEST_AUTHZ_SVC_2";

    @Autowired
    private AdminAuthorityService service;

    @Autowired
    private AdminAuthorityMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        wipe();
        insertUser(USER_1, "test_authz_svc_one", "Svc One", "20");
        insertUser(USER_2, "test_authz_svc_two", "Svc Two", "20");
        insertUser(USER_3, "test_authz_svc_three", "Svc Three", "10");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "authz_svc_1", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_parent_id, menu_pgm, sort_seq, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                MENU_2, MENU_1, "authz_svc_2", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "ko_KR", "서비스 테스트 메뉴", "SYSTEM");
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        String ids = "SELECT auth_id FROM kkdugi_auth_base WHERE auth_role_cd LIKE '" + ROLE_PREFIX + "%'";
        jdbcTemplate.update("DELETE FROM kkdugi_auth_menu WHERE auth_id IN (" + ids + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE auth_id IN (" + ids + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_role_cd LIKE '" + ROLE_PREFIX + "%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id LIKE 'M_TEST_AUTHZ_SVC_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id LIKE 'M_TEST_AUTHZ_SVC_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id LIKE 'U_TEST_AUTHZ_SVC_%'");
    }

    private void insertUser(String id, String loginId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", status, "SYSTEM");
    }

    private static AdminAuthorityPersistRequest request(String roleSuffix, String type,
            List<AdminAuthorityUser> users, List<AdminAuthorityMenu> menus) {
        return new AdminAuthorityPersistRequest(ROLE_PREFIX + roleSuffix, type, "Name " + roleSuffix,
                "remarks", null, users, menus);
    }

    /** {@code codes}에 든 RBAC 코드만 true, 나머지는 false인 메뉴 부여 항목. */
    private static AdminAuthorityMenu grant(String menuId, String... codes) {
        Map<String, Boolean> authorities = new LinkedHashMap<>();
        for (Rbac rbac : Rbac.values()) {
            authorities.put(rbac.getCode(), Arrays.asList(codes).contains(rbac.getCode()));
        }
        return new AdminAuthorityMenu(menuId, authorities);
    }

    private void assertMalformed(AdminAuthorityPersistRequest request) {
        assertThatThrownBy(() -> service.regist(request))
                .isInstanceOf(AdminAuthorityValidationException.class)
                .hasMessage(AdminAuthorityService.ERR_MALFORMED_REQUEST);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    // ---- regist -------------------------------------------------------

    @Test
    void regist_createsAuthority_withGeneratedIdAndDefaults() {
        AdminAuthority created = service.regist(request("BASIC", "ROLE", null, null));

        assertThat(created.getId()).startsWith("A");
        assertThat(created.getRole()).isEqualTo(ROLE_PREFIX + "BASIC");
        assertThat(created.getType()).isEqualTo("ROLE");
        assertThat(created.getName()).isEqualTo("Name BASIC");
        assertThat(created.getRemarks()).isEqualTo("remarks");
        assertThat(created.getUse()).isEqualTo("Y");
        assertThat(created.getUsers()).isEmpty();
    }

    @Test
    void regist_rejectsMalformedRequests() {
        List<AdminAuthorityPersistRequest> bad = List.of(
                new AdminAuthorityPersistRequest(null, "ROLE", "n", null, null, null, null),
                new AdminAuthorityPersistRequest("  ", "ROLE", "n", null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "ROLE", null, null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "BOGUS", "n", null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", null, "n", null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "ROLE", "n", null, "MAYBE", null, null),
                new AdminAuthorityPersistRequest("R".repeat(61), "ROLE", "n", null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "ROLE", "n".repeat(201), null, null, null, null),
                new AdminAuthorityPersistRequest(ROLE_PREFIX + "X", "ROLE", "n", "r".repeat(1001), null, null, null));

        for (AdminAuthorityPersistRequest request : bad) {
            assertMalformed(request);
        }
    }

    @Test
    void regist_duplicateTypeAndRole_conflicts_butSameRoleInOtherTypeIsAllowed() {
        service.regist(request("DUP", "ROLE", null, null));

        assertThatThrownBy(() -> service.regist(request("DUP", "ROLE", null, null)))
                .isInstanceOf(AdminAuthorityConflictException.class)
                .hasMessage(AdminAuthorityService.ERR_DUPLICATE);

        AdminAuthority plan = service.regist(request("DUP", "PLAN", null, null));
        assertThat(plan.getType()).isEqualTo("PLAN");
    }

    @Test
    void regist_rejectsBadUsersAndMenus_andPersistsNothing() {
        LocalDate day = LocalDate.of(2030, 1, 1);

        assertMalformed(request("V1", "ROLE",
                List.of(new AdminAuthorityUser(USER_1, day.plusDays(1), day)), null));
        assertMalformed(request("V2", "ROLE",
                List.of(new AdminAuthorityUser(USER_1, null, null), new AdminAuthorityUser(USER_1, null, null)), null));
        assertMalformed(request("V3", "ROLE", List.of(new AdminAuthorityUser(" ", null, null)), null));
        assertMalformed(request("V4", "ROLE", null, List.of(new AdminAuthorityMenu(MENU_1, Map.of("99", true)))));
        assertMalformed(request("V5", "ROLE", null, List.of(new AdminAuthorityMenu(MENU_1, null))));
        assertMalformed(request("V6", "ROLE", null, List.of(grant(MENU_1, "10"), grant(MENU_1, "20"))));

        assertThatThrownBy(() -> service.regist(request("V7", "ROLE",
                List.of(new AdminAuthorityUser("U_TEST_AUTHZ_SVC_MISSING", null, null)), null)))
                .isInstanceOf(AdminAuthorityValidationException.class)
                .hasMessage(AdminAuthorityService.ERR_USER_NOT_FOUND);
        assertThatThrownBy(() -> service.regist(request("V8", "ROLE", null,
                List.of(grant("M_TEST_AUTHZ_SVC_MISSING", "10")))))
                .isInstanceOf(AdminAuthorityValidationException.class)
                .hasMessage(AdminAuthorityService.ERR_MENU_NOT_FOUND);

        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_base WHERE auth_role_cd LIKE ?", ROLE_PREFIX + "%"))
                .isZero();
    }

    @Test
    void regist_persistsUsersAndMenus_withDefaultPeriodAndSkipsAllFalseGrants() {
        AdminAuthority created = service.regist(request("FULL", "ROLE",
                List.of(new AdminAuthorityUser(USER_1, null, null),
                        new AdminAuthorityUser(USER_2, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31))),
                List.of(grant(MENU_1, "10", "20"), grant(MENU_2))));

        assertThat(created.getUsers()).hasSize(2);
        AdminAuthorityUser first = created.getUsers().stream()
                .filter(user -> USER_1.equals(user.getId())).findFirst().orElseThrow();
        assertThat(first.getApplyStartDate()).isEqualTo(LocalDate.now());
        assertThat(first.getApplyEndDate()).isEqualTo(LocalDate.of(9999, 12, 31));
        AdminAuthorityUser second = created.getUsers().stream()
                .filter(user -> USER_2.equals(user.getId())).findFirst().orElseThrow();
        assertThat(second.getApplyStartDate()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(second.getApplyEndDate()).isEqualTo(LocalDate.of(2030, 12, 31));

        assertThat(count("SELECT auth_val FROM kkdugi_auth_menu WHERE auth_id = ? AND menu_id = ?",
                created.getId(), MENU_1)).isEqualTo(Rbac.READ.getValue() | Rbac.WRTE.getValue());
        // MENU_2는 전부 false라 행이 저장되지 않는다.
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isEqualTo(1);
    }

    // ---- get / search -------------------------------------------------

    @Test
    void get_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.get("A_TEST_AUTHZ_SVC_MISSING"))
                .isInstanceOf(AdminAuthorityNotFoundException.class)
                .hasMessage(AdminAuthorityService.ERR_NOT_FOUND);
    }

    @Test
    void search_normalizesParams_pagesAndOmitsUsers() {
        service.regist(request("P1", "ROLE", null, null));
        service.regist(request("P2", "ROLE", null, null));
        service.regist(request("P3", "ROLE", null, null));

        AdminAuthorityParams params = new AdminAuthorityParams(ROLE_PREFIX, null, null, 0, 2);
        Page<AdminAuthority> page = service.search(params);

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(2);
        assertThat(page.getTotalItems()).isEqualTo(3);
        assertThat(page.getContents()).hasSize(2);
        assertThat(page.getContents().get(0).getRole()).isEqualTo(ROLE_PREFIX + "P1");
        assertThat(page.getContents().get(0).getUsers()).isNull();
    }

    // ---- save ---------------------------------------------------------

    @Test
    void save_updatesFields_andLeavesMappingsAlone_whenUsersAndMenusAreNull() {
        AdminAuthority created = service.regist(request("KEEP", "ROLE",
                List.of(new AdminAuthorityUser(USER_1, null, null)), List.of(grant(MENU_1, "10"))));

        AdminAuthority saved = service.save(created.getId(), new AdminAuthorityPersistRequest(
                ROLE_PREFIX + "KEEP", "ROLE", "Renamed", "new remarks", "N", null, null));

        assertThat(saved.getName()).isEqualTo("Renamed");
        assertThat(saved.getRemarks()).isEqualTo("new remarks");
        assertThat(saved.getUse()).isEqualTo("N");
        assertThat(saved.getUsers()).extracting(AdminAuthorityUser::getId).containsExactly(USER_1);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isEqualTo(1);
        AuthorityBase row = mapper.findById(created.getId()).orElseThrow();
        assertThat(row.getUpdatedAt()).isNotNull();
        assertThat(row.getUpdaterId()).isEqualTo("SYSTEM");
    }

    @Test
    void save_keepsExistingUse_whenRequestOmitsIt() {
        AdminAuthority created = service.regist(new AdminAuthorityPersistRequest(
                ROLE_PREFIX + "USE", "ROLE", "n", null, "N", null, null));

        AdminAuthority saved = service.save(created.getId(), new AdminAuthorityPersistRequest(
                ROLE_PREFIX + "USE", "ROLE", "n2", null, null, null, null));

        assertThat(saved.getUse()).isEqualTo("N");
    }

    @Test
    void save_replacesUsersAndMenus_andEmptyListsClearThem() {
        AdminAuthority created = service.regist(request("REPL", "ROLE",
                List.of(new AdminAuthorityUser(USER_1, null, null)),
                List.of(grant(MENU_1, "10"), grant(MENU_2, "10"))));

        AdminAuthority replaced = service.save(created.getId(), request("REPL", "ROLE",
                List.of(new AdminAuthorityUser(USER_2, null, null)),
                List.of(grant(MENU_1, "10", "20", "30", "40"))));

        assertThat(replaced.getUsers()).extracting(AdminAuthorityUser::getId).containsExactly(USER_2);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isEqualTo(1);
        assertThat(count("SELECT auth_val FROM kkdugi_auth_menu WHERE auth_id = ? AND menu_id = ?",
                created.getId(), MENU_1)).isEqualTo(15);

        AdminAuthority cleared = service.save(created.getId(), request("REPL", "ROLE", List.of(), List.of()));

        assertThat(cleared.getUsers()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isZero();
    }

    @Test
    void save_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.save("A_TEST_AUTHZ_SVC_MISSING", request("NOPE", "ROLE", null, null)))
                .isInstanceOf(AdminAuthorityNotFoundException.class)
                .hasMessage(AdminAuthorityService.ERR_NOT_FOUND);
    }

    @Test
    void save_toAnotherAuthoritysRole_conflicts_butKeepingOwnRoleIsFine() {
        AdminAuthority first = service.regist(request("S1", "ROLE", null, null));
        AdminAuthority second = service.regist(request("S2", "ROLE", null, null));

        assertThatThrownBy(() -> service.save(second.getId(), request("S1", "ROLE", null, null)))
                .isInstanceOf(AdminAuthorityConflictException.class)
                .hasMessage(AdminAuthorityService.ERR_DUPLICATE);

        AdminAuthority sameRole = service.save(second.getId(), request("S2", "ROLE", null, null));
        assertThat(sameRole.getRole()).isEqualTo(ROLE_PREFIX + "S2");
        assertThat(first.getRole()).isEqualTo(ROLE_PREFIX + "S1");
    }

    @Test
    void sysAdmin_cannotBeDeleted_renamedRetypedOrDeactivated() {
        AuthorityBase sysAdmin = mapper.findByTypeAndRole(AuthorityType.ROLE, Constants.SYS_ADMIN).orElseThrow();
        List<AdminAuthorityPersistRequest> forbidden = List.of(
                new AdminAuthorityPersistRequest("OTHER_ROLE", "ROLE", sysAdmin.getName(), null, null, null, null),
                new AdminAuthorityPersistRequest(Constants.SYS_ADMIN, "PLAN", sysAdmin.getName(), null, null, null, null),
                new AdminAuthorityPersistRequest(Constants.SYS_ADMIN, "ROLE", sysAdmin.getName(), null, "N", null, null));

        for (AdminAuthorityPersistRequest request : forbidden) {
            assertThatThrownBy(() -> service.save(sysAdmin.getId(), request))
                    .isInstanceOf(AdminAuthorityConflictException.class)
                    .hasMessage(AdminAuthorityService.ERR_IMMUTABLE);
        }
        assertThatThrownBy(() -> service.delete(sysAdmin.getId()))
                .isInstanceOf(AdminAuthorityConflictException.class)
                .hasMessage(AdminAuthorityService.ERR_IMMUTABLE);

        AuthorityBase after = mapper.findById(sysAdmin.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(Constants.SYS_ADMIN);
        assertThat(after.getType()).isEqualTo(AuthorityType.ROLE);
        assertThat(after.getUse()).isEqualTo("Y");
    }

    // ---- delete -------------------------------------------------------

    @Test
    void delete_removesAuthorityUsersAndMenus_andMissingIdIsNoOp() {
        AdminAuthority created = service.regist(request("DEL", "ROLE",
                List.of(new AdminAuthorityUser(USER_1, null, null)), List.of(grant(MENU_1, "10"))));

        service.delete(created.getId());

        assertThat(mapper.findById(created.getId())).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_auth WHERE auth_id = ?", created.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM kkdugi_auth_menu WHERE auth_id = ?", created.getId())).isZero();
        // 사용자/메뉴 자체는 남아 있다.
        assertThat(count("SELECT COUNT(*) FROM kkdugi_user_base WHERE user_id = ?", USER_1)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM kkdugi_menu_base WHERE menu_id = ?", MENU_1)).isEqualTo(1);

        service.delete("A_TEST_AUTHZ_SVC_MISSING");
    }

    // ---- candidates / menus -------------------------------------------

    @Test
    void searchCandidates_excludesMappedAndNonNormalUsers_andUnknownAuthorityIsNotFound() {
        AdminAuthority created = service.regist(request("CAND", "ROLE",
                List.of(new AdminAuthorityUser(USER_1, null, null)), null));

        List<AdminAuthorityCandidate> candidates = service.searchCandidates(created.getId(), "TEST_AUTHZ_SVC");

        assertThat(candidates).extracting(AdminAuthorityCandidate::getId).containsExactly(USER_2);
        assertThat(candidates.get(0).getUsername()).isEqualTo("test_authz_svc_two");
        assertThat(candidates.get(0).getName()).isEqualTo("Svc Two");
        assertThat(service.searchCandidates(created.getId(), "  ")).isNotEmpty();

        assertThatThrownBy(() -> service.searchCandidates("A_TEST_AUTHZ_SVC_MISSING", null))
                .isInstanceOf(AdminAuthorityNotFoundException.class);
    }

    @Test
    void menus_returnsTreeWithGrantsAndLabels_andUnknownAuthorityIsNotFound() {
        AdminAuthority created = service.regist(request("TREE", "ROLE", null,
                List.of(grant(MENU_1, "10", "20"))));

        List<AdminAuthorityMenuNode> tree = service.menus(created.getId());

        AdminAuthorityMenuNode root = tree.stream().filter(node -> MENU_1.equals(node.getId()))
                .findFirst().orElseThrow();
        assertThat(root.getAuthorities())
                .containsEntry("10", true).containsEntry("20", true)
                .containsEntry("30", false).containsEntry("40", false);
        assertThat(root.getLocale()).containsKey("ko_KR");
        assertThat(root.getLocale().get("ko_KR").getLabel()).isEqualTo("서비스 테스트 메뉴");
        assertThat(root.getProgram()).isEqualTo("authz_svc_1");
        assertThat(root.getChildren()).hasSize(1);
        AdminAuthorityMenuNode child = root.getChildren().get(0);
        assertThat(child.getId()).isEqualTo(MENU_2);
        assertThat(child.getParentId()).isEqualTo(MENU_1);
        assertThat(child.getAuthorities()).containsEntry("10", false);

        assertThatThrownBy(() -> service.menus("A_TEST_AUTHZ_SVC_MISSING"))
                .isInstanceOf(AdminAuthorityNotFoundException.class);
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 단계에서 실패하는지 확인**

Run: `cd kkdugi-admin; ./mvnw.cmd -B -ntp test "-Dtest=AdminAuthorityServiceTest"`
Expected: `COMPILATION ERROR` — `AdminAuthorityService`/`AdminAuthority*` 등 `cannot find symbol`.

- [ ] **Step 3: 예외 클래스 3개 작성**

Create `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/exceptions/AdminAuthorityValidationException.java`:

```java
package kkdugi.app.admin.authority.exceptions;

/** 요청 값이 올바르지 않을 때(400). */
public class AdminAuthorityValidationException extends RuntimeException {

    private final String code;

    public AdminAuthorityValidationException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

Create `.../exceptions/AdminAuthorityConflictException.java`:

```java
package kkdugi.app.admin.authority.exceptions;

/** 현재 상태와 충돌할 때(409) — 유형+role 중복, SYS_ADMIN 보호. */
public class AdminAuthorityConflictException extends RuntimeException {

    private final String code;

    public AdminAuthorityConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

Create `.../exceptions/AdminAuthorityNotFoundException.java`:

```java
package kkdugi.app.admin.authority.exceptions;

/** 대상 권한이 없을 때(404). */
public class AdminAuthorityNotFoundException extends RuntimeException {

    private final String code;

    public AdminAuthorityNotFoundException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

- [ ] **Step 4: 콘텐츠/요청/응답 모델 8개 작성**

Create `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/models/AdminAuthority.java`:

```java
package kkdugi.app.admin.authority.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import kkdugi.core.models.BaseModel;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 권한 목록/상세 응답 콘텐츠. {@link kkdugi.core.models.Page}가
 * {@code T extends BaseModel}을 요구해서 BaseModel을 상속하고, 응답에
 * 노출하지 않을 감사 필드는 {@link JsonIgnoreProperties}로 숨긴다.
 * {@code users}는 상세/등록/저장 응답에만 채워지고 목록에서는 {@code null}이라
 * JSON에서 빠진다.
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties({ "rownum", "createdAt", "creatorId", "updatedAt", "updaterId" })
public class AdminAuthority extends BaseModel {

    private final String id;
    private final String role;
    private final String type;
    private final String name;
    private final String remarks;
    private final String use;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final List<AdminAuthorityUser> users;
}
```

Create `.../models/AdminAuthorityParams.java`:

```java
package kkdugi.app.admin.authority.models;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminAuthorityParams extends BaseParams {

    private String role;
    private String type;
    private String name;

    public AdminAuthorityParams() {
    }

    public AdminAuthorityParams(String role, String type, String name, int page, int pageSize) {
        this.role = role;
        this.type = type;
        this.name = name;
        setPage(page);
        setPageSize(pageSize);
    }
}
```

Create `.../models/AdminAuthorityUser.java`:

```java
package kkdugi.app.admin.authority.models;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 권한에 붙는 사용자 한 명과 적용 기간 — 요청/응답 공용. 날짜는 {@code yyyy-MM-dd}. */
@Getter
@AllArgsConstructor
public class AdminAuthorityUser {

    private final String id;
    private final LocalDate applyStartDate;
    private final LocalDate applyEndDate;
}
```

Create `.../models/AdminAuthorityMenu.java`:

```java
package kkdugi.app.admin.authority.models;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 요청의 메뉴 부여 항목 — {@code authorities}의 키는 {@code Rbac} 숫자 코드({@code "10"}~{@code "40"}). */
@Getter
@AllArgsConstructor
public class AdminAuthorityMenu {

    private final String id;
    private final Map<String, Boolean> authorities;
}
```

Create `.../models/AdminAuthorityPersistRequest.java`:

```java
package kkdugi.app.admin.authority.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * regist/save 요청 본문. {@code users}/{@code menus}가 {@code null}이면 그
 * 매핑은 건드리지 않고, 빈 배열이면 전부 비운다. save의 본문 {@code id}는
 * 경로의 id가 우선이라 무시한다.
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminAuthorityPersistRequest {

    private final String role;
    private final String type;
    private final String name;
    private final String remarks;
    private final String use;
    private final List<AdminAuthorityUser> users;
    private final List<AdminAuthorityMenu> menus;
}
```

Create `.../models/AdminAuthorityCandidate.java`:

```java
package kkdugi.app.admin.authority.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 권한에 붙일 수 있는 후보 사용자. */
@Getter
@AllArgsConstructor
public class AdminAuthorityCandidate {

    private final String id;
    private final String username;
    private final String name;
    private final String image;
}
```

Create `.../models/AdminAuthorityMenuLocale.java`:

```java
package kkdugi.app.admin.authority.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminAuthorityMenuLocale {

    private final String label;
    private final String remarks;
}
```

Create `.../models/AdminAuthorityMenuNode.java`:

```java
package kkdugi.app.admin.authority.models;

import java.util.List;
import java.util.Map;

import kkdugi.core.models.Tree;

import lombok.Getter;
import lombok.Setter;

/**
 * 권한 화면의 메뉴 트리 노드. 메뉴 관리({@code app.admin.menu})의 모델을
 * 재사용하지 않는다(app.admin.* 기능끼리는 서로 import하지 않는다). 이
 * 권한이 그 메뉴에 갖는 RBAC를 {@code authorities}({@code "10"}~{@code "40"}
 * → true/false)로 싣는다.
 */
@Getter
public class AdminAuthorityMenuNode implements Tree<AdminAuthorityMenuNode> {

    private final String id;
    private final String parentId;
    private final Map<String, AdminAuthorityMenuLocale> locale;
    private final String icon;
    private final String program;
    private final String use;
    private final String path;
    private final Integer level;
    private final int sort;
    private final Map<String, Boolean> authorities;

    @Setter
    private List<AdminAuthorityMenuNode> children;

    public AdminAuthorityMenuNode(String id, String parentId, Map<String, AdminAuthorityMenuLocale> locale,
            String icon, String program, String use, String path, Integer level, Integer sort,
            Map<String, Boolean> authorities) {
        this.id = id;
        this.parentId = parentId;
        this.locale = locale;
        this.icon = icon;
        this.program = program;
        this.use = use;
        this.path = path;
        this.level = level;
        this.sort = sort == null ? 0 : sort;
        this.authorities = authorities;
    }
}
```

- [ ] **Step 5: 에러 메시지 6개 추가** (3개 properties 파일 끝)

Edit `kkdugi-admin/src/main/resources/messages/messages.properties` — `old_string`: `menu.err.immutable=The parent menu cannot be modified`, `new_string`:

```
menu.err.immutable=The parent menu cannot be modified
authority.err.malformed_request=The request is malformed
authority.err.user_not_found=A specified user could not be found
authority.err.menu_not_found=A specified menu could not be found
authority.err.not_found=The target authority could not be found
authority.err.duplicate=An authority with the same type and role already exists
authority.err.immutable=The SYS_ADMIN authority cannot be deleted, renamed, retyped or deactivated
```

Edit `messages_en_US.properties` — 같은 `old_string`/`new_string`(위와 동일).

Edit `messages_ko_KR.properties` — `old_string`: `menu.err.immutable=상위 메뉴는 수정할 수 없습니다`, `new_string`:

```
menu.err.immutable=상위 메뉴는 수정할 수 없습니다
authority.err.malformed_request=요청 값이 올바르지 않습니다
authority.err.user_not_found=지정한 사용자를 찾을 수 없습니다
authority.err.menu_not_found=지정한 메뉴를 찾을 수 없습니다
authority.err.not_found=대상 권한을 찾을 수 없습니다
authority.err.duplicate=같은 유형과 권한 코드를 가진 권한이 이미 있습니다
authority.err.immutable=SYS_ADMIN 권한은 삭제하거나 권한 코드/유형을 바꾸거나 비활성화할 수 없습니다
```

- [ ] **Step 6: 서비스 작성**

Create `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/service/AdminAuthorityService.java`:

```java
package kkdugi.app.admin.authority.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.admin.authority.exceptions.AdminAuthorityConflictException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityNotFoundException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityValidationException;
import kkdugi.app.admin.authority.mapper.AdminAuthorityMapper;
import kkdugi.app.admin.authority.models.AdminAuthority;
import kkdugi.app.admin.authority.models.AdminAuthorityCandidate;
import kkdugi.app.admin.authority.models.AdminAuthorityMenu;
import kkdugi.app.admin.authority.models.AdminAuthorityMenuLocale;
import kkdugi.app.admin.authority.models.AdminAuthorityMenuNode;
import kkdugi.app.admin.authority.models.AdminAuthorityParams;
import kkdugi.app.admin.authority.models.AdminAuthorityPersistRequest;
import kkdugi.app.admin.authority.models.AdminAuthorityUser;
import kkdugi.app.admin.authority.models.AuthorityBase;
import kkdugi.app.admin.authority.models.AuthorityMenu;
import kkdugi.app.admin.authority.models.AuthorityMenuLang;
import kkdugi.app.admin.authority.models.AuthorityMenuRow;
import kkdugi.app.admin.authority.models.AuthorityUser;
import kkdugi.core.Constants;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.enums.Rbac;
import kkdugi.core.enums.UserStatus;
import kkdugi.core.models.Page;
import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;
import kkdugi.core.util.TreeUtils;

@Service
public class AdminAuthorityService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthorityService.class);

    public static final String ERR_MALFORMED_REQUEST = "authority.err.malformed_request";
    public static final String ERR_USER_NOT_FOUND = "authority.err.user_not_found";
    public static final String ERR_MENU_NOT_FOUND = "authority.err.menu_not_found";
    public static final String ERR_NOT_FOUND = "authority.err.not_found";
    public static final String ERR_DUPLICATE = "authority.err.duplicate";
    public static final String ERR_IMMUTABLE = "authority.err.immutable";

    private static final String SYSTEM_USER_ID = "SYSTEM";
    private static final String DEFAULT_USE = "Y";
    private static final Set<String> USE_VALUES = Set.of("Y", "N");
    private static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);
    private static final int MAX_ROLE_LENGTH = 60;
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_REMARKS_LENGTH = 1000;
    private static final int CANDIDATE_LIMIT = 200;

    private static final SerialConfig SERIAL_CONFIG = new SerialConfig() {

        @Override
        public String getId() {
            return "KKDUGI_AUTH";
        }

        @Override
        public String getValueFormatter() {
            return "A%s%04d";
        }
    };

    private final AdminAuthorityMapper adminAuthorityMapper;

    public AdminAuthorityService(AdminAuthorityMapper adminAuthorityMapper) {
        this.adminAuthorityMapper = adminAuthorityMapper;
    }

    // ---- 조회 ----------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminAuthority> search(AdminAuthorityParams params) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());

        List<AuthorityBase> rows = adminAuthorityMapper.search(
                params.getRole(), params.getType(), params.getName(), params.getOffset(), params.getLimit());
        List<AdminAuthority> contents = rows.stream().map(row -> {
            AdminAuthority content = toContent(row, null);
            content.setTotalSize(row.getTotalSize());
            return content;
        }).toList();

        return Page.of(contents, params);
    }

    @Transactional(readOnly = true)
    public AdminAuthority get(String id) {
        AuthorityBase row = findExisting(id);
        List<AdminAuthorityUser> users = adminAuthorityMapper.findUsersByAuthorityId(id).stream()
                .map(user -> new AdminAuthorityUser(user.getUserId(), user.getApplyStartDate(), user.getApplyEndDate()))
                .toList();
        return toContent(row, users);
    }

    @Transactional(readOnly = true)
    public List<AdminAuthorityCandidate> searchCandidates(String id, String query) {
        findExisting(id);
        String trimmed = query == null || query.isBlank() ? null : query.trim();
        return adminAuthorityMapper.searchCandidates(id, UserStatus.NORM, trimmed, CANDIDATE_LIMIT).stream()
                .map(user -> new AdminAuthorityCandidate(user.getId(), user.getUsername(), user.getName(), user.getImage()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminAuthorityMenuNode> menus(String id) {
        findExisting(id);
        List<AuthorityMenuRow> rows = adminAuthorityMapper.findMenusWithGrant(id);
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, Map<String, AdminAuthorityMenuLocale>> locales = new LinkedHashMap<>();
        for (AuthorityMenuLang lang : adminAuthorityMapper.findAllMenuLangs()) {
            locales.computeIfAbsent(lang.getMenuId(), key -> new LinkedHashMap<>())
                    .put(lang.getLangCode(), new AdminAuthorityMenuLocale(lang.getLabel(), lang.getRemarks()));
        }

        List<AdminAuthorityMenuNode> nodes = rows.stream()
                .map(row -> new AdminAuthorityMenuNode(row.getId(), row.getParentId(),
                        locales.getOrDefault(row.getId(), Map.of()), row.getIcon(), row.getProgram(), row.getUse(),
                        row.getPath(), row.getLevel(), row.getSort(), Rbac.toMap(row.getRbac())))
                .toList();
        return TreeUtils.convert(nodes);
    }

    // ---- 쓰기 ----------------------------------------------------------

    @Transactional
    public AdminAuthority regist(AdminAuthorityPersistRequest request) {
        validate(request);
        AuthorityType type = AuthorityType.fromCode(request.getType());
        checkDuplicate(type, request.getRole(), null);

        LocalDateTime now = LocalDateTime.now();
        String id = SerialUtils.next(SERIAL_CONFIG);
        String use = request.getUse() != null ? request.getUse() : DEFAULT_USE;

        AuthorityBase row = new AuthorityBase(id, request.getRole(), type, request.getName(), request.getRemarks(), use);
        row.setCreatedAt(now);
        row.setCreatorId(SYSTEM_USER_ID);
        try {
            adminAuthorityMapper.insert(row);
        } catch (DuplicateKeyException e) {
            log.warn("권한 등록 실패 - 같은 유형/role이 동시에 등록됨: type={}, role={}", type, request.getRole());
            throw new AdminAuthorityConflictException(ERR_DUPLICATE);
        }

        replaceUsers(id, request.getUsers(), now);
        replaceMenus(id, request.getMenus(), now);
        return get(id);
    }

    @Transactional
    public AdminAuthority save(String id, AdminAuthorityPersistRequest request) {
        validate(request);
        AuthorityBase existing = findExisting(id);
        AuthorityType type = AuthorityType.fromCode(request.getType());
        String use = request.getUse() != null ? request.getUse() : existing.getUse();
        boolean identityChanged = !existing.getRole().equals(request.getRole()) || existing.getType() != type;

        // SYS_ADMIN은 KkdugiUserDetailsService가 role 코드로 메뉴 우회를 판단한다 —
        // 바꾸거나 끄면 모든 사용자가 잠길 수 있어 이름/설명만 수정할 수 있다.
        if (isSysAdmin(existing) && (identityChanged || !DEFAULT_USE.equals(use))) {
            log.warn("권한 저장 실패 - SYS_ADMIN 변경 시도: id={}", id);
            throw new AdminAuthorityConflictException(ERR_IMMUTABLE);
        }
        if (identityChanged) {
            checkDuplicate(type, request.getRole(), id);
        }

        LocalDateTime now = LocalDateTime.now();
        AuthorityBase row = new AuthorityBase(id, request.getRole(), type, request.getName(), request.getRemarks(), use);
        row.setUpdatedAt(now);
        row.setUpdaterId(SYSTEM_USER_ID);
        try {
            adminAuthorityMapper.update(row);
        } catch (DuplicateKeyException e) {
            log.warn("권한 저장 실패 - 같은 유형/role이 동시에 등록됨: type={}, role={}", type, request.getRole());
            throw new AdminAuthorityConflictException(ERR_DUPLICATE);
        }

        replaceUsers(id, request.getUsers(), now);
        replaceMenus(id, request.getMenus(), now);
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        AuthorityBase existing = adminAuthorityMapper.findById(id).orElse(null);
        if (existing == null) {
            log.info("권한 삭제 - 이미 없는 권한이라 아무것도 하지 않음: id={}", id);
            return;
        }
        if (isSysAdmin(existing)) {
            log.warn("권한 삭제 실패 - SYS_ADMIN 삭제 시도: id={}", id);
            throw new AdminAuthorityConflictException(ERR_IMMUTABLE);
        }
        adminAuthorityMapper.deleteUsersByAuthorityId(id);
        adminAuthorityMapper.deleteMenusByAuthorityId(id);
        adminAuthorityMapper.deleteById(id);
    }

    // ---- 매핑 교체 -----------------------------------------------------

    /** {@code users}가 null이면 건드리지 않고, 빈 목록이면 비운다. 목록에 없는 기존 행은 삭제, 나머지는 upsert. */
    private void replaceUsers(String id, List<AdminAuthorityUser> users, LocalDateTime now) {
        if (users == null) {
            return;
        }
        List<String> userIds = users.stream().map(AdminAuthorityUser::getId).toList();
        if (userIds.isEmpty()) {
            adminAuthorityMapper.deleteUsersByAuthorityId(id);
            return;
        }
        adminAuthorityMapper.deleteUsersNotIn(id, userIds);
        for (AdminAuthorityUser user : users) {
            AuthorityUser row = new AuthorityUser(user.getId(), id, resolveStart(user), resolveEnd(user));
            row.setCreatedAt(now);
            row.setCreatorId(SYSTEM_USER_ID);
            adminAuthorityMapper.upsertUser(row);
        }
    }

    /** {@code menus}가 null이면 건드리지 않는다. RBAC 값이 0인 항목은 행을 저장하지 않는다(행이 없는 것이 접근권 없음). */
    private void replaceMenus(String id, List<AdminAuthorityMenu> menus, LocalDateTime now) {
        if (menus == null) {
            return;
        }
        Map<String, Integer> grants = new LinkedHashMap<>();
        for (AdminAuthorityMenu menu : menus) {
            int rbac = Rbac.fromMap(menu.getAuthorities());
            if (rbac != 0) {
                grants.put(menu.getId(), rbac);
            }
        }
        if (grants.isEmpty()) {
            adminAuthorityMapper.deleteMenusByAuthorityId(id);
            return;
        }
        adminAuthorityMapper.deleteMenusNotIn(id, List.copyOf(grants.keySet()));
        for (Map.Entry<String, Integer> grant : grants.entrySet()) {
            AuthorityMenu row = new AuthorityMenu(id, grant.getKey(), grant.getValue());
            row.setCreatedAt(now);
            row.setCreatorId(SYSTEM_USER_ID);
            adminAuthorityMapper.upsertMenu(row);
        }
    }

    // ---- 검증 ----------------------------------------------------------

    private void validate(AdminAuthorityPersistRequest request) {
        if (isBlank(request.getRole()) || isBlank(request.getName()) || isBlank(request.getType())
                || request.getRole().length() > MAX_ROLE_LENGTH
                || request.getName().length() > MAX_NAME_LENGTH
                || (request.getRemarks() != null && request.getRemarks().length() > MAX_REMARKS_LENGTH)
                || (request.getUse() != null && !USE_VALUES.contains(request.getUse()))) {
            log.warn("권한 저장 검증 실패 - 필수값 누락/길이/use 값 오류: role={}", request.getRole());
            throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
        }
        try {
            AuthorityType.fromCode(request.getType());
        } catch (IllegalArgumentException e) {
            log.warn("권한 저장 검증 실패 - 알 수 없는 type: {}", request.getType());
            throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
        }
        validateUsers(request.getUsers());
        validateMenus(request.getMenus());
    }

    private void validateUsers(List<AdminAuthorityUser> users) {
        if (users == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (AdminAuthorityUser user : users) {
            if (user == null || isBlank(user.getId()) || !seen.add(user.getId())
                    || resolveEnd(user).isBefore(resolveStart(user))) {
                log.warn("권한 저장 검증 실패 - users 항목 오류(빈 id/중복/적용기간 역전): {}",
                        user == null ? null : user.getId());
                throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
            }
        }
        if (!seen.isEmpty() && adminAuthorityMapper.findExistingUserIds(List.copyOf(seen)).size() != seen.size()) {
            log.warn("권한 저장 검증 실패 - 존재하지 않는 사용자 id가 포함됨");
            throw new AdminAuthorityValidationException(ERR_USER_NOT_FOUND);
        }
    }

    private void validateMenus(List<AdminAuthorityMenu> menus) {
        if (menus == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (AdminAuthorityMenu menu : menus) {
            if (menu == null || isBlank(menu.getId()) || menu.getAuthorities() == null || !seen.add(menu.getId())) {
                log.warn("권한 저장 검증 실패 - menus 항목 오류(빈 id/중복/authorities 없음): {}",
                        menu == null ? null : menu.getId());
                throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
            }
            for (Map.Entry<String, Boolean> entry : menu.getAuthorities().entrySet()) {
                // Rbac.fromMap은 알 수 없는 키에서 IllegalArgumentException을 던져 500이 되므로 먼저 걸러 400으로 바꾼다.
                if (entry.getValue() == null || !isRbacCode(entry.getKey())) {
                    log.warn("권한 저장 검증 실패 - 알 수 없는 RBAC 키: menuId={}, key={}", menu.getId(), entry.getKey());
                    throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
                }
            }
        }
        if (!seen.isEmpty() && adminAuthorityMapper.findExistingMenuIds(List.copyOf(seen)).size() != seen.size()) {
            log.warn("권한 저장 검증 실패 - 존재하지 않는 메뉴 id가 포함됨");
            throw new AdminAuthorityValidationException(ERR_MENU_NOT_FOUND);
        }
    }

    private void checkDuplicate(AuthorityType type, String role, String excludeId) {
        adminAuthorityMapper.findByTypeAndRole(type, role).ifPresent(found -> {
            if (!found.getId().equals(excludeId)) {
                log.warn("권한 저장 실패 - 같은 유형/role이 이미 있음: type={}, role={}", type, role);
                throw new AdminAuthorityConflictException(ERR_DUPLICATE);
            }
        });
    }

    // ---- 헬퍼 ----------------------------------------------------------

    private AuthorityBase findExisting(String id) {
        return adminAuthorityMapper.findById(id).orElseThrow(() -> {
            log.warn("권한을 찾을 수 없음: id={}", id);
            return new AdminAuthorityNotFoundException(ERR_NOT_FOUND);
        });
    }

    private static boolean isSysAdmin(AuthorityBase authority) {
        return authority.getType() == AuthorityType.ROLE && Constants.SYS_ADMIN.equals(authority.getRole());
    }

    private static boolean isRbacCode(String key) {
        return Arrays.stream(Rbac.values()).anyMatch(rbac -> rbac.getCode().equals(key));
    }

    private static LocalDate resolveStart(AdminAuthorityUser user) {
        return user.getApplyStartDate() != null ? user.getApplyStartDate() : LocalDate.now();
    }

    private static LocalDate resolveEnd(AdminAuthorityUser user) {
        return user.getApplyEndDate() != null ? user.getApplyEndDate() : OPEN_ENDED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static AdminAuthority toContent(AuthorityBase row, List<AdminAuthorityUser> users) {
        return new AdminAuthority(row.getId(), row.getRole(), row.getType().getCode(), row.getName(),
                row.getRemarks(), row.getUse(), users);
    }
}
```

- [ ] **Step 7: 서비스 테스트 통과 확인**

Run: `cd kkdugi-admin; ./mvnw.cmd -B -ntp test "-Dtest=AdminAuthorityServiceTest"`
Expected: `Tests run: 16, Failures: 0, Errors: 0` / `BUILD SUCCESS`.

실패 시 점검 순서: (a) `Constants` 임포트가 `kkdugi.core.Constants`인지, (b) `Rbac.fromMap`은 `Map<String, Boolean>`을 받는지(`Rbac.java` 확인), (c) `count("SELECT auth_val ...")`가 `Integer` 한 값을 돌려주는지, (d) `Page`의 getter 이름(`getTotalItems` 등 — `Page.java`는 Lombok `@Getter`).

- [ ] **Step 8: 전체 회귀 확인**

Run: `cd kkdugi-admin; ./mvnw.cmd -B -ntp test`
Expected: 전체 `BUILD SUCCESS`. (`messages*.properties`를 건드렸으므로 `KkdugiMessageSourceTest`가 포함되는지 확인.)

- [ ] **Step 9: 커밋**

```bash
git add kkdugi-admin/src/main/java/kkdugi/app/admin/authority \
        kkdugi-admin/src/main/resources/messages \
        kkdugi-admin/src/test/java/kkdugi/app/admin/authority/service
git commit -m "feat(authority): add AdminAuthorityService with full-replace save and SYS_ADMIN protection"
```

---

### Task 3: API — `AdminAuthorityController`

**Files:**
- Create: `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/models/AdminAuthorityCandidateQuery.java`
- Create: `kkdugi-admin/src/main/java/kkdugi/api/admin/AdminAuthorityController.java`
- Test: `kkdugi-admin/src/test/java/kkdugi/api/admin/AdminAuthorityControllerTest.java`

**Interfaces:**
- Consumes: Task 2의 `AdminAuthorityService` 7개 메서드, 예외 3종(`getCode()`), 응답/요청 모델, `kkdugi.core.exceptions.ExceptionMessage(String code, Object... args)`.
- Produces: HTTP 엔드포인트(경로 표는 [설계 문서](../../authority-system-design.md#엔드포인트)) — 에러 본문은 `{ "code": "...", "message": "..." }`(`ExceptionMessage`), 상태 400/404/409.

- [ ] **Step 1: 실패하는 컨트롤러 테스트 작성**

Create `kkdugi-admin/src/test/java/kkdugi/api/admin/AdminAuthorityControllerTest.java`:

```java
package kkdugi.api.admin;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

import kkdugi.KkdugiAdminApplication;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class AdminAuthorityControllerTest {

    private static final String URL = "/api/v1.0/admin/authority";
    private static final String ROLE_PREFIX = "TEST_AUTHZ_API_";
    private static final String USER_1 = "U_TEST_AUTHZ_API_1";
    private static final String USER_2 = "U_TEST_AUTHZ_API_2";
    private static final String MENU_1 = "M_TEST_AUTHZ_API_1";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        wipe();
        insertUser(USER_1, "test_authz_api_one", "Api One");
        insertUser(USER_2, "test_authz_api_two", "Api Two");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_base (menu_id, menu_pgm, sort_seq, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "authz_api_1", 1, "SYSTEM");
        jdbcTemplate.update(
                "INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, reg_id) VALUES (?, ?, ?, ?)",
                MENU_1, "ko_KR", "API 테스트 메뉴", "SYSTEM");
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        String ids = "SELECT auth_id FROM kkdugi_auth_base WHERE auth_role_cd LIKE '" + ROLE_PREFIX + "%'";
        jdbcTemplate.update("DELETE FROM kkdugi_auth_menu WHERE auth_id IN (" + ids + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_user_auth WHERE auth_id IN (" + ids + ")");
        jdbcTemplate.update("DELETE FROM kkdugi_auth_base WHERE auth_role_cd LIKE '" + ROLE_PREFIX + "%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_lang WHERE menu_id LIKE 'M_TEST_AUTHZ_API_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_menu_base WHERE menu_id LIKE 'M_TEST_AUTHZ_API_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_user_base WHERE user_id LIKE 'U_TEST_AUTHZ_API_%'");
    }

    private void insertUser(String id, String loginId, String name) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_user_base (user_id, user_login_id, user_nm, user_email, user_stat_cd, reg_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, loginId, name, loginId + "@example.com", "20", "SYSTEM");
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

    private Map<String, Object> fullBody(String roleSuffix) {
        return map("role", ROLE_PREFIX + roleSuffix, "type", "ROLE", "name", "API " + roleSuffix,
                "remarks", "r", "use", "Y",
                "users", List.of(map("id", USER_1, "applyStartDate", "2030-01-01", "applyEndDate", "2030-12-31")),
                "menus", List.of(map("id", MENU_1,
                        "authorities", map("10", true, "20", false, "30", false, "40", false))));
    }

    /** regist를 호출하고 생성된 권한 id를 돌려준다. */
    private String regist(Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    @Test
    void regist_returnsCreatedAuthority_withObjectUsers_andHidesAuditFields() throws Exception {
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(json(fullBody("ONE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.role").value(ROLE_PREFIX + "ONE"))
                .andExpect(jsonPath("$.type").value("ROLE"))
                .andExpect(jsonPath("$.name").value("API ONE"))
                .andExpect(jsonPath("$.remarks").value("r"))
                .andExpect(jsonPath("$.use").value("Y"))
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].id").value(USER_1))
                .andExpect(jsonPath("$.users[0].applyStartDate").value("2030-01-01"))
                .andExpect(jsonPath("$.users[0].applyEndDate").value("2030-12-31"))
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.creatorId").doesNotExist())
                .andExpect(jsonPath("$.totalSize").doesNotExist());
    }

    @Test
    void get_returnsDetailWithUsers_and404ForUnknownId() throws Exception {
        String id = regist(fullBody("GET"));

        mockMvc.perform(get(URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.users[0].id").value(USER_1));

        mockMvc.perform(get(URL + "/A_TEST_AUTHZ_API_MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("authority.err.not_found"));
    }

    @Test
    void list_returnsPageWithoutUsers() throws Exception {
        regist(fullBody("L1"));
        regist(fullBody("L2"));
        regist(fullBody("L3"));

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .content(json(map("role", ROLE_PREFIX, "page", 1, "pageSize", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.contents.length()").value(2))
                .andExpect(jsonPath("$.contents[0].role").value(ROLE_PREFIX + "L1"))
                .andExpect(jsonPath("$.contents[0].users").doesNotExist());

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .content(json(map("role", ROLE_PREFIX + "L2", "type", "ROLE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(200))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void save_replacesFields_andUsers() throws Exception {
        String id = regist(fullBody("SAVE"));

        Map<String, Object> body = fullBody("SAVE");
        body.put("id", "IGNORED_BODY_ID");
        body.put("name", "Renamed");
        body.put("users", List.of(map("id", USER_2)));
        mockMvc.perform(post(URL + "/" + id).contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].id").value(USER_2))
                .andExpect(jsonPath("$.users[0].applyEndDate").value("9999-12-31"));

        mockMvc.perform(post(URL + "/A_TEST_AUTHZ_API_MISSING").contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_removesAuthority_andIsIdempotent() throws Exception {
        String id = regist(fullBody("DEL"));

        mockMvc.perform(post(URL + "/" + id + "/delete")).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/" + id + "/delete")).andExpect(status().isOk());
    }

    @Test
    void sysAdmin_deleteIsRejectedWith409() throws Exception {
        String sysAdminId = jdbcTemplate.queryForObject(
                "SELECT auth_id FROM kkdugi_auth_base WHERE auth_tp_cd = 'ROLE' AND auth_role_cd = 'SYS_ADMIN'",
                String.class);

        mockMvc.perform(post(URL + "/" + sysAdminId + "/delete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("authority.err.immutable"));
    }

    @Test
    void regist_returns400_forMalformedRequest_and409_forDuplicate() throws Exception {
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("role", ROLE_PREFIX + "BAD", "type", "BOGUS", "name", "n"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("authority.err.malformed_request"));

        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON)
                        .content(json(map("role", ROLE_PREFIX + "BAD2", "type", "ROLE", "name", "n",
                                "users", List.of(map("id", "U_TEST_AUTHZ_API_MISSING"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("authority.err.user_not_found"));

        regist(fullBody("DUP"));
        mockMvc.perform(post(URL + "/regist").contentType(APPLICATION_JSON).content(json(fullBody("DUP"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("authority.err.duplicate"));
    }

    @Test
    void candidates_listsUnmappedNormalUsers_matchingTheQuery() throws Exception {
        String id = regist(fullBody("CAND"));

        mockMvc.perform(post(URL + "/" + id + "/user").contentType(APPLICATION_JSON)
                        .content(json(map("query", "TEST_AUTHZ_API"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(USER_2))
                .andExpect(jsonPath("$[0].username").value("test_authz_api_two"))
                .andExpect(jsonPath("$[0].name").value("Api Two"));

        // 본문 없이도 호출할 수 있다.
        mockMvc.perform(post(URL + "/" + id + "/user")).andExpect(status().isOk());

        mockMvc.perform(post(URL + "/A_TEST_AUTHZ_API_MISSING/user").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void menus_returnsTreeWithRbacMapKeyedByCode() throws Exception {
        String id = regist(fullBody("MENU"));

        mockMvc.perform(post(URL + "/" + id + "/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + MENU_1 + "')].authorities['10']").value(true))
                .andExpect(jsonPath("$[?(@.id=='" + MENU_1 + "')].authorities['20']").value(false))
                .andExpect(jsonPath("$[?(@.id=='" + MENU_1 + "')].locale.ko_KR.label").value("API 테스트 메뉴"))
                .andExpect(jsonPath("$[?(@.id=='" + MENU_1 + "')].program").value("authz_api_1"));

        mockMvc.perform(post(URL + "/A_TEST_AUTHZ_API_MISSING/menu")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd kkdugi-admin; ./mvnw.cmd -B -ntp test "-Dtest=AdminAuthorityControllerTest"`
Expected: 컴파일은 되고(컨트롤러 없이도 테스트 자체는 컴파일된다) 각 테스트가 `Status expected:<200> but was:<404>`(매핑 없음)로 FAIL.

- [ ] **Step 3: 후보 조회 요청 모델 작성**

Create `kkdugi-admin/src/main/java/kkdugi/app/admin/authority/models/AdminAuthorityCandidateQuery.java`:

```java
package kkdugi.app.admin.authority.models;

import lombok.Getter;
import lombok.Setter;

/**
 * 후보 사용자 조회 요청 본문 {@code {"query": "..."}}. 필드가 하나뿐인 all-args
 * 생성자는 Jackson이 속성 생성자인지 위임 생성자인지 모호해하므로 이 클래스만
 * 기본 생성자 + setter로 둔다.
 */
@Getter
@Setter
public class AdminAuthorityCandidateQuery {

    private String query;
}
```

- [ ] **Step 4: 컨트롤러 작성**

Create `kkdugi-admin/src/main/java/kkdugi/api/admin/AdminAuthorityController.java`:

```java
package kkdugi.api.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.admin.authority.exceptions.AdminAuthorityConflictException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityNotFoundException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityValidationException;
import kkdugi.app.admin.authority.models.AdminAuthority;
import kkdugi.app.admin.authority.models.AdminAuthorityCandidate;
import kkdugi.app.admin.authority.models.AdminAuthorityCandidateQuery;
import kkdugi.app.admin.authority.models.AdminAuthorityMenuNode;
import kkdugi.app.admin.authority.models.AdminAuthorityParams;
import kkdugi.app.admin.authority.models.AdminAuthorityPersistRequest;
import kkdugi.app.admin.authority.service.AdminAuthorityService;
import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.models.Page;

@RestController
@RequestMapping("/api/v1.0/admin/authority")
public class AdminAuthorityController {

    private final AdminAuthorityService service;

    public AdminAuthorityController(AdminAuthorityService service) {
        this.service = service;
    }

    @PostMapping
    public Page<AdminAuthority> search(@RequestBody AdminAuthorityParams params) {
        return service.search(params);
    }

    @GetMapping("/{id}")
    public AdminAuthority get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @PostMapping("/regist")
    public AdminAuthority regist(@RequestBody AdminAuthorityPersistRequest request) {
        return service.regist(request);
    }

    @PostMapping("/{id}")
    public AdminAuthority save(@PathVariable("id") String id, @RequestBody AdminAuthorityPersistRequest request) {
        return service.save(id, request);
    }

    @PostMapping("/{id}/delete")
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }

    @PostMapping("/{id}/user")
    public List<AdminAuthorityCandidate> candidates(@PathVariable("id") String id,
            @RequestBody(required = false) AdminAuthorityCandidateQuery request) {
        return service.searchCandidates(id, request == null ? null : request.getQuery());
    }

    @PostMapping("/{id}/menu")
    public List<AdminAuthorityMenuNode> menus(@PathVariable("id") String id) {
        return service.menus(id);
    }

    @ExceptionHandler(AdminAuthorityValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ExceptionMessage handleValidation(AdminAuthorityValidationException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminAuthorityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ExceptionMessage handleNotFound(AdminAuthorityNotFoundException e) {
        return new ExceptionMessage(e.getCode());
    }

    @ExceptionHandler(AdminAuthorityConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ExceptionMessage handleConflict(AdminAuthorityConflictException e) {
        return new ExceptionMessage(e.getCode());
    }
}
```

- [ ] **Step 5: 컨트롤러 테스트 통과 확인**

Run: `cd kkdugi-admin; ./mvnw.cmd -B -ntp test "-Dtest=AdminAuthorityControllerTest"`
Expected: `Tests run: 9, Failures: 0, Errors: 0` / `BUILD SUCCESS`.

실패 시 점검:
- `applyStartDate`가 `"2030-01-01"`이 아니라 숫자 배열/타임스탬프로 나오면 Jackson 3의 날짜 직렬화 설정 문제 — `application.yml`의 `spring.jackson.*`를 확인하고, 필요하면 `AdminAuthorityUser`의 두 필드에 `@JsonFormat(pattern = "yyyy-MM-dd")`를 붙인다(그 경우 스펙 문서에 사유 기록).
- `POST /regist`가 `/{id}`로 라우팅되면(`role` 관련 404) Spring의 리터럴 우선 매칭 가정이 깨진 것 — 경로 매핑을 확인한다.
- 요청 본문 역직렬화가 400(`HttpMessageNotReadableException`)이면 `AdminAuthorityPersistRequest`의 다중 인자 생성자가 인식되지 않은 것 — `AdminMenuPersistRequest`가 같은 패턴이므로 컴파일 `-parameters` 설정을 비교한다.

- [ ] **Step 6: 전체 회귀 확인**

Run: `cd kkdugi-admin; ./mvnw.cmd -B -ntp test`
Expected: 전체 `BUILD SUCCESS`.

- [ ] **Step 7: 커밋**

```bash
git add kkdugi-admin/src/main/java/kkdugi/api/admin/AdminAuthorityController.java \
        kkdugi-admin/src/main/java/kkdugi/app/admin/authority/models/AdminAuthorityCandidateQuery.java \
        kkdugi-admin/src/test/java/kkdugi/api/admin/AdminAuthorityControllerTest.java
git commit -m "feat(authority): add AdminAuthorityController (/api/v1.0/admin/authority)"
```

---

### Task 4: 문서 갱신과 최종 검증

**Files:**
- Create: `docs/api/authority.md`
- Create: `docs/adr/0017-authority-management-system.md`
- Modify: `docs/api/README.md`, `docs/adr/README.md`, `docs/authority-system-design.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: Task 1~3의 최종 동작(엔드포인트, 에러 코드, 규칙).
- Produces: 살아있는 API 문서 `docs/api/authority.md`(이후 사용자 관리 작업이 참조).

- [ ] **Step 1: API 문서 작성**

Create `docs/api/authority.md`:

````markdown
# 권한 관리 API

- 구현: `kkdugi.api.admin.AdminAuthorityController` → `AdminAuthorityService` → `AdminAuthorityMapper`
- 설계 근거: [authority-system-design.md](../authority-system-design.md), [ADR-0017](../adr/0017-authority-management-system.md)
- 원본 스펙(아카이브, 이 문서와 다르면 이 문서가 맞다): [api-define-admin.md](../archive/api-define-admin.md) 4절

## 공통

- Base URL: `/api/v1.0/admin/authority`
- **접근 제어는 아직 없다.** `SecurityConfigurer`가 전부 `permitAll`이라 401/403은 발생하지 않는다(메뉴 관리 API와 같은 상태 — [README](README.md) 참고).
- **권한 변경은 이미 로그인된 세션에 즉시 반영되지 않는다.** 다음 로그인부터 적용된다.
- **RBAC 맵 키**는 `Rbac` 숫자 코드다: `"10"` 조회, `"20"` 등록, `"30"` 삭제, `"40"` 실행. (원본 스펙의 `READ`/`WRITE` 표기는 쓰지 않는다.)
- **`users`는 항상 객체** `{ "id", "applyStartDate", "applyEndDate" }`, 날짜는 `yyyy-MM-dd`. 요청에서 날짜를 생략하면 시작=오늘, 종료=`9999-12-31`.
- **에러 본문**: `{ "code": "authority.err.…", "message": "…" }`.

| 상태 | code | 의미 |
|---|---|---|
| 400 | `authority.err.malformed_request` | 필수값(role/type/name) 누락·길이 초과, 알 수 없는 type/use, `users`/`menus` 항목 오류(빈 id, 중복 id, 적용기간 역전, 알 수 없는 RBAC 키) |
| 400 | `authority.err.user_not_found` | `users`에 존재하지 않는 사용자 id |
| 400 | `authority.err.menu_not_found` | `menus`에 존재하지 않는 메뉴 id |
| 404 | `authority.err.not_found` | 대상 권한 없음 |
| 409 | `authority.err.duplicate` | 같은 `type` 안에서 `role`이 이미 있음 |
| 409 | `authority.err.immutable` | `SYS_ADMIN`(ROLE) 삭제/role·type 변경/비활성화 시도 |

## 1. 권한 목록 — `POST /api/v1.0/admin/authority`

```javascript
Request
{ "role"?: "부분 일치", "type"?: "ROLE | PLAN", "name"?: "부분 일치", "page": 1, "pageSize": 200 }

Response  // kkdugi.core.models.Page — users는 포함되지 않는다
{
  "page": 1, "pageSize": 200, "totalItems": 1, "totalPages": 1,
  "contents": [
    { "id": "A2026091908110001", "role": "SYS_ADMIN", "type": "ROLE",
      "name": "시스템 관리자", "remarks": null, "use": "Y" }
  ]
}
```

`page` 기본 1, `pageSize` 기본/최대 200. 정렬은 `role`, `id` 순.

## 2. 권한 상세 — `GET /api/v1.0/admin/authority/{id}`

```javascript
Response
{
  "id": "A2026091908110001", "role": "SYS_ADMIN", "type": "ROLE",
  "name": "시스템 관리자", "remarks": null, "use": "Y",
  "users": [ { "id": "U2026091508020001", "applyStartDate": "2026-09-19", "applyEndDate": "9999-12-31" } ]
}
```

없으면 404.

## 3. 권한 등록 — `POST /api/v1.0/admin/authority/regist`

```javascript
Request  // id는 보내지 않는다(서버 채번, 접두사 A). users/menus는 생략 가능
{
  "role": "OPERATOR", "type": "ROLE", "name": "운영자", "remarks": "…", "use": "Y",
  "users": [ { "id": "U2026091508020001", "applyStartDate": "2026-10-01", "applyEndDate": "2026-12-31" } ],
  "menus": [ { "id": "M2026080102030001", "authorities": { "10": true, "20": false, "30": false, "40": false } } ]
}

Response  // 상세(2)와 같은 형태
```

`use` 생략 시 `Y`. `type`이 `ROLE`/`PLAN`이 아니면 400.

## 4. 권한 저장 — `POST /api/v1.0/admin/authority/{id}`

요청/응답 형태는 등록과 같다(본문의 `id`는 무시하고 경로의 id를 쓴다). **저장은 전체 교체**다.

- `users`/`menus` 필드가 **없으면(null)** 그 매핑은 건드리지 않는다.
- **빈 배열이면** 그 매핑을 전부 비운다.
- 목록이 있으면 그 목록으로 교체한다(목록에 없는 기존 행은 삭제, 나머지는 upsert).
- `use`를 생략하면 기존 값을 유지한다.
- `menus`에서 네 RBAC가 모두 `false`인 항목은 저장하지 않는다(행이 없는 것이 접근권 없음).
- `SYS_ADMIN`(ROLE)은 이름/설명만 바꿀 수 있다. role·type 변경이나 `use: "N"`은 409.

## 5. 권한 삭제 — `POST /api/v1.0/admin/authority/{id}/delete`

권한과 그 사용자 매핑·메뉴 매핑을 한 트랜잭션으로 삭제한다(사용자/메뉴 자체는 삭제되지 않는다). 이미 없는 id는 200으로 아무것도 하지 않는다. `SYS_ADMIN`은 409.

> 원본 스펙의 경로는 `authroity` 오타였다. 구현은 `authority`다.

## 6. 후보 사용자 조회 — `POST /api/v1.0/admin/authority/{id}/user`

권한에 붙일 수 있는 사용자 — 상태가 정상(`20`)이고 이 권한에 아직 매핑되지 않은 사용자 — 를 조회한다.

```javascript
Request  // 본문 자체를 생략해도 된다
{ "query"?: "로그인 ID / 이름 / 이메일에 대한 대소문자 무시 부분 일치" }

Response
[ { "id": "U2026091509120001", "username": "admin", "name": "관리자", "image": null } ]
```

이름 순으로 최대 200명까지 돌려준다(초과분은 `query`로 좁힌다). 권한이 없으면 404.

## 7. 권한 기준 메뉴 트리 — `POST /api/v1.0/admin/authority/{id}/menu`

모든 메뉴를 트리(`children` 중첩)로 돌려주고, 각 노드에 이 권한이 갖는 RBAC를 싣는다. 부여가 없는 메뉴는 전부 `false`.

```javascript
Response
[
  {
    "id": "M2026091517570001", "parentId": null,
    "locale": { "ko_KR": { "label": "시스템 관리", "remarks": null } },
    "icon": "…", "program": "admin/menu", "use": "Y",
    "path": "/M2026091517570001", "level": 0, "sort": 1,
    "authorities": { "10": true, "20": false, "30": false, "40": false },
    "children": [ … ]
  }
]
```

권한이 없으면 404.

## 알려진 한계

- 메뉴 관리에서 메뉴를 삭제할 때 `kkdugi_auth_menu`를 정리하지 않는다. 어떤 권한에 부여된 메뉴를 삭제하면 FK 위반(500)이 난다 — 후속 작업으로 메뉴 삭제 시 매핑 연쇄 삭제가 필요하다.
- 사용자 관리(원본 스펙 5.5/5.6)가 구현되면 `kkdugi_user_auth`를 쓰는 경로가 두 곳(권한 저장, 사용자의 권한 목록 저장)이 된다 — 그때 쓰기 경로를 정리한다.
````

- [ ] **Step 2: ADR 작성**

Create `docs/adr/0017-authority-management-system.md`:

````markdown
# ADR-0017: 권한 관리 시스템 — 단일 기능 패키지, 전체 교체 저장, SYS_ADMIN 보호

- 상태: 채택 (2026-09-19)
- 관련: [ADR-0016](0016-app-and-admin-feature-split.md), [ADR-0012](0012-common-code-system.md)(채번), 설계 문서 [authority-system-design.md](../authority-system-design.md), 원본 스펙 [archive/api-define-admin.md](../archive/api-define-admin.md) 4절

## 배경

아카이브된 스펙 4절(권한 관리)은 살아있는 계약이 아니라서 착수 시점에 오너와 범위와 모호한 부분을 확정했다. 테이블(`kkdugi_auth_base`, `kkdugi_user_auth`, `kkdugi_auth_menu`)과 세션 쿼리는 이미 있었고, 스펙에는 RBAC 키 표기(`READ`/`WRITE` vs `Rbac` 숫자 코드)와 `users` 형태(ID 문자열 vs 객체)가 엇갈려 있었다.

## 결정

1. **범위는 스펙 4절 전부**: 권한 CRUD, 메뉴 RBAC 매핑, 사용자 매핑, 후보 사용자 조회. 사용자 도메인이 아직 없으므로 `kkdugi_user_base`는 읽기 전용, `kkdugi_user_auth`는 권한 쪽에서 쓴다.
2. **한 기능 패키지** `app.admin.authority`(mapper/service 하나)로 만든다. 권한 저장이 세 테이블을 한 트랜잭션으로 쓰기 때문에 기능을 쪼개면 트랜잭션 경계가 흐려진다.
3. **RBAC 맵 키는 `Rbac` 숫자 코드** `"10"`~`"40"`. 기존 `Rbac.toMap`, Pragma, session 문서와 같은 형태라 변환 계층이 필요 없다.
4. **`users`는 어디서나 `{id, applyStartDate, applyEndDate}` 객체**. 컬럼이 `NOT NULL DATE`라 ID만으로는 저장할 수 없다. 생략 시 시작=오늘, 종료=`9999-12-31`.
5. **저장은 전체 교체**: 필드가 null이면 그 매핑은 그대로, 빈 배열이면 비움, 목록이 있으면 교체(없는 행 삭제 + 나머지 upsert로 등록 감사 정보 보존).
6. **`SYS_ADMIN`(ROLE) 보호**: 삭제·role/type 변경·비활성화는 409. `KkdugiUserDetailsService`가 이 role로 메뉴 우회를 판단하므로 바꾸면 전원이 잠길 수 있다.
7. **`(type, role)` 유일성**: 서비스에서 사전 검사(409) + `V11` 유니크 제약으로 동시 요청 경쟁을 막는다. `SessionUtils`의 role 기반 판단이 모호해지면 안 된다.
8. **`auth_val`이 0인 매핑은 저장하지 않는다**(행 없음 = 접근권 없음).
9. **알 수 없는 RBAC 키는 400**: `Rbac.fromMap`이 알 수 없는 키에서 `IllegalArgumentException`을 던져 그대로 두면 500이 된다.
10. **범위 밖**: 접근 제어(`permitAll` 유지), 세션 즉시 반영(다음 로그인부터 적용), 사용자 관리, 프런트엔드.

## 결과

- 권한 API가 생겨 `auth_menu` 행이 처음으로 만들어질 수 있다. 메뉴 삭제(`AdminMenuService`)가 `kkdugi_auth_menu`를 정리하지 않으므로, 부여된 메뉴를 삭제하면 FK 위반이 난다 — 후속 작업.
- 사용자 관리(5.5/5.6) 구현 시 `kkdugi_user_auth`의 쓰기 경로가 둘이 된다 — 그때 한쪽으로 정리한다.
- 후보 사용자 조회는 스펙에 페이징이 없어 최대 200명으로 제한했다(초과분은 `query`로 좁힌다).
````

- [ ] **Step 3: 인덱스/목차 갱신**

Edit `docs/adr/README.md` — `old_string`:

```
| [0016](0016-app-and-admin-feature-split.md) | 사용자용(app.<기능>)과 관리자용(app.admin.<기능>) 기능 분리, 세션 메뉴 /api/v1.0/menu 이전 | Accepted |
```

`new_string`:

```
| [0016](0016-app-and-admin-feature-split.md) | 사용자용(app.<기능>)과 관리자용(app.admin.<기능>) 기능 분리, 세션 메뉴 /api/v1.0/menu 이전 | Accepted |
| [0017](0017-authority-management-system.md) | 권한 관리 시스템: 단일 기능 패키지, 전체 교체 저장, SYS_ADMIN 보호, RBAC 숫자 코드 키 | Accepted |
```

Edit `docs/api/README.md` — `old_string`:

```
| [menu.md](menu.md) | 메뉴 관리 — 전체 트리 조회/저장(SYS_ADMIN 전용, 계층형) |
```

`new_string`:

```
| [menu.md](menu.md) | 메뉴 관리 — 전체 트리 조회/저장(SYS_ADMIN 전용, 계층형) |
| [authority.md](authority.md) | 권한 관리 — 권한 CRUD, 메뉴 RBAC 매핑, 사용자 매핑, 후보 사용자 조회 |
```

같은 파일 상단 문단 — `old_string`: `현재 API의 유일한 살아있는 근거다. 아직 구현되지 않은 권한/사용자 스펙은` → `new_string`: `현재 API의 유일한 살아있는 근거다. 아직 구현되지 않은 사용자 스펙은`. 그리고 `old_string`: `현재 `SecurityConfigurer`의 인가 규칙은 전부 `permitAll`이다 — 사용자/권한
  관리 API([archive/api-define-admin.md](../archive/api-define-admin.md) 4~5절)가 아직 구현되지 않아 실제로 무엇을
  막아야 하는지가 정해지지 않았기 때문이며,` → `new_string`: `현재 `SecurityConfigurer`의 인가 규칙은 전부 `permitAll`이다 — 사용자 관리 API([archive/api-define-admin.md](../archive/api-define-admin.md) 5절)가 아직 구현되지 않았고, 권한 관리 API([authority.md](authority.md))도 접근 제어 없이 먼저 구현돼 실제로 무엇을 막아야 하는지가 정해지지 않았기 때문이며,`. (정확한 줄바꿈이 다르면 `Read`로 해당 문단을 다시 읽고 같은 의미로 고친다.)

- [ ] **Step 4: 설계 문서 상태와 보완 사항 갱신**

Edit `docs/authority-system-design.md`:

1. `old_string`: `- 상태: 설계 승인됨, 구현 전` → `new_string`: `- 상태: 구현 완료 (2026-09-19) — 구현 후 API 문서는 [api/authority.md](api/authority.md), 결정 기록은 [ADR-0017](adr/0017-authority-management-system.md)`
2. `old_string`: `6. **후보 사용자 조회는 읽기 전용**이다(`kkdugi_user_base`를 조회만 함).
   비밀번호 등 민감 컬럼은 select하지 않는다.` → `new_string`: 같은 문장 뒤에 이어서 `스펙에 페이징이 없어 이름 순으로 최대 200명까지만 돌려준다(초과분은 `query`로 좁힌다).`를 덧붙인다.
3. "범위 밖 (알려진 한계)" 목록 끝에 항목 추가: `- **메뉴 삭제 시 `kkdugi_auth_menu` 미정리.** `AdminMenuService.deleteOne`이 권한-메뉴 매핑을 지우지 않아, 권한에 부여된 메뉴를 삭제하면 FK 위반(500)이 난다. 이 작업이 `auth_menu` 행을 만들 수 있게 한 첫 기능이라 드러난 것으로, 후속 작업에서 메뉴 삭제 시 매핑을 연쇄 삭제해야 한다.`

- [ ] **Step 5: CLAUDE.md 갱신**

Edit `CLAUDE.md`:

1. 프로젝트 개요 문단 — `old_string`: `Authority/users are still TODO
(see Scope notes)` → `new_string`: `Users are still TODO
(see Scope notes)`, 그리고 같은 문단의 `For common codes, messages, and menu, the
implemented-and-current source of truth is now
[docs/api/common-code.md](docs/api/common-code.md),
[docs/api/i18n-message.md](docs/api/i18n-message.md), and
[docs/api/menu.md](docs/api/menu.md)` → `... and
[docs/api/menu.md](docs/api/menu.md), and [docs/api/authority.md](docs/api/authority.md)` (`For common codes, messages, menu, and authority`로 문구도 맞춘다). 줄바꿈이 다르면 해당 문단을 `Read`로 확인하고 같은 의미로 고친다.
2. 패키지 트리 — `old_string`: `│       └─ i18n     — models(AdminMessage, AdminMessageParams, AdminMessagePersistRequest,` → `new_string`:

```
│       ├─ authority — models(AuthorityBase/AuthorityUser/AuthorityMenu rows, AdminAuthority,
│       │             AdminAuthorityParams, AdminAuthorityPersistRequest, AdminAuthorityUser,
│       │             AdminAuthorityMenu, AdminAuthorityMenuNode implements Tree, …),
│       │             exceptions(AdminAuthority{Validation,Conflict,NotFound}Exception),
│       │             mapper(AdminAuthorityMapper), service(AdminAuthorityService — SerialUtils.next(config))
│       └─ i18n     — models(AdminMessage, AdminMessageParams, AdminMessagePersistRequest,
```

   그리고 `│       ├─ menu     — models(AdminMenu ...` 줄의 `├─`/`└─` 연결 기호가 자연스럽게 이어지는지 확인한다(`menu`는 이미 `├─`).
3. 트리의 `api` 항목 — `AdminCodeController, AdminMenuController, AdminMessageController` → `AdminCodeController, AdminMenuController, AdminAuthorityController, AdminMessageController`, `(/api/v1.0/admin/{code,menu,i18n})` → `(/api/v1.0/admin/{code,menu,authority,i18n})`.
4. Scope notes — `Authority and users (sections 4, 5 of the same archived doc)
  are still not started — reconfirm with the owner before implementing
  against that archived spec, and reuse `SerialConfig`/`SerialUtils`/
  `fn_get_serial` for their ID prefixes (`A`/`U`).` 를 다음으로 교체:

```
Authority management (section 4 of the same archived doc) was implemented
  2026-09-19 — see [docs/api/authority.md](docs/api/authority.md),
  [docs/authority-system-design.md](docs/authority-system-design.md), and
  [ADR-0017](docs/adr/0017-authority-management-system.md). Users (section 5)
  are still not started — reconfirm with the owner before implementing
  against that archived spec, reuse `SerialConfig`/`SerialUtils`/
  `fn_get_serial` for the ID prefix (`U`), and note that `kkdugi_user_auth`
  is currently written from the authority side (see ADR-0017).
```

- [ ] **Step 6: 최종 검증 — Java 전체 + 프런트 계약 테스트**

Run (PowerShell):

```powershell
cd kkdugi-admin
./mvnw.cmd -B -ntp test
node --test src/test/js/*.test.mjs
```

Expected: Maven `BUILD SUCCESS`(전체 테스트 통과, 새 테스트 34개 = mapper 9 + service 16 + controller 9), Node 테스트 전부 pass. 하나라도 실패하면 완료가 아니다 — 원인을 고치고 다시 돌린다.

- [ ] **Step 7: 커밋**

```bash
git add docs/api/authority.md docs/api/README.md docs/adr/0017-authority-management-system.md \
        docs/adr/README.md docs/authority-system-design.md CLAUDE.md
git commit -m "docs(authority): add API doc and ADR-0017, update design status, CLAUDE.md and indexes"
```

---

## Self-Review (작성자 점검 결과)

**1. Spec coverage** — 설계 문서 각 요구사항의 담당 Task:

| 설계 요구사항 | Task |
|---|---|
| 목록/상세/등록/저장/삭제/후보 사용자/메뉴 트리 7개 엔드포인트 | 3 (컨트롤러), 2 (서비스) |
| RBAC 키 숫자 코드, `users` 객체·날짜 형식·기본 기간 | 2 (`regist_persistsUsersAndMenus…`, `menus_returnsTree…`), 3 (`regist_returnsCreated…`) |
| 규칙 1 전체 교체(null/빈 배열/목록) | 2 (`save_updatesFields_andLeavesMappingsAlone…`, `save_replacesUsersAndMenus…`) |
| 규칙 2 SYS_ADMIN 보호 | 2 (`sysAdmin_cannotBe…`), 3 (`sysAdmin_deleteIsRejectedWith409`) |
| 규칙 3 (type, role) 중복 409 + V11 | 1 (`insert_sameTypeAndRole…`), 2 (`regist_duplicate…`, `save_toAnotherAuthoritysRole…`), 3 |
| 규칙 4 검증 400 (필수값/type/없는 id/RBAC 키/중복 id/기간 역전) | 2 (`regist_rejectsMalformedRequests`, `regist_rejectsBadUsersAndMenus…`), 3 |
| 규칙 5 `auth_val=0` 미저장 | 2 (`regist_persistsUsersAndMenus…`) |
| 규칙 6 후보 조회 읽기 전용·민감 컬럼 제외 | 1 (XML은 4개 컬럼만 select), 2, 3 |
| 삭제 시 매핑 연쇄 삭제 | 2 (`delete_removesAuthorityUsersAndMenus…`) |
| 목록 `Page<T>`·정규화·`total_size` | 1 (`search_filters…`), 2 (`search_normalizesParams…`), 3 (`list_returnsPage…`) |
| 세션 즉시 반영 없음 | 4 (문서화) |
| 접근 제어 없음(범위 밖) | 4 (문서화) |
| 문서 작업(api/authority.md, ADR, CLAUDE.md, README, 설계 상태) | 4 |

설계 문서 대비 추가된 것(설계 문서 갱신은 Task 4 Step 4): 후보 조회 최대 200명 제한, 메뉴 삭제 시 `auth_menu` 미정리 한계 문서화. 두 가지 모두 구현 중 코드를 읽다 확인된 사항이다.

**2. Placeholder scan** — "TBD/TODO/적절히 처리" 없음. 모든 코드 Step에 전체 코드가 있다. Task 4 Step 3/5의 문서 Edit 중 줄바꿈이 원문과 다를 수 있는 두 곳에는 "`Read`로 확인하고 같은 의미로 고친다"는 지시를 명시했다(정확한 원문이 실행 시점의 줄바꿈에 좌우되어 사전에 고정할 수 없기 때문).

**3. Type consistency** — Task 1 Interfaces의 시그니처가 Task 2 서비스가 쓰는 것과 같다(`AuthorityBase(id, role, AuthorityType, name, remarks, use)`, `AuthorityUser(userId, authorityId, start, end)`, `AuthorityMenu(authorityId, menuId, rbac)`, mapper 메서드명/파라미터). Task 2 Interfaces의 서비스 메서드 7개와 응답 타입 getter가 Task 3 컨트롤러/테스트가 쓰는 것과 같다. 에러 코드 상수 6개는 Task 2 서비스·테스트·properties 키와 일치하고, Task 3 테스트의 `$.code` 기대값(`authority.err.*`)과도 일치한다.
