# app / api 구조 리팩터링 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자용 기능(`app.<기능>`)과 관리자용 기능(`app.admin.<기능>`)을 모델·mapper·서비스까지 분리하고, API를 `/api/v1.0/{menu,code}` + `/api/v1.0/admin/*` 구조로 재편한다.

**Architecture:** `core.{code,menu}`의 행 모델·mapper를 `app.admin.*`로 옮겨 read/write를 `Admin*Mapper` 하나로 통합하고, 사용자용 `app.code`(신규 read 전용)·`app.menu`(세션 기반)를 별도로 둔다. `app.admin.*`와 `app.*`는 서로 import하지 않고 core만 의존한다. code → menu → i18n 순으로 한 도메인씩 옮기며, 각 Task 끝에서 테스트가 통과해야 다음으로 넘어간다.

**Tech Stack:** Java 17, Spring Boot 4.0.8, MyBatis(XML mapper), PostgreSQL 17(docker-compose), Lombok, JUnit 5 + MockMvc(`@SpringBootTest`, 실제 Postgres), Node `node:test`(프런트 계약 테스트).

**Spec:** `docs/superpowers/specs/2026-09-19-app-api-structure-refactor-design.md`

## Global Constraints

- 모든 명령은 `C:\projects\kkdugi\kkdugi-admin`(Git Bash에서는 `/c/projects/kkdugi/kkdugi-admin`)에서 실행한다. 소스 루트는 `src/main/java/kkdugi`, `src/test/java/kkdugi`.
- Java 테스트: `./mvnw.cmd -B -ntp test` (단일 클래스: `./mvnw.cmd -B -ntp test -Dtest=AdminCodeServiceTest`). 실행 전 `docker ps`에 `kkdugi-dev-postgres-1`이 떠 있어야 한다. **기준선: Java 138건, JS 23건 전부 통과.**
- JS 테스트: `node --test src/test/js/*.test.mjs` (디렉터리 경로를 그대로 넘기면 실패한다 — 반드시 glob).
- Jackson 3: `tools.jackson.databind.ObjectMapper`를 import한다(`com.fasterxml...` 아님).
- record 금지. DB 행 모델은 `kkdugi.core.models.BaseModel`, 목록 검색 파라미터는 `BaseParams` 상속, 나머지는 `final` 필드 + 전체 생성자 클래스(Lombok `@Getter @AllArgsConstructor`).
- 목록 응답은 `kkdugi.core.models.Page<T>`(`T extends BaseModel`). 목록 쿼리는 `COUNT(*) OVER() AS total_size`를 select하고 resultMap에서 `totalSize`에 매핑한다. 별도 count 쿼리 금지. `Page.of(...)` 호출 전 서비스에서 `params.setPage(params.resolvedPage()); params.setPageSize(params.resolvedPageSize());`로 정규화한다.
- `BaseModel` 상속 응답 모델은 `@JsonIgnoreProperties({"rownum","createdAt","creatorId","updatedAt","updaterId"})`를 붙인다.
- JSON 필드명은 DB 컬럼명을 드러내지 않는다. enum은 `CodeEnums` + 전역 `default-enum-type-handler`(컬럼별 `typeHandler=` 금지).
- mapper XML은 mapper 인터페이스의 Java 패키지를 `src/main/resources/mapper/postgres/` 아래에 미러링한다(예: `kkdugi.app.code.mapper.CodeMapper` → `mapper/postgres/app/code/CodeMapper.xml`). `mapper-locations` glob과 `@Mapper` 스캔은 이미 재귀라 설정 변경 없음. SQL 주석의 `QueryID=`와 namespace, resultMap `type`은 새 FQCN으로 맞춘다.
- `app.admin.*`는 `kkdugi.app.<기능>.*`를 import하지 않고, `app.<기능>.*`도 `kkdugi.app.admin.*`를 import하지 않는다.
- DB 스키마 변경 없음(Flyway 마이그레이션 추가 금지). 응답 JSON 필드명·기존 `/api/v1.0/admin/*` URL은 바꾸지 않는다.
- 커밋은 브랜치 `refactor/app-structure`에 Task 단위로. 커밋 메시지 끝에 아래 두 줄을 붙인다(push는 사용자 요청이 있을 때만).
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Vb3CBKRkZDo8GoFJtAUgjT
  ```

## Spec clarifications (스펙에서 계획 단계에 확정한 사항)

1. **행 모델 이름은 유지한다.** `CodeBase`, `CodeLang`, `CodeValue`, `MenuBase`, `MenuLang`은 `app.admin.*.models`로 패키지만 옮기고 이름은 그대로 둔다(사용자용 `Code`/`Menu`와 이름이 겹치지 않는 내부 클래스라 `Admin` 접두사 불필요). 접두사는 API에 노출되거나 사용자용과 이름이 비슷한 클래스(콘텐츠/파라미터/요청/예외/서비스/mapper/컨트롤러)에만 붙인다.
2. **사용자용 `CodeMapper`는 메서드 하나**: `findChildren(parentId, path, langCode, offset, pageSize)`. 스펙의 `findByPath`는 호출부가 없으므로 별도 메서드로 만들지 않고 `path` 파라미터(부모 코드의 경로)로 흡수한다. 언어 행이 없으면 이름은 코드 값(`code_val`)으로 대체한다.
3. **프런트 경로 화이트리스트**(`static/js/api/http.mjs:8`)는 `session`을 빼고 `menu`를 추가한다(`/api/v1.0/menu`는 뒤에 `/`가 없으므로 정규식도 함께 고친다). `/api/v1.0/code`는 프런트가 아직 쓰지 않으므로 화이트리스트에 넣지 않는다.
4. **i18n의 core mapper는 축소**한다: `I18nMessageMapper`는 `MessageSource`가 쓰는 `selectAll`, `findByCodeAndLang`만 남긴다. `MessageCode`, `MessageCodeRow`는 `app.admin.i18n.models`로, `I18nMessage`는 `MessageSource`와 관리자가 함께 쓰므로 core에 남긴다. core 테스트 `KkdugiMessageSourceTest`는 픽스처를 `JdbcTemplate`으로 넣는다(core가 `app.admin`에 의존하면 안 되므로).
5. **CLAUDE.md는 낡았다**: "프로젝트 수준 mvnw 없음"은 사실이 아니다(`kkdugi-admin/mvnw.cmd` 존재). 마지막 문서 Task에서 함께 바로잡는다.

## File Structure (최종 상태)

```
src/main/java/kkdugi
├─ app
│  ├─ code
│  │  ├─ models/   Code, CodeParams                        (신규)
│  │  ├─ mapper/   CodeMapper                              (신규)
│  │  └─ service/  CodeService                             (신규)
│  ├─ menu
│  │  ├─ models/   Menu                                    (api.session.MenuTreeItem 이전)
│  │  └─ service/  MenuService                             (신규, 세션 기반)
│  └─ admin
│     ├─ code   models/{AdminCode,AdminCodeParams,AdminCodePersistRequest,AdminCodeLocale,CodeBase,CodeLang,CodeValue}
│     │         exceptions/{AdminCodeConflictException,AdminCodeValidationException}
│     │         mapper/AdminCodeMapper   service/AdminCodeService
│     ├─ menu   models/{AdminMenu,AdminMenuLocale,AdminMenuPersistRequest,MenuBase,MenuLang}
│     │         exceptions/{AdminMenuConflictException,AdminMenuValidationException}
│     │         mapper/AdminMenuMapper   service/AdminMenuService
│     └─ i18n   models/{AdminMessage,AdminMessageParams,AdminMessagePersistRequest,MessageCode,MessageCodeRow}
│               exceptions/{AdminMessageConflictException,AdminMessageValidationException}
│               mapper/AdminMessageMapper   service/AdminMessageService
├─ api
│  ├─ CodeController, MenuController
│  └─ admin/  AdminCodeController, AdminMenuController, AdminMessageController
└─ core/i18n  (MessageSource 인프라만: I18nMessage, I18nMessageMapper{selectAll,findByCodeAndLang}, KkdugiMessageSource, I18nMessageSourceConfig)
```

---

## Task 1: code 관리자 측 이동 — `core.code` → `app.admin.code` (`Admin*` 이름, mapper 통합)

**Files:**
- Move+rename (main): `app/admin/code/models/{CodeContent→AdminCode, CodeSearchParams→AdminCodeParams, CodePersistRequest→AdminCodePersistRequest, CodeLocale→AdminCodeLocale}.java`, `app/admin/code/exceptions/{CodeConflictException→AdminCodeConflictException, CodeValidationException→AdminCodeValidationException}.java`, `app/admin/code/service/CodeAdminService.java→AdminCodeService.java`, `api/admin/code/CodeAdminController.java→api/admin/AdminCodeController.java`
- Move (main): `core/code/models/{CodeBase,CodeLang,CodeValue}.java → app/admin/code/models/`
- Create: `app/admin/code/mapper/AdminCodeMapper.java`, `src/main/resources/mapper/postgres/app/admin/code/AdminCodeMapper.xml`
- Delete: `core/code/mapper/{CodeBaseMapper,CodeLangMapper}.java`, `resources/mapper/postgres/core/code/{CodeBaseMapper,CodeLangMapper}.xml`
- Test (move+edit): `app/admin/code/service/CodeAdminServiceTest.java → AdminCodeServiceTest.java`, `api/admin/code/CodeAdminControllerTest.java → api/admin/AdminCodeControllerTest.java`
- Docs: `docs/adr/0016-app-and-admin-feature-split.md`

**Interfaces:**
- Consumes: 기존 `CodeBase`/`CodeLang`(BaseModel 하위), `SerialConfig`/`SerialUtils.next`, `Page.of`.
- Produces (Task 2~6이 의존): `kkdugi.app.admin.code.mapper.AdminCodeMapper` 메서드 —
  `Optional<CodeBase> findById(String id)`, `List<CodeBase> search(String parentId, String code, String path, String name, String use, int offset, int pageSize)`, `List<CodeBase> findSelfAndDescendants(String path)`, `int insert(CodeBase)`, `int update(CodeBase)`, `int deleteByIds(List<String> ids)`, `List<CodeLang> findLangsByCodeId(String codeId)`, `List<CodeLang> findLangsByCodeIds(List<String> codeIds)`, `int insertLang(CodeLang)`, `int updateLang(CodeLang)`, `int deleteLangByCodeIds(List<String> codeIds)`. 클래스 `AdminCode`(구 `CodeContent`, 필드·생성자 동일), `AdminCodeParams`, `AdminCodePersistRequest`, `AdminCodeLocale`, `AdminCodeService`(`search(AdminCodeParams)`, `persist(AdminCodePersistRequest)`).

- [ ] **Step 1: 테스트 파일을 새 위치·이름으로 옮기고 새 API에 맞게 고친다 (이 상태가 "실패하는 테스트")**

```bash
cd /c/projects/kkdugi/kkdugi-admin
T=src/test/java/kkdugi
git mv $T/app/admin/code/service/CodeAdminServiceTest.java $T/app/admin/code/service/AdminCodeServiceTest.java
git mv $T/api/admin/code/CodeAdminControllerTest.java $T/api/admin/AdminCodeControllerTest.java

cat > "$TEMP/code-rename.sed" <<'EOF'
s/\bCodeContent\b/AdminCode/g
s/\bCodeSearchParams\b/AdminCodeParams/g
s/\bCodePersistRequest\b/AdminCodePersistRequest/g
s/\bCodeLocale\b/AdminCodeLocale/g
s/\bCodeConflictException\b/AdminCodeConflictException/g
s/\bCodeValidationException\b/AdminCodeValidationException/g
s/\bCodeAdminServiceTest\b/AdminCodeServiceTest/g
s/\bCodeAdminControllerTest\b/AdminCodeControllerTest/g
s/\bCodeAdminService\b/AdminCodeService/g
s/\bCodeAdminController\b/AdminCodeController/g
s/kkdugi\.core\.code\.models/kkdugi.app.admin.code.models/g
s/kkdugi\.api\.admin\.code/kkdugi.api.admin/g
EOF
sed -i -E -f "$TEMP/code-rename.sed" $T/app/admin/code/service/AdminCodeServiceTest.java $T/api/admin/AdminCodeControllerTest.java
```

`AdminCodeServiceTest.java`에서 mapper import 2줄과 필드·`cleanUp`을 아래로 교체한다(Edit 도구 사용).

old:
```java
import kkdugi.core.code.mapper.CodeBaseMapper;
import kkdugi.core.code.mapper.CodeLangMapper;
import kkdugi.app.admin.code.models.CodeBase;
```
new:
```java
import kkdugi.app.admin.code.mapper.AdminCodeMapper;
import kkdugi.app.admin.code.models.CodeBase;
```

old:
```java
    @Autowired
    private CodeBaseMapper codeBaseMapper;

    @Autowired
    private CodeLangMapper codeLangMapper;

    private String createdRootId;

    @AfterEach
    void cleanUp() {
        if (createdRootId != null) {
            String path = codeBaseMapper.findById(createdRootId).map(CodeBase::getPath).orElse("/__missing__");
            List<CodeBase> targets = codeBaseMapper.findSelfAndDescendants(path);
            List<String> ids = targets.stream().map(CodeBase::getId).toList();
            if (!ids.isEmpty()) {
                codeLangMapper.deleteByCodeIds(ids);
                codeBaseMapper.deleteByIds(ids);
            }
            createdRootId = null;
        }
    }
```
new:
```java
    @Autowired
    private AdminCodeMapper adminCodeMapper;

    private String createdRootId;

    @AfterEach
    void cleanUp() {
        if (createdRootId != null) {
            String path = adminCodeMapper.findById(createdRootId).map(CodeBase::getPath).orElse("/__missing__");
            List<CodeBase> targets = adminCodeMapper.findSelfAndDescendants(path);
            List<String> ids = targets.stream().map(CodeBase::getId).toList();
            if (!ids.isEmpty()) {
                adminCodeMapper.deleteLangByCodeIds(ids);
                adminCodeMapper.deleteByIds(ids);
            }
            createdRootId = null;
        }
    }
```

같은 파일의 마지막 사용처도 바꾼다: `codeBaseMapper.findById(parentId)` → `adminCodeMapper.findById(parentId)`.

```bash
sed -i 's/codeBaseMapper\.findById(parentId)/adminCodeMapper.findById(parentId)/' $T/app/admin/code/service/AdminCodeServiceTest.java
grep -n "codeBaseMapper\|codeLangMapper\|CodeBaseMapper\|CodeLangMapper" $T/app/admin/code/service/AdminCodeServiceTest.java
```
Expected: grep 결과 없음.

- [ ] **Step 2: 컴파일이 실패하는지 확인한다**

Run: `./mvnw.cmd -B -ntp test-compile`
Expected: `BUILD FAILURE` — `package kkdugi.app.admin.code.mapper does not exist`, `cannot find symbol AdminCode` 등 (main이 아직 옛 구조).

- [ ] **Step 3: main 소스를 옮기고 이름을 바꾼다**

```bash
cd /c/projects/kkdugi/kkdugi-admin
M=src/main/java/kkdugi
mkdir -p $M/app/admin/code/mapper
git mv $M/core/code/models/CodeBase.java  $M/app/admin/code/models/CodeBase.java
git mv $M/core/code/models/CodeLang.java  $M/app/admin/code/models/CodeLang.java
git mv $M/core/code/models/CodeValue.java $M/app/admin/code/models/CodeValue.java
git mv $M/app/admin/code/models/CodeContent.java        $M/app/admin/code/models/AdminCode.java
git mv $M/app/admin/code/models/CodeSearchParams.java   $M/app/admin/code/models/AdminCodeParams.java
git mv $M/app/admin/code/models/CodePersistRequest.java $M/app/admin/code/models/AdminCodePersistRequest.java
git mv $M/app/admin/code/models/CodeLocale.java         $M/app/admin/code/models/AdminCodeLocale.java
git mv $M/app/admin/code/exceptions/CodeConflictException.java   $M/app/admin/code/exceptions/AdminCodeConflictException.java
git mv $M/app/admin/code/exceptions/CodeValidationException.java $M/app/admin/code/exceptions/AdminCodeValidationException.java
git mv $M/app/admin/code/service/CodeAdminService.java $M/app/admin/code/service/AdminCodeService.java
git mv $M/api/admin/code/CodeAdminController.java $M/api/admin/AdminCodeController.java
git rm -q $M/core/code/mapper/CodeBaseMapper.java $M/core/code/mapper/CodeLangMapper.java

sed -i -E -f "$TEMP/code-rename.sed" \
  $M/app/admin/code/models/*.java $M/app/admin/code/exceptions/*.java \
  $M/app/admin/code/service/AdminCodeService.java $M/api/admin/AdminCodeController.java
# core.code.models 패키지 선언과 controller 패키지 선언 (위 sed는 FQCN 문자열만 바꾸므로 package 줄은 이미 포함됨)
grep -rn "^package" $M/app/admin/code/models/CodeBase.java $M/api/admin/AdminCodeController.java
```
Expected: `package kkdugi.app.admin.code.models;` / `package kkdugi.api.admin;`.

- [ ] **Step 4: `AdminCodeMapper` 인터페이스를 만든다**

Create `src/main/java/kkdugi/app/admin/code/mapper/AdminCodeMapper.java`:
```java
package kkdugi.app.admin.code.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

import kkdugi.app.admin.code.models.CodeBase;
import kkdugi.app.admin.code.models.CodeLang;

@Mapper
public interface AdminCodeMapper {

    Optional<CodeBase> findById(@Param("id") String id);

    List<CodeBase> search(@Param("parentId") String parentId,
                          @Param("code") String code,
                          @Param("path") String path,
                          @Param("name") String name,
                          @Param("use") String use,
                          @Param("offset") int offset,
                          @Param("pageSize") int pageSize);

    List<CodeBase> findSelfAndDescendants(@Param("path") String path);

    int insert(CodeBase codeBase);

    int update(CodeBase codeBase);

    int deleteByIds(@Param("ids") List<String> ids);

    List<CodeLang> findLangsByCodeId(@Param("codeId") String codeId);

    List<CodeLang> findLangsByCodeIds(@Param("codeIds") List<String> codeIds);

    int insertLang(CodeLang codeLang);

    int updateLang(CodeLang codeLang);

    int deleteLangByCodeIds(@Param("codeIds") List<String> codeIds);
}
```

- [ ] **Step 5: 두 XML을 `AdminCodeMapper.xml` 하나로 합친다**

```bash
R=src/main/resources/mapper/postgres
mkdir -p $R/app/admin/code
git rm -q $R/core/code/CodeBaseMapper.xml $R/core/code/CodeLangMapper.xml
```
Create `src/main/resources/mapper/postgres/app/admin/code/AdminCodeMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.admin.code.mapper.AdminCodeMapper">

  <resultMap id="codeBaseResultMap" type="kkdugi.app.admin.code.models.CodeBase"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="id" column="code_id"/>
    <result property="parentId" column="code_parent_id"/>
    <result property="code" column="code_val"/>
    <result property="extra1" column="etc_val1"/>
    <result property="extra2" column="etc_val2"/>
    <result property="extra3" column="etc_val3"/>
    <result property="extra4" column="etc_val4"/>
    <result property="extra5" column="etc_val5"/>
    <result property="level" column="code_lvl"/>
    <result property="path" column="code_path"/>
    <result property="sort" column="sort_seq"/>
    <result property="use" column="use_yn"/>
    <result property="totalSize" column="total_size"/>
  </resultMap>

  <resultMap id="codeLangResultMap" type="kkdugi.app.admin.code.models.CodeLang"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="codeId" column="code_id"/>
    <id property="langCode" column="lang_cd"/>
    <result property="name" column="code_nm"/>
    <result property="remarks" column="code_dc"/>
  </resultMap>

  <sql id="baseColumns">
<![CDATA[
code_id, code_parent_id, code_val, etc_val1, etc_val2, etc_val3, etc_val4, etc_val5,
code_lvl, code_path, sort_seq, use_yn, reg_dtm, reg_id, upd_dtm, upd_id
]]>
  </sql>

  <sql id="langColumns">
<![CDATA[
code_id, lang_cd, code_nm, code_dc, reg_dtm, reg_id, upd_dtm, upd_id
]]>
  </sql>

  <!--
    * QueryID=findById
    * Description=Find one code by id
    -->
  <select id="findById" resultMap="codeBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.findById */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_code_base
WHERE code_id = #{id}
]]>
  </select>

  <!--
    * QueryID=search
    * Description=Find direct children of a parent code with paging (all use_yn values)
    -->
  <select id="search" resultMap="codeBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.search */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
, COUNT(*) OVER() AS total_size
FROM kkdugi_code_base cb
]]>
    <where>
      <choose>
        <when test="parentId != null">
<![CDATA[
AND cb.code_parent_id = #{parentId}
]]>
        </when>
        <otherwise>
<![CDATA[
AND cb.code_parent_id IS NULL
]]>
        </otherwise>
      </choose>
      <if test="code != null and code != ''">
<![CDATA[
AND cb.code_val LIKE '%' || #{code} || '%'
]]>
      </if>
      <if test="path != null and path != ''">
<![CDATA[
AND cb.code_path LIKE '%' || #{path} || '%'
]]>
      </if>
      <if test="use != null and use != ''">
<![CDATA[
AND cb.use_yn = #{use}
]]>
      </if>
      <if test="name != null and name != ''">
<![CDATA[
AND EXISTS (
    SELECT 1 FROM kkdugi_code_lang cl
    WHERE cl.code_id = cb.code_id AND cl.code_nm LIKE '%' || #{name} || '%'
)
]]>
      </if>
    </where>
<![CDATA[
ORDER BY cb.sort_seq NULLS LAST, cb.code_val
OFFSET #{offset} LIMIT #{pageSize}
]]>
  </select>

  <!--
    * QueryID=findSelfAndDescendants
    * Description=Find a code and all its descendants by path prefix
    -->
  <select id="findSelfAndDescendants" resultMap="codeBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.findSelfAndDescendants */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_code_base
WHERE code_path = #{path} OR code_path LIKE #{path} || '/%'
]]>
  </select>

  <!--
    * QueryID=insert
    * Description=Insert one code row
    -->
  <insert id="insert" parameterType="kkdugi.app.admin.code.models.CodeBase">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.insert */
INSERT INTO kkdugi_code_base
    (code_id, code_parent_id, code_val, etc_val1, etc_val2, etc_val3, etc_val4, etc_val5,
     code_lvl, code_path, sort_seq, use_yn, reg_dtm, reg_id)
VALUES
    (#{id}, #{parentId}, #{code}, #{extra1}, #{extra2}, #{extra3}, #{extra4}, #{extra5},
     #{level}, #{path}, #{sort}, #{use}, #{createdAt}, #{creatorId})
]]>
  </insert>

  <!--
    * QueryID=update
    * Description=Update one code row
    -->
  <update id="update" parameterType="kkdugi.app.admin.code.models.CodeBase">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.update */
UPDATE kkdugi_code_base
SET etc_val1 = #{extra1},
    etc_val2 = #{extra2},
    etc_val3 = #{extra3},
    etc_val4 = #{extra4},
    etc_val5 = #{extra5},
    sort_seq = #{sort},
    use_yn   = #{use},
    upd_dtm  = #{updatedAt},
    upd_id   = #{updaterId}
WHERE code_id = #{id}
]]>
  </update>

  <!--
    * QueryID=deleteByIds
    * Description=Delete code rows by id list
    -->
  <delete id="deleteByIds">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.deleteByIds */
DELETE FROM kkdugi_code_base
WHERE code_id IN
]]>
    <foreach item="id" collection="ids" open="(" separator="," close=")">
      #{id}
    </foreach>
  </delete>

  <!--
    * QueryID=findLangsByCodeId
    * Description=Find all language rows for one code
    -->
  <select id="findLangsByCodeId" resultMap="codeLangResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.findLangsByCodeId */
SELECT
]]>
    <include refid="langColumns"/>
<![CDATA[
FROM kkdugi_code_lang
WHERE code_id = #{codeId}
]]>
  </select>

  <!--
    * QueryID=findLangsByCodeIds
    * Description=Find all language rows for a set of codes
    -->
  <select id="findLangsByCodeIds" resultMap="codeLangResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.findLangsByCodeIds */
SELECT
]]>
    <include refid="langColumns"/>
<![CDATA[
FROM kkdugi_code_lang
WHERE code_id IN
]]>
    <foreach item="id" collection="codeIds" open="(" separator="," close=")">
      #{id}
    </foreach>
<![CDATA[
ORDER BY code_id, lang_cd
]]>
  </select>

  <!--
    * QueryID=insertLang
    * Description=Insert one code-language row
    -->
  <insert id="insertLang" parameterType="kkdugi.app.admin.code.models.CodeLang">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.insertLang */
INSERT INTO kkdugi_code_lang (code_id, lang_cd, code_nm, code_dc, reg_dtm, reg_id)
VALUES (#{codeId}, #{langCode}, #{name}, #{remarks}, #{createdAt}, #{creatorId})
]]>
  </insert>

  <!--
    * QueryID=updateLang
    * Description=Update one code-language row
    -->
  <update id="updateLang" parameterType="kkdugi.app.admin.code.models.CodeLang">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.updateLang */
UPDATE kkdugi_code_lang
SET code_nm = #{name},
    code_dc = #{remarks},
    upd_dtm = #{updatedAt},
    upd_id  = #{updaterId}
WHERE code_id = #{codeId} AND lang_cd = #{langCode}
]]>
  </update>

  <!--
    * QueryID=deleteLangByCodeIds
    * Description=Delete language rows for a set of codes
    -->
  <delete id="deleteLangByCodeIds">
<![CDATA[
/* QueryID=kkdugi.app.admin.code.mapper.AdminCodeMapper.deleteLangByCodeIds */
DELETE FROM kkdugi_code_lang
WHERE code_id IN
]]>
    <foreach item="id" collection="codeIds" open="(" separator="," close=")">
      #{id}
    </foreach>
  </delete>

</mapper>
```

- [ ] **Step 6: `AdminCodeService`가 통합 mapper를 쓰도록 고친다**

```bash
S=src/main/java/kkdugi/app/admin/code/service/AdminCodeService.java
sed -i -E \
 -e 's/codeBaseMapper\.findChildren\(/adminCodeMapper.search(/g' \
 -e 's/codeBaseMapper\.(findById|insert|update|findSelfAndDescendants|deleteByIds)\(/adminCodeMapper.\1(/g' \
 -e 's/codeLangMapper\.findByCodeIds\(/adminCodeMapper.findLangsByCodeIds(/g' \
 -e 's/codeLangMapper\.findByCodeId\(/adminCodeMapper.findLangsByCodeId(/g' \
 -e 's/codeLangMapper\.insert\(/adminCodeMapper.insertLang(/g' \
 -e 's/codeLangMapper\.update\(/adminCodeMapper.updateLang(/g' \
 -e 's/codeLangMapper\.deleteByCodeIds\(/adminCodeMapper.deleteLangByCodeIds(/g' $S
```
그다음 Edit 도구로 import와 필드·생성자를 교체한다.

old:
```java
import kkdugi.core.code.mapper.CodeBaseMapper;
import kkdugi.core.code.mapper.CodeLangMapper;
```
new:
```java
import kkdugi.app.admin.code.mapper.AdminCodeMapper;
```

old:
```java
    private final CodeBaseMapper codeBaseMapper;
    private final CodeLangMapper codeLangMapper;

    public AdminCodeService(CodeBaseMapper codeBaseMapper, CodeLangMapper codeLangMapper) {
        this.codeBaseMapper = codeBaseMapper;
        this.codeLangMapper = codeLangMapper;
    }
```
new:
```java
    private final AdminCodeMapper adminCodeMapper;

    public AdminCodeService(AdminCodeMapper adminCodeMapper) {
        this.adminCodeMapper = adminCodeMapper;
    }
```

```bash
grep -n "codeBaseMapper\|codeLangMapper\|CodeBaseMapper\|CodeLangMapper" $S
grep -rn "kkdugi\.core\.code" src
```
Expected: 두 grep 모두 결과 없음.

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

Run: `./mvnw.cmd -B -ntp test -Dtest='AdminCodeServiceTest,AdminCodeControllerTest'`
Expected: `Tests run: 10, Failures: 0, Errors: 0` (서비스 7 + 컨트롤러 3), `BUILD SUCCESS`.

이어서 전체 회귀: `./mvnw.cmd -B -ntp test` → Expected `Tests run: 138, Failures: 0`, `BUILD SUCCESS`.

- [ ] **Step 8: ADR-0016을 쓰고 커밋한다**

Create `docs/adr/0016-app-and-admin-feature-split.md`:
```markdown
# ADR-0016: 사용자용(app.<기능>)과 관리자용(app.admin.<기능>) 기능 분리

- 상태: 채택 (2026-09-19)
- 관련: [ADR-0011](0011-api-define-admin-contract-and-record-models.md), [ADR-0014](0014-revert-to-base-model-inheritance.md), 설계 스펙 `docs/superpowers/specs/2026-09-19-app-api-structure-refactor-design.md`

## 배경

기능별로 별개 라이브러리 프로젝트로 떼어낼 계획이 있다. 기존에는 DB 행 모델·mapper가 `core.<기능>`에, 관리자 서비스가 `app.admin.<기능>`에 있어서 `app.<기능>` 계층이 없었고, 관리자 서비스가 read/write 구분 없이 core mapper를 직접 썼다.

## 결정

1. 사용자에게 보이는 데이터와 관리자 기능이 필요로 하는 데이터는 다르므로 모델·mapper·서비스를 분리해 유지한다: `app.<기능>`(사용자용)과 `app.admin.<기능>`(관리자용).
2. 의존 방향은 `api → app → core`. `app.admin.*`와 `app.*`는 서로 import하지 않는다.
3. `app.admin.<기능>`은 `Admin*` 접두사를 붙인 mapper/서비스/컨트롤러/모델을 가진다. read+write 쿼리는 `Admin<기능>Mapper` 하나에 통합한다.
4. API: 사용자용 `/api/v1.0/{menu,code}`, 관리자용 `/api/v1.0/admin/*`. 세션 메뉴는 `/api/v1.0/session/menu`에서 `/api/v1.0/menu`로 이전(옛 경로 별칭 없음).
5. i18n은 Spring `MessageSource` 인프라(`KkdugiMessageSource`, `I18nMessageMapper`)를 core에 두고 관리자 쓰기만 `app.admin.i18n`으로 분리한다.

## 결과

- `app.<기능>`을 admin 없이 라이브러리로 분리할 수 있다.
- 같은 테이블을 읽는 쿼리가 사용자용/관리자용 mapper에 각각 존재한다(의도된 중복).
```
```bash
git add -A
git commit -m "refactor(code): move core.code into app.admin.code with Admin* names and a single AdminCodeMapper

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Vb3CBKRkZDo8GoFJtAUgjT"
```

---

## Task 2: 사용자용 `app.code` 신설 — `GET /api/v1.0/code`

**Files:**
- Create: `src/main/java/kkdugi/app/code/models/Code.java`, `src/main/java/kkdugi/app/code/models/CodeParams.java`
- Create: `src/main/java/kkdugi/app/code/mapper/CodeMapper.java`, `src/main/resources/mapper/postgres/app/code/CodeMapper.xml`
- Create: `src/main/java/kkdugi/app/code/service/CodeService.java`
- Create: `src/main/java/kkdugi/api/CodeController.java`
- Test: `src/test/java/kkdugi/app/code/service/CodeServiceTest.java`, `src/test/java/kkdugi/api/CodeControllerTest.java`
- Docs: `docs/api/code.md`, `docs/api/README.md`(목차 표에 한 줄)

**Interfaces:**
- Consumes: `kkdugi.core.models.{BaseModel,BaseParams,Page}`, `AdminWebConfig`의 `?lang=` 로케일 해석(기존 동작, 기본 `ko_KR`).
- Produces: `CodeService#findChildren(CodeParams params, String langCode)` → `Page<Code>`, `CodeMapper#findChildren(String parentId, String path, String langCode, int offset, int pageSize)` → `List<Code>`, `GET /api/v1.0/code?parentId=&path=&page=&pageSize=&lang=`.

동작 규칙: `use_yn = 'Y'`만. `path`가 있으면 그 경로의 코드의 하위, 없고 `parentId`가 있으면 그 코드의 하위, 둘 다 없으면 최상위. 이름은 요청 언어 행의 `code_nm`, 없으면 `code_val`로 대체(`remarks`는 null). `sort_seq`(NULL 뒤), `code_val` 순 정렬.

- [ ] **Step 1: 서비스 테스트를 먼저 쓴다 (실패해야 함)**

Create `src/test/java/kkdugi/app/code/service/CodeServiceTest.java`:
```java
package kkdugi.app.code.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kkdugi.KkdugiAdminApplication;
import kkdugi.app.code.models.Code;
import kkdugi.app.code.models.CodeParams;
import kkdugi.core.models.Page;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class CodeServiceTest {

    private static final String ROOT_ID = "C_TEST_USER_SVC_ROOT";
    private static final String ROOT_PATH = "/TEST_USER_SVC_ROOT";
    private static final String CHILD_A = "C_TEST_USER_SVC_A";
    private static final String CHILD_B = "C_TEST_USER_SVC_B";
    private static final String CHILD_C = "C_TEST_USER_SVC_C";

    @Autowired
    private CodeService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        insertCode(ROOT_ID, null, "TEST_USER_SVC_ROOT", 0, ROOT_PATH, 1, "Y");
        insertCode(CHILD_A, ROOT_ID, "CHILD_A", 1, ROOT_PATH + "/CHILD_A", 1, "Y");
        insertCode(CHILD_B, ROOT_ID, "CHILD_B", 1, ROOT_PATH + "/CHILD_B", 2, "N");
        insertCode(CHILD_C, ROOT_ID, "CHILD_C", 1, ROOT_PATH + "/CHILD_C", 3, "Y");
        insertLang(ROOT_ID, "ko_KR", "루트");
        insertLang(ROOT_ID, "en_US", "Root");
        insertLang(CHILD_A, "ko_KR", "자식A");
        insertLang(CHILD_A, "en_US", "Child A");
        insertLang(CHILD_B, "ko_KR", "자식B");
        // CHILD_C는 언어 행이 없다 — 이름이 코드 값으로 대체되는지 확인하기 위함.
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_code_lang WHERE code_id LIKE 'C_TEST_USER_SVC_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_parent_id = ?", ROOT_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_id = ?", ROOT_ID);
    }

    @Test
    void findChildren_byParentId_returnsUsedChildrenInSortOrderWithLocalizedName() {
        Page<Code> page = service.findChildren(new CodeParams(ROOT_ID, null, 1, 200), "ko_KR");

        assertThat(page.getContents()).extracting(Code::getId).containsExactly(CHILD_A, CHILD_C);
        assertThat(page.getContents()).extracting(Code::getName).containsExactly("자식A", "CHILD_C");
        assertThat(page.getContents().get(0).getCode()).isEqualTo("CHILD_A");
        assertThat(page.getContents().get(0).getPath()).isEqualTo(ROOT_PATH + "/CHILD_A");
        assertThat(page.getContents().get(0).getLevel()).isEqualTo(1);
        assertThat(page.getContents().get(1).getRemarks()).isNull();
        assertThat(page.getTotalItems()).isEqualTo(2);
    }

    @Test
    void findChildren_byPath_resolvesParentFromPathAndUsesRequestedLanguage() {
        Page<Code> page = service.findChildren(new CodeParams(null, ROOT_PATH, 1, 200), "en_US");

        assertThat(page.getContents()).extracting(Code::getName).containsExactly("Child A", "CHILD_C");
    }

    @Test
    void findChildren_withUnknownPath_returnsEmptyInsteadOfRoots() {
        Page<Code> page = service.findChildren(new CodeParams(null, "/NO_SUCH_PATH", 1, 200), "ko_KR");

        assertThat(page.getContents()).isEmpty();
        assertThat(page.getTotalItems()).isZero();
    }

    @Test
    void findChildren_withoutParentOrPath_returnsRootsOnly() {
        Page<Code> page = service.findChildren(new CodeParams(null, null, 1, 200), "ko_KR");

        assertThat(page.getContents()).extracting(Code::getId).contains(ROOT_ID).doesNotContain(CHILD_A);
    }

    @Test
    void findChildren_pagesAtQueryLevelAndReportsResolvedPageParams() {
        Page<Code> page = service.findChildren(new CodeParams(ROOT_ID, null, 2, 1), "ko_KR");

        assertThat(page.getContents()).extracting(Code::getId).containsExactly(CHILD_C);
        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(1);
        assertThat(page.getTotalItems()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void findChildren_withUnresolvedPageParams_reportsDefaults() {
        Page<Code> page = service.findChildren(new CodeParams(ROOT_ID, null, 0, 0), "ko_KR");

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(200);
    }

    private void insertCode(String id, String parentId, String value, int level, String path, int sort, String use) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_code_base (code_id, code_parent_id, code_val, code_lvl, code_path, sort_seq, "
                        + "use_yn, reg_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, parentId, value, level, path, sort, use, "SYSTEM");
    }

    private void insertLang(String codeId, String lang, String name) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_code_lang (code_id, lang_cd, code_nm, reg_id) VALUES (?, ?, ?, ?)",
                codeId, lang, name, "SYSTEM");
    }
}
```

- [ ] **Step 2: 컨트롤러 테스트도 먼저 쓴다**

Create `src/test/java/kkdugi/api/CodeControllerTest.java`:
```java
package kkdugi.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import kkdugi.KkdugiAdminApplication;

@SpringBootTest(classes = KkdugiAdminApplication.class)
class CodeControllerTest {

    private static final String URL = "/api/v1.0/code";
    private static final String ROOT_ID = "C_TEST_USER_API_ROOT";
    private static final String ROOT_PATH = "/TEST_USER_API_ROOT";
    private static final String CHILD_A = "C_TEST_USER_API_A";
    private static final String CHILD_C = "C_TEST_USER_API_C";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        insertCode(ROOT_ID, null, "TEST_USER_API_ROOT", 0, ROOT_PATH, 1, "Y");
        insertCode(CHILD_A, ROOT_ID, "CHILD_A", 1, ROOT_PATH + "/CHILD_A", 1, "Y");
        insertCode(CHILD_C, ROOT_ID, "CHILD_C", 1, ROOT_PATH + "/CHILD_C", 3, "Y");
        insertLang(CHILD_A, "ko_KR", "자식A");
        insertLang(CHILD_A, "en_US", "Child A");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_code_lang WHERE code_id LIKE 'C_TEST_USER_API_%'");
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_parent_id = ?", ROOT_ID);
        jdbcTemplate.update("DELETE FROM kkdugi_code_base WHERE code_id = ?", ROOT_ID);
    }

    @Test
    void children_byPath_returnsPagedLocalizedContentsInDefaultLanguage() throws Exception {
        mockMvc.perform(get(URL).param("path", ROOT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(200))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.contents.length()").value(2))
                .andExpect(jsonPath("$.contents[0].id").value(CHILD_A))
                .andExpect(jsonPath("$.contents[0].parentId").value(ROOT_ID))
                .andExpect(jsonPath("$.contents[0].code").value("CHILD_A"))
                .andExpect(jsonPath("$.contents[0].name").value("자식A"))
                .andExpect(jsonPath("$.contents[0].path").value(ROOT_PATH + "/CHILD_A"))
                .andExpect(jsonPath("$.contents[1].name").value("CHILD_C"))
                // BaseModel 상속 필드는 응답에 노출되지 않는다.
                .andExpect(jsonPath("$.contents[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.contents[0].creatorId").doesNotExist())
                .andExpect(jsonPath("$.contents[0].rownum").doesNotExist());
    }

    @Test
    void children_withLangParam_usesRequestedLanguage() throws Exception {
        mockMvc.perform(get(URL).param("path", ROOT_PATH).param("lang", "en_US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contents[0].name").value("Child A"));
    }

    private void insertCode(String id, String parentId, String value, int level, String path, int sort, String use) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_code_base (code_id, code_parent_id, code_val, code_lvl, code_path, sort_seq, "
                        + "use_yn, reg_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, parentId, value, level, path, sort, use, "SYSTEM");
    }

    private void insertLang(String codeId, String lang, String name) {
        jdbcTemplate.update(
                "INSERT INTO kkdugi_code_lang (code_id, lang_cd, code_nm, reg_id) VALUES (?, ?, ?, ?)",
                codeId, lang, name, "SYSTEM");
    }
}
```

- [ ] **Step 3: 컴파일이 실패하는지 확인한다**

Run: `./mvnw.cmd -B -ntp test-compile`
Expected: `BUILD FAILURE` — `package kkdugi.app.code.models does not exist`, `cannot find symbol CodeService`.

- [ ] **Step 4: 모델 두 개를 만든다**

Create `src/main/java/kkdugi/app/code/models/Code.java`:
```java
package kkdugi.app.code.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kkdugi.core.models.BaseModel;

import lombok.Getter;
import lombok.Setter;

/**
 * 사용자에게 보이는 공통코드 한 건 — 요청 언어로 해석된 이름/설명을 담는다.
 * {@link kkdugi.core.models.Page}가 {@code T extends BaseModel}을 요구해서
 * (쿼리 레벨 페이징) BaseModel을 상속하고, 상속 필드는 응답에서 숨긴다.
 * 관리자용 {@code kkdugi.app.admin.code}의 모델과는 의도적으로 공유하지 않는다.
 */
@Getter
@Setter
@JsonIgnoreProperties({ "rownum", "createdAt", "creatorId", "updatedAt", "updaterId" })
public class Code extends BaseModel {

    private String id;
    private String parentId;
    private String code;
    private String name;
    private String remarks;
    private String extra1;
    private String extra2;
    private String extra3;
    private String extra4;
    private String extra5;
    private String path;
    private Integer level;
    private Integer sort;

    public Code() {
    }
}
```
Create `src/main/java/kkdugi/app/code/models/CodeParams.java`:
```java
package kkdugi.app.code.models;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

/** {@code path}가 있으면 그 경로 코드의 하위, 없고 {@code parentId}가 있으면 그 코드의 하위, 둘 다 없으면 최상위. */
@Getter
@Setter
public class CodeParams extends BaseParams {

    private String parentId;
    private String path;

    public CodeParams() {
    }

    public CodeParams(String parentId, String path, int page, int pageSize) {
        this.parentId = parentId;
        this.path = path;
        setPage(page);
        setPageSize(pageSize);
    }
}
```

- [ ] **Step 5: mapper 인터페이스와 XML을 만든다**

Create `src/main/java/kkdugi/app/code/mapper/CodeMapper.java`:
```java
package kkdugi.app.code.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import kkdugi.app.code.models.Code;

@Mapper
public interface CodeMapper {

    List<Code> findChildren(@Param("parentId") String parentId,
                            @Param("path") String path,
                            @Param("langCode") String langCode,
                            @Param("offset") int offset,
                            @Param("pageSize") int pageSize);
}
```
Create `src/main/resources/mapper/postgres/app/code/CodeMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.code.mapper.CodeMapper">

  <!-- 감사 컬럼(reg_*/upd_*)은 select하지 않으므로 baseResultMap을 extends하지 않는다. -->
  <resultMap id="codeResultMap" type="kkdugi.app.code.models.Code">
    <id property="id" column="code_id"/>
    <result property="parentId" column="code_parent_id"/>
    <result property="code" column="code_val"/>
    <result property="name" column="code_nm"/>
    <result property="remarks" column="code_dc"/>
    <result property="extra1" column="etc_val1"/>
    <result property="extra2" column="etc_val2"/>
    <result property="extra3" column="etc_val3"/>
    <result property="extra4" column="etc_val4"/>
    <result property="extra5" column="etc_val5"/>
    <result property="path" column="code_path"/>
    <result property="level" column="code_lvl"/>
    <result property="sort" column="sort_seq"/>
    <result property="totalSize" column="total_size"/>
  </resultMap>

  <!--
    * QueryID=findChildren
    * Description=Find used (use_yn = 'Y') direct children of a parent code, with the
    *             name resolved to the requested language (falls back to code_val),
    *             paged at query level (total via window function)
    -->
  <select id="findChildren" resultMap="codeResultMap">
<![CDATA[
/* QueryID=kkdugi.app.code.mapper.CodeMapper.findChildren */
SELECT cb.code_id, cb.code_parent_id, cb.code_val,
       COALESCE(cl.code_nm, cb.code_val) AS code_nm,
       cl.code_dc,
       cb.etc_val1, cb.etc_val2, cb.etc_val3, cb.etc_val4, cb.etc_val5,
       cb.code_path, cb.code_lvl, cb.sort_seq,
       COUNT(*) OVER() AS total_size
FROM kkdugi_code_base cb
LEFT JOIN kkdugi_code_lang cl
       ON cl.code_id = cb.code_id AND cl.lang_cd = #{langCode}
WHERE cb.use_yn = 'Y'
]]>
    <choose>
      <when test="path != null and path != ''">
<![CDATA[
AND cb.code_parent_id = (SELECT p.code_id FROM kkdugi_code_base p WHERE p.code_path = #{path})
]]>
      </when>
      <when test="parentId != null and parentId != ''">
<![CDATA[
AND cb.code_parent_id = #{parentId}
]]>
      </when>
      <otherwise>
<![CDATA[
AND cb.code_parent_id IS NULL
]]>
      </otherwise>
    </choose>
<![CDATA[
ORDER BY cb.sort_seq NULLS LAST, cb.code_val
OFFSET #{offset} LIMIT #{pageSize}
]]>
  </select>

</mapper>
```

- [ ] **Step 6: 서비스와 컨트롤러를 만든다**

Create `src/main/java/kkdugi/app/code/service/CodeService.java`:
```java
package kkdugi.app.code.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.code.mapper.CodeMapper;
import kkdugi.app.code.models.Code;
import kkdugi.app.code.models.CodeParams;
import kkdugi.core.models.Page;

@Service
public class CodeService {

    private final CodeMapper mapper;

    public CodeService(CodeMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page<Code> findChildren(CodeParams params, String langCode) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());

        List<Code> contents = mapper.findChildren(
                params.getParentId(), params.getPath(), langCode, params.getOffset(), params.getLimit());

        return Page.of(contents, params);
    }
}
```
Create `src/main/java/kkdugi/api/CodeController.java`:
```java
package kkdugi.api;

import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.code.models.Code;
import kkdugi.app.code.models.CodeParams;
import kkdugi.app.code.service.CodeService;
import kkdugi.core.models.Page;

/** 사용자용 공통코드 조회. 관리자용은 {@code kkdugi.api.admin.AdminCodeController}. */
@RestController
@RequestMapping("/api/v1.0/code")
public class CodeController {

    private final CodeService service;

    public CodeController(CodeService service) {
        this.service = service;
    }

    @GetMapping
    public Page<Code> children(CodeParams params, Locale locale) {
        return service.findChildren(params, locale.toString());
    }
}
```

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

Run: `./mvnw.cmd -B -ntp test -Dtest='CodeServiceTest,CodeControllerTest'`
Expected: `Tests run: 8, Failures: 0, Errors: 0` (서비스 6 + 컨트롤러 2), `BUILD SUCCESS`.
실패 시 확인: 컨트롤러 `name`이 `ko_KR`로 안 나오면 `AdminWebConfig`의 기본 로케일 함수가 적용되는지, `en_US` 케이스는 `?lang=` 인터셉터가 동작하는지.

- [ ] **Step 8: API 문서를 쓰고 커밋한다**

Create `docs/api/code.md`:
~~~markdown
# 공통코드 조회 (사용자용) - GET /api/v1.0/code

로그인 여부와 무관하게(현재 인가 규칙은 전부 `permitAll`) 사용할 수 있는 읽기 전용 API.
관리자용 조회/저장은 [common-code.md](common-code.md)(`/api/v1.0/admin/code`)이며 모델을 공유하지 않는다.

## 요청

쿼리 파라미터 (모두 선택):

| 이름 | 설명 |
|---|---|
| `path` | 이 경로(`/SYS/USER/STAT` 형식)의 코드의 하위 코드를 조회한다. 우선순위 1 |
| `parentId` | 이 ID의 코드의 하위 코드를 조회한다. `path`가 없을 때만 사용 |
| `page`, `pageSize` | 기본 1, 200. `pageSize` 최대 200 |
| `lang` | `ko_KR`/`en_US`. 지정하면 이후 요청에도 쿠키로 유지된다(기본 `ko_KR`) |

`path`와 `parentId`가 모두 없으면 최상위 코드를 조회한다. 존재하지 않는 `path`는 빈 목록이다.

## 응답 200

```json
{
  "page": 1,
  "pageSize": 200,
  "totalItems": 2,
  "totalPages": 1,
  "contents": [
    { "id": "C2026091912000001", "parentId": "C2026091912000000", "code": "ACTIVE",
      "name": "사용", "remarks": null, "extra1": null, "extra2": null, "extra3": null,
      "extra4": null, "extra5": null, "path": "/SYS/USER_STAT/ACTIVE", "level": 2, "sort": 1 }
  ]
}
```

- `use = 'Y'`인 코드만 내려준다. 정렬은 `sort`(없으면 뒤), `code` 순.
- `name`은 요청 언어의 이름이고, 그 언어 행이 없으면 `code` 값으로 대체된다. `remarks`는 그 언어 행이 없으면 `null`.
~~~
`docs/api/README.md`의 목차 표에서 `common-code.md` 행 바로 아래에 추가:
```markdown
| [code.md](code.md) | 공통코드 조회(사용자용) — 하위 코드 목록, 언어별 이름 |
```
```bash
git add -A
git commit -m "feat(code): add user-facing app.code with GET /api/v1.0/code

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Vb3CBKRkZDo8GoFJtAUgjT"
```

---

## Task 3: menu 관리자 측 이동 — `core.menu` → `app.admin.menu` (`Admin*` 이름, mapper 통합)

**Files:**
- Move+rename (main): `app/admin/menu/models/{MenuContent→AdminMenu, MenuLocale→AdminMenuLocale, MenuPersistRequest→AdminMenuPersistRequest}.java`, `app/admin/menu/exceptions/{MenuConflictException→AdminMenuConflictException, MenuValidationException→AdminMenuValidationException}.java`, `app/admin/menu/service/MenuAdminService.java→AdminMenuService.java`, `api/admin/menu/MenuAdminController.java→api/admin/AdminMenuController.java`
- Move (main): `core/menu/models/{MenuBase,MenuLang}.java → app/admin/menu/models/`
- Create: `app/admin/menu/mapper/AdminMenuMapper.java`, `src/main/resources/mapper/postgres/app/admin/menu/AdminMenuMapper.xml`
- Delete: `core/menu/mapper/{MenuBaseMapper,MenuLangMapper}.java`, `resources/mapper/postgres/core/menu/{MenuBaseMapper,MenuLangMapper}.xml`
- Test (move+edit): `app/admin/menu/service/MenuAdminServiceTest.java → AdminMenuServiceTest.java`, `api/admin/menu/MenuAdminControllerTest.java → api/admin/AdminMenuControllerTest.java`, `core/menu/DefaultMenuSeedTest.java → app/admin/menu/DefaultMenuSeedTest.java`

**Interfaces:**
- Consumes: Task 1과 같은 패턴(BaseModel 하위 행 모델, `SerialConfig`/`SerialUtils`, `TreeUtils.convert`).
- Produces: `kkdugi.app.admin.menu.mapper.AdminMenuMapper` — `Optional<MenuBase> findById(String id)`, `List<MenuBase> findAll()`, `List<MenuBase> findSelfAndDescendants(String path)`, `int insert(MenuBase)`, `int update(MenuBase)`, `int deleteByIds(List<String> ids)`, `List<MenuLang> findLangsByMenuId(String menuId)`, `List<MenuLang> findLangsByMenuIds(List<String> menuIds)`, `int insertLang(MenuLang)`, `int updateLang(MenuLang)`, `int deleteLangByMenuIds(List<String> menuIds)`. `AdminMenu`(구 `MenuContent`, `Tree<AdminMenu>` 구현), `AdminMenuLocale`, `AdminMenuPersistRequest`, `AdminMenuService`(`search()`, `persist(AdminMenuPersistRequest)`).

- [ ] **Step 1: 테스트 파일을 새 위치·이름으로 옮기고 새 API에 맞게 고친다 (실패하는 테스트)**

```bash
cd /c/projects/kkdugi/kkdugi-admin
T=src/test/java/kkdugi
git mv $T/app/admin/menu/service/MenuAdminServiceTest.java $T/app/admin/menu/service/AdminMenuServiceTest.java
git mv $T/api/admin/menu/MenuAdminControllerTest.java $T/api/admin/AdminMenuControllerTest.java
git mv $T/core/menu/DefaultMenuSeedTest.java $T/app/admin/menu/DefaultMenuSeedTest.java

cat > "$TEMP/menu-rename.sed" <<'EOF'
s/\bMenuContent\b/AdminMenu/g
s/\bMenuPersistRequest\b/AdminMenuPersistRequest/g
s/\bMenuLocale\b/AdminMenuLocale/g
s/\bMenuConflictException\b/AdminMenuConflictException/g
s/\bMenuValidationException\b/AdminMenuValidationException/g
s/\bMenuAdminServiceTest\b/AdminMenuServiceTest/g
s/\bMenuAdminControllerTest\b/AdminMenuControllerTest/g
s/\bMenuAdminService\b/AdminMenuService/g
s/\bMenuAdminController\b/AdminMenuController/g
s/kkdugi\.core\.menu\.models/kkdugi.app.admin.menu.models/g
s/kkdugi\.api\.admin\.menu/kkdugi.api.admin/g
EOF
sed -i -E -f "$TEMP/menu-rename.sed" \
  $T/app/admin/menu/service/AdminMenuServiceTest.java $T/api/admin/AdminMenuControllerTest.java \
  $T/app/admin/menu/DefaultMenuSeedTest.java
sed -i 's/^package kkdugi\.core\.menu;/package kkdugi.app.admin.menu;/' $T/app/admin/menu/DefaultMenuSeedTest.java
```

`AdminMenuServiceTest.java`에서 아래를 Edit 도구로 교체한다.

old:
```java
import kkdugi.core.menu.mapper.MenuBaseMapper;
import kkdugi.core.menu.mapper.MenuLangMapper;
import kkdugi.app.admin.menu.models.MenuBase;
```
new:
```java
import kkdugi.app.admin.menu.mapper.AdminMenuMapper;
import kkdugi.app.admin.menu.models.MenuBase;
```

old:
```java
    @Autowired
    private MenuBaseMapper menuBaseMapper;

    @Autowired
    private MenuLangMapper menuLangMapper;

    private String createdRootId;

    @AfterEach
    void cleanUp() {
        if (createdRootId != null) {
            String path = menuBaseMapper.findById(createdRootId).map(MenuBase::getPath).orElse("/__missing__");
            List<MenuBase> targets = menuBaseMapper.findSelfAndDescendants(path);
            List<String> ids = targets.stream().map(MenuBase::getId).toList();
            if (!ids.isEmpty()) {
                menuLangMapper.deleteByMenuIds(ids);
                menuBaseMapper.deleteByIds(ids);
            }
            createdRootId = null;
        }
    }
```
new:
```java
    @Autowired
    private AdminMenuMapper adminMenuMapper;

    private String createdRootId;

    @AfterEach
    void cleanUp() {
        if (createdRootId != null) {
            String path = adminMenuMapper.findById(createdRootId).map(MenuBase::getPath).orElse("/__missing__");
            List<MenuBase> targets = adminMenuMapper.findSelfAndDescendants(path);
            List<String> ids = targets.stream().map(MenuBase::getId).toList();
            if (!ids.isEmpty()) {
                adminMenuMapper.deleteLangByMenuIds(ids);
                adminMenuMapper.deleteByIds(ids);
            }
            createdRootId = null;
        }
    }
```

```bash
sed -i 's/menuBaseMapper\.findById(parentId)/adminMenuMapper.findById(parentId)/' $T/app/admin/menu/service/AdminMenuServiceTest.java
grep -n "menuBaseMapper\|menuLangMapper\|MenuBaseMapper\|MenuLangMapper" $T/app/admin/menu/service/AdminMenuServiceTest.java
```
Expected: grep 결과 없음.

- [ ] **Step 2: 컴파일이 실패하는지 확인한다**

Run: `./mvnw.cmd -B -ntp test-compile`
Expected: `BUILD FAILURE` — `package kkdugi.app.admin.menu.mapper does not exist`, `cannot find symbol AdminMenu`.

- [ ] **Step 3: main 소스를 옮기고 이름을 바꾼다**

```bash
cd /c/projects/kkdugi/kkdugi-admin
M=src/main/java/kkdugi
mkdir -p $M/app/admin/menu/mapper
git mv $M/core/menu/models/MenuBase.java $M/app/admin/menu/models/MenuBase.java
git mv $M/core/menu/models/MenuLang.java $M/app/admin/menu/models/MenuLang.java
git mv $M/app/admin/menu/models/MenuContent.java        $M/app/admin/menu/models/AdminMenu.java
git mv $M/app/admin/menu/models/MenuLocale.java         $M/app/admin/menu/models/AdminMenuLocale.java
git mv $M/app/admin/menu/models/MenuPersistRequest.java $M/app/admin/menu/models/AdminMenuPersistRequest.java
git mv $M/app/admin/menu/exceptions/MenuConflictException.java   $M/app/admin/menu/exceptions/AdminMenuConflictException.java
git mv $M/app/admin/menu/exceptions/MenuValidationException.java $M/app/admin/menu/exceptions/AdminMenuValidationException.java
git mv $M/app/admin/menu/service/MenuAdminService.java $M/app/admin/menu/service/AdminMenuService.java
git mv $M/api/admin/menu/MenuAdminController.java $M/api/admin/AdminMenuController.java
git rm -q $M/core/menu/mapper/MenuBaseMapper.java $M/core/menu/mapper/MenuLangMapper.java

sed -i -E -f "$TEMP/menu-rename.sed" \
  $M/app/admin/menu/models/*.java $M/app/admin/menu/exceptions/*.java \
  $M/app/admin/menu/service/AdminMenuService.java $M/api/admin/AdminMenuController.java
grep -n "^package" $M/app/admin/menu/models/MenuBase.java $M/api/admin/AdminMenuController.java
```
Expected: `package kkdugi.app.admin.menu.models;` / `package kkdugi.api.admin;`.

- [ ] **Step 4: `AdminMenuMapper` 인터페이스를 만든다**

Create `src/main/java/kkdugi/app/admin/menu/mapper/AdminMenuMapper.java`:
```java
package kkdugi.app.admin.menu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

import kkdugi.app.admin.menu.models.MenuBase;
import kkdugi.app.admin.menu.models.MenuLang;

@Mapper
public interface AdminMenuMapper {

    Optional<MenuBase> findById(@Param("id") String id);

    List<MenuBase> findAll();

    List<MenuBase> findSelfAndDescendants(@Param("path") String path);

    int insert(MenuBase menuBase);

    int update(MenuBase menuBase);

    int deleteByIds(@Param("ids") List<String> ids);

    List<MenuLang> findLangsByMenuId(@Param("menuId") String menuId);

    List<MenuLang> findLangsByMenuIds(@Param("menuIds") List<String> menuIds);

    int insertLang(MenuLang menuLang);

    int updateLang(MenuLang menuLang);

    int deleteLangByMenuIds(@Param("menuIds") List<String> menuIds);
}
```

- [ ] **Step 5: 두 XML을 `AdminMenuMapper.xml` 하나로 합친다**

```bash
R=src/main/resources/mapper/postgres
mkdir -p $R/app/admin/menu
git rm -q $R/core/menu/MenuBaseMapper.xml $R/core/menu/MenuLangMapper.xml
```
Create `src/main/resources/mapper/postgres/app/admin/menu/AdminMenuMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.admin.menu.mapper.AdminMenuMapper">

  <resultMap id="menuBaseResultMap" type="kkdugi.app.admin.menu.models.MenuBase"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="id" column="menu_id"/>
    <result property="parentId" column="menu_parent_id"/>
    <result property="icon" column="menu_ico"/>
    <result property="program" column="menu_pgm"/>
    <result property="level" column="menu_lvl"/>
    <result property="path" column="menu_path"/>
    <result property="sort" column="sort_seq"/>
    <result property="use" column="use_yn"/>
    <result property="close" column="close_yn"/>
  </resultMap>

  <resultMap id="menuLangResultMap" type="kkdugi.app.admin.menu.models.MenuLang"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="menuId" column="menu_id"/>
    <id property="langCode" column="lang_cd"/>
    <result property="label" column="menu_nm"/>
    <result property="remarks" column="menu_dc"/>
  </resultMap>

  <sql id="baseColumns">
<![CDATA[
menu_id, menu_parent_id, menu_ico, menu_pgm, menu_lvl, menu_path, sort_seq, use_yn, close_yn,
reg_dtm, reg_id, upd_dtm, upd_id
]]>
  </sql>

  <sql id="langColumns">
<![CDATA[
menu_id, lang_cd, menu_nm, menu_dc, reg_dtm, reg_id, upd_dtm, upd_id
]]>
  </sql>

  <!--
    * QueryID=findById
    * Description=Find one menu by id
    -->
  <select id="findById" resultMap="menuBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.findById */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_menu_base
WHERE menu_id = #{id}
]]>
  </select>

  <!--
    * QueryID=findAll
    * Description=Find every menu (admin tree view has no paging/filter)
    -->
  <select id="findAll" resultMap="menuBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.findAll */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_menu_base
ORDER BY menu_lvl, sort_seq NULLS LAST, menu_id
]]>
  </select>

  <!--
    * QueryID=findSelfAndDescendants
    * Description=Find a menu and all its descendants by path prefix
    -->
  <select id="findSelfAndDescendants" resultMap="menuBaseResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.findSelfAndDescendants */
SELECT
]]>
    <include refid="baseColumns"/>
<![CDATA[
FROM kkdugi_menu_base
WHERE menu_path = #{path} OR menu_path LIKE #{path} || '/%'
]]>
  </select>

  <!--
    * QueryID=insert
    * Description=Insert one menu row
    -->
  <insert id="insert" parameterType="kkdugi.app.admin.menu.models.MenuBase">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.insert */
INSERT INTO kkdugi_menu_base
    (menu_id, menu_parent_id, menu_ico, menu_pgm, menu_lvl, menu_path, sort_seq, use_yn, close_yn,
     reg_dtm, reg_id)
VALUES
    (#{id}, #{parentId}, #{icon}, #{program}, #{level}, #{path}, #{sort}, #{use}, #{close},
     #{createdAt}, #{creatorId})
]]>
  </insert>

  <!--
    * QueryID=update
    * Description=Update one menu row
    -->
  <update id="update" parameterType="kkdugi.app.admin.menu.models.MenuBase">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.update */
UPDATE kkdugi_menu_base
SET menu_ico = #{icon},
    menu_pgm = #{program},
    sort_seq = #{sort},
    use_yn   = #{use},
    close_yn = #{close},
    upd_dtm  = #{updatedAt},
    upd_id   = #{updaterId}
WHERE menu_id = #{id}
]]>
  </update>

  <!--
    * QueryID=deleteByIds
    * Description=Delete menu rows by id list
    -->
  <delete id="deleteByIds">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.deleteByIds */
DELETE FROM kkdugi_menu_base
WHERE menu_id IN
]]>
    <foreach item="id" collection="ids" open="(" separator="," close=")">
      #{id}
    </foreach>
  </delete>

  <!--
    * QueryID=findLangsByMenuId
    * Description=Find all language rows for one menu
    -->
  <select id="findLangsByMenuId" resultMap="menuLangResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.findLangsByMenuId */
SELECT
]]>
    <include refid="langColumns"/>
<![CDATA[
FROM kkdugi_menu_lang
WHERE menu_id = #{menuId}
]]>
  </select>

  <!--
    * QueryID=findLangsByMenuIds
    * Description=Find all language rows for a set of menus
    -->
  <select id="findLangsByMenuIds" resultMap="menuLangResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.findLangsByMenuIds */
SELECT
]]>
    <include refid="langColumns"/>
<![CDATA[
FROM kkdugi_menu_lang
WHERE menu_id IN
]]>
    <foreach item="id" collection="menuIds" open="(" separator="," close=")">
      #{id}
    </foreach>
<![CDATA[
ORDER BY menu_id, lang_cd
]]>
  </select>

  <!--
    * QueryID=insertLang
    * Description=Insert one menu-language row
    -->
  <insert id="insertLang" parameterType="kkdugi.app.admin.menu.models.MenuLang">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.insertLang */
INSERT INTO kkdugi_menu_lang (menu_id, lang_cd, menu_nm, menu_dc, reg_dtm, reg_id)
VALUES (#{menuId}, #{langCode}, #{label}, #{remarks}, #{createdAt}, #{creatorId})
]]>
  </insert>

  <!--
    * QueryID=updateLang
    * Description=Update one menu-language row
    -->
  <update id="updateLang" parameterType="kkdugi.app.admin.menu.models.MenuLang">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.updateLang */
UPDATE kkdugi_menu_lang
SET menu_nm = #{label},
    menu_dc = #{remarks},
    upd_dtm = #{updatedAt},
    upd_id  = #{updaterId}
WHERE menu_id = #{menuId} AND lang_cd = #{langCode}
]]>
  </update>

  <!--
    * QueryID=deleteLangByMenuIds
    * Description=Delete language rows for a set of menus
    -->
  <delete id="deleteLangByMenuIds">
<![CDATA[
/* QueryID=kkdugi.app.admin.menu.mapper.AdminMenuMapper.deleteLangByMenuIds */
DELETE FROM kkdugi_menu_lang
WHERE menu_id IN
]]>
    <foreach item="id" collection="menuIds" open="(" separator="," close=")">
      #{id}
    </foreach>
  </delete>

</mapper>
```

- [ ] **Step 6: `AdminMenuService`가 통합 mapper를 쓰도록 고친다**

```bash
S=src/main/java/kkdugi/app/admin/menu/service/AdminMenuService.java
sed -i -E \
 -e 's/menuBaseMapper\.(findById|findAll|findSelfAndDescendants|insert|update|deleteByIds)\(/adminMenuMapper.\1(/g' \
 -e 's/menuLangMapper\.findByMenuIds\(/adminMenuMapper.findLangsByMenuIds(/g' \
 -e 's/menuLangMapper\.findByMenuId\(/adminMenuMapper.findLangsByMenuId(/g' \
 -e 's/menuLangMapper\.insert\(/adminMenuMapper.insertLang(/g' \
 -e 's/menuLangMapper\.update\(/adminMenuMapper.updateLang(/g' \
 -e 's/menuLangMapper\.deleteByMenuIds\(/adminMenuMapper.deleteLangByMenuIds(/g' $S
```
그다음 Edit 도구로 교체한다.

old:
```java
import kkdugi.core.menu.mapper.MenuBaseMapper;
import kkdugi.core.menu.mapper.MenuLangMapper;
```
new:
```java
import kkdugi.app.admin.menu.mapper.AdminMenuMapper;
```

old:
```java
    private final MenuBaseMapper menuBaseMapper;
    private final MenuLangMapper menuLangMapper;

    public AdminMenuService(MenuBaseMapper menuBaseMapper, MenuLangMapper menuLangMapper) {
        this.menuBaseMapper = menuBaseMapper;
        this.menuLangMapper = menuLangMapper;
    }
```
new:
```java
    private final AdminMenuMapper adminMenuMapper;

    public AdminMenuService(AdminMenuMapper adminMenuMapper) {
        this.adminMenuMapper = adminMenuMapper;
    }
```

```bash
grep -n "menuBaseMapper\|menuLangMapper\|MenuBaseMapper\|MenuLangMapper" $S
grep -rn "kkdugi\.core\.menu" src
```
Expected: 두 grep 모두 결과 없음.

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

Run: `./mvnw.cmd -B -ntp test -Dtest='AdminMenuServiceTest,AdminMenuControllerTest,DefaultMenuSeedTest'`
Expected: `Tests run: 13, Failures: 0, Errors: 0` (서비스 6 + 컨트롤러 3 + 시드 4), `BUILD SUCCESS`.

이어서 전체 회귀: `./mvnw.cmd -B -ntp test` → Expected `Tests run: 146, Failures: 0` (기준선 138 + Task 2의 8), `BUILD SUCCESS`.

- [ ] **Step 8: 커밋한다**

```bash
git add -A
git commit -m "refactor(menu): move core.menu into app.admin.menu with Admin* names and a single AdminMenuMapper

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Vb3CBKRkZDo8GoFJtAUgjT"
```

---

## Task 4: 사용자 메뉴 이전 — `api.session` → `api.MenuController` + `app.menu`, `/api/v1.0/menu`

**Files:**
- Move+rename: `src/main/java/kkdugi/api/session/MenuTreeItem.java → src/main/java/kkdugi/app/menu/models/Menu.java`
- Create: `src/main/java/kkdugi/app/menu/service/MenuService.java`, `src/main/java/kkdugi/api/MenuController.java`
- Delete: `src/main/java/kkdugi/api/session/SessionMenuController.java`
- Test (move+edit): `src/test/java/kkdugi/api/admin/session/SessionMenuControllerTest.java → src/test/java/kkdugi/api/MenuControllerTest.java`
- Modify: `src/test/java/kkdugi/core/security/filter/AuthenticationProcessingFilterTest.java`(URL 상수), `src/main/resources/static/js/api/index.mjs`, `src/main/resources/static/js/api/http.mjs:8`, `src/test/js/current-contract.test.mjs`
- Docs: `../docs/api/session.md`, `../docs/api/README.md`

**Interfaces:**
- Consumes: `kkdugi.core.util.SessionUtils#getUser()`(`getMenus(): List<SessionMenu>`), `kkdugi.core.security.models.SessionMenu`(`getId/getParentId/getTitle/getRemarks/getIcon/getSort/getProgram`), `kkdugi.core.util.TreeUtils#convert`, `kkdugi.core.util.CommonUtils#isNotEmpty`, `kkdugi.core.models.Tree`.
- Produces: `kkdugi.app.menu.models.Menu`(구 `MenuTreeItem`, 필드·JSON 동일), `MenuService#tree(): List<Menu>`, `GET /api/v1.0/menu`(응답 형태는 기존 `/api/v1.0/session/menu`와 동일).

- [ ] **Step 1: Java 테스트를 새 위치·URL로 옮긴다 (실패하는 테스트)**

```bash
cd /c/projects/kkdugi/kkdugi-admin
T=src/test/java/kkdugi
git mv $T/api/admin/session/SessionMenuControllerTest.java $T/api/MenuControllerTest.java
sed -i -E \
 -e 's/^package kkdugi\.api\.admin\.session;/package kkdugi.api;/' \
 -e 's/\bSessionMenuControllerTest\b/MenuControllerTest/g' \
 -e 's/\bSessionMenuController\b/MenuController/g' \
 -e 's#/api/v1\.0/session/menu#/api/v1.0/menu#g' $T/api/MenuControllerTest.java
sed -i 's#/api/v1\.0/session/menu#/api/v1.0/menu#' $T/core/security/filter/AuthenticationProcessingFilterTest.java
grep -n "session/menu\|MENU_URL =" $T/api/MenuControllerTest.java $T/core/security/filter/AuthenticationProcessingFilterTest.java
```
Expected: 두 파일 모두 `MENU_URL = "/api/v1.0/menu"`만 나오고 `session/menu`는 없다.

- [ ] **Step 2: JS 계약 테스트를 고치고 새 테스트를 추가한다 (실패하는 테스트)**

```bash
sed -i "s#'/kk/api/v1.0/session/menu'#'/kk/api/v1.0/menu'#" src/test/js/current-contract.test.mjs
cat >> src/test/js/current-contract.test.mjs <<'EOF'
test('menu route is allowed without a trailing slash while the retired session prefix and lookalikes are rejected',async()=>{
 const api=createApi({basePath:'/kk'},async()=>new Response('[]'));
 await api.request('/api/v1.0/menu','GET');
 await assert.rejects(()=>api.request('/api/v1.0/session/menu','GET'),/Invalid API path/);
 await assert.rejects(()=>api.request('/api/v1.0/menus','GET'),/Invalid API path/);
});
EOF
```

- [ ] **Step 3: 두 테스트가 실패하는지 확인한다**

Run: `./mvnw.cmd -B -ntp test -Dtest=MenuControllerTest`
Expected: `Tests run: 2, Failures: 2` (`/api/v1.0/menu`가 아직 없어 404 → `Status expected:<200> but was:<404>`), `BUILD FAILURE`.

Run: `node --test src/test/js/current-contract.test.mjs`
Expected: `fail 2` (기대 URL 불일치 + `/api/v1.0/menu`가 화이트리스트에서 `Invalid API path`).

- [ ] **Step 4: `Menu` 모델을 옮긴다**

```bash
M=src/main/java/kkdugi
mkdir -p $M/app/menu/models $M/app/menu/service
git mv $M/api/session/MenuTreeItem.java $M/app/menu/models/Menu.java
sed -i -E \
 -e 's/^package kkdugi\.api\.session;/package kkdugi.app.menu.models;/' \
 -e 's/\bMenuTreeItem\b/Menu/g' \
 -e 's#/api/v1\.0/session/menu#/api/v1.0/menu#g' $M/app/menu/models/Menu.java
grep -n "^package\|class Menu\|public Menu(" $M/app/menu/models/Menu.java
```
Expected: `package kkdugi.app.menu.models;`, `public class Menu implements Tree<Menu> {`, `public Menu(String id, ...`.

- [ ] **Step 5: `MenuService`와 `MenuController`를 만들고 옛 컨트롤러를 지운다**

Create `src/main/java/kkdugi/app/menu/service/MenuService.java`:
```java
package kkdugi.app.menu.service;

import java.util.List;

import org.springframework.stereotype.Service;

import kkdugi.app.menu.models.Menu;
import kkdugi.core.security.models.SessionMenu;
import kkdugi.core.util.CommonUtils;
import kkdugi.core.util.SessionUtils;
import kkdugi.core.util.TreeUtils;

/**
 * 로그인한 본인이 볼 수 있는 메뉴를 화면 내비게이션용 트리로 만든다. 세션에는
 * {@link SessionMenu}가 flat list로 저장돼 있다(화면 콘텐츠 로딩용
 * {@code program}/{@code authority} 필드 포함 — {@code kkdugi.web.admin.PragmaController}가
 * {@code SessionUtils.getMenu(menuId)}로 쓴다). 그 필드들은 내비게이션 응답에 필요/노출 대상이
 * 아니므로 {@link Menu}로 옮겨 담아 트리로 변환한다. 로그인하지 않은 요청은
 * {@link SessionUtils#getUser()}가 돌려주는 익명 사용자의 빈 메뉴 목록이라 빈 목록을 받는다.
 * DB를 읽지 않는다(세션 스냅샷 기반)라서 mapper가 없다. 관리자용 메뉴 CRUD는
 * {@code kkdugi.app.admin.menu.service.AdminMenuService}.
 */
@Service
public class MenuService {

    public List<Menu> tree() {
        List<Menu> items = SessionUtils.getUser().getMenus().stream()
                .map(MenuService::toMenu)
                .toList();
        return TreeUtils.convert(items);
    }

    private static Menu toMenu(SessionMenu menu) {
        return new Menu(menu.getId(), menu.getParentId(), menu.getTitle(), menu.getRemarks(),
                menu.getIcon(), menu.getSort(), CommonUtils.isNotEmpty(menu.getProgram()));
    }
}
```
Create `src/main/java/kkdugi/api/MenuController.java`:
```java
package kkdugi.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kkdugi.app.menu.models.Menu;
import kkdugi.app.menu.service.MenuService;

/** 사용자용 메뉴 조회(내 메뉴 트리). 관리자용 메뉴 CRUD는 {@code kkdugi.api.admin.AdminMenuController}. */
@RestController
@RequestMapping("/api/v1.0/menu")
public class MenuController {

    private final MenuService service;

    public MenuController(MenuService service) {
        this.service = service;
    }

    @GetMapping
    public List<Menu> menu() {
        return service.tree();
    }
}
```
```bash
git rm -q src/main/java/kkdugi/api/session/SessionMenuController.java
grep -rn "MenuTreeItem\|SessionMenuController\|kkdugi\.api\.session" src
```
Expected: grep 결과 없음.

- [ ] **Step 6: 프런트 URL과 화이트리스트를 고친다**

```bash
sed -i "s#'/api/v1.0/session/menu'#'/api/v1.0/menu'#" src/main/resources/static/js/api/index.mjs
grep -c "/api/v1.0/menu" src/main/resources/static/js/api/index.mjs
```
Expected: `1`.

`src/main/resources/static/js/api/http.mjs` 8번째 줄을 Edit 도구로 교체한다.

old:
```js
  if(!/^\/(?:api\/v1\.0\/(?:admin|auth|session)\/|pragma\/)/.test(path)||path.includes('..')||path.includes('\\'))throw new Error('Invalid API path');
```
new:
```js
  if(!/^\/(?:api\/v1\.0\/(?:(?:admin|auth)\/|menu(?:$|\?))|pragma\/)/.test(path)||path.includes('..')||path.includes('\\'))throw new Error('Invalid API path');
```

- [ ] **Step 7: 문서를 고친다**

```bash
cd /c/projects/kkdugi/docs/api
sed -i -E \
 -e 's#/api/v1\.0/session/menu#/api/v1.0/menu#g' \
 -e 's#get-apiv10sessionmenu#get-apiv10menu#g' \
 -e 's#kkdugi/api/session/SessionMenuController\.java#kkdugi/api/MenuController.java#' \
 -e 's#\[`SessionMenuController`\]#[`MenuController`]#' \
 -e 's#이 문서의 session/menu와#이 문서의 내 메뉴 트리 조회와#' session.md
grep -n "session/menu\|SessionMenuController" session.md README.md
```
Expected: `README.md`에 한 건(아래 Edit로 교체) 외에는 없다.

`session.md`의 "1. 내 메뉴 트리 조회" 첫 문단을 Edit 도구로 교체한다.

old:
```markdown
**`/api/v1.0/admin` 접두사 밖에 있다** — 로그인한 사용자 본인의 세션 정보를
다루는 것이지 "admin 리소스"(공통코드/메시지/메뉴 CRUD)가 아니라서
[auth.md](auth.md)의 로그인/로그아웃과 같은 이유로 이 접두사 밖이다.
```
new:
```markdown
**사용자용 API라 `/api/v1.0/menu`에 있다**(관리자용 메뉴 CRUD는 `/api/v1.0/admin/menu`,
[menu.md](menu.md)). 2026-09-19 이전에는 `/api/v1.0/session/menu`였으며 옛 경로 별칭은 없다.
```

`README.md`의 "공통 사항 > Base URL" 항목을 Edit 도구로 교체한다.

old:
```markdown
- **Base URL**: 도메인 API는 모두 `/api/v1.0/admin` 하위에 있다. 예외 셋 —
  [auth.md](auth.md)의 로그인/로그아웃(`/api/v1.0/auth/login`,
  `/api/v1.0/auth/logout`)과 [session.md](session.md)의 내 메뉴 트리 조회
  (`/api/v1.0/session/menu`)는 "admin 리소스"(공통코드/메시지/메뉴 CRUD)가
  아니라 로그인한 사용자 본인을 다루는 요청이라 이 접두사 밖에 있고,
  [session.md](session.md)의 Pragma 화면 조각 엔드포인트(`/pragma/{menuId}`)도
  예외다(`/api/v1.0` 프리픽스조차 없음) — 이유는 해당 문서에 설명.
  프런트엔드 `static/js/api/http.mjs`의 경로 화이트리스트도
  `/api/v1.0/admin/`·`/api/v1.0/auth/`·`/api/v1.0/session/`·`/pragma/` 네
  접두사만 허용한다.
```
new:
```markdown
- **Base URL**: 관리자용 도메인 API(공통코드/메시지/메뉴 CRUD)는 `/api/v1.0/admin` 하위에 있고,
  사용자용 조회 API는 `/api/v1.0/menu`([session.md](session.md)의 내 메뉴 트리 조회),
  `/api/v1.0/code`([code.md](code.md))처럼 `/api/v1.0` 바로 아래에 있다. 예외 —
  [auth.md](auth.md)의 로그인/로그아웃(`/api/v1.0/auth/login`, `/api/v1.0/auth/logout`)과
  [session.md](session.md)의 Pragma 화면 조각 엔드포인트(`/pragma/{menuId}`, `/api/v1.0`
  프리픽스조차 없음 — 이유는 해당 문서에 설명).
  프런트엔드 `static/js/api/http.mjs`의 경로 화이트리스트는
  `/api/v1.0/admin/`·`/api/v1.0/auth/`·`/api/v1.0/menu`·`/pragma/`만 허용한다
  (`/api/v1.0/code`는 프런트가 아직 쓰지 않아 넣지 않았다).
```

- [ ] **Step 8: 테스트가 통과하는지 확인한다**

```bash
cd /c/projects/kkdugi/kkdugi-admin
./mvnw.cmd -B -ntp test -Dtest='MenuControllerTest,AuthenticationProcessingFilterTest'
node --test src/test/js/*.test.mjs
```
Expected: Maven `Failures: 0, Errors: 0`, `BUILD SUCCESS` (MenuControllerTest 2건 포함). JS `pass 24, fail 0`(기준선 23 + 새 1).

이어서 전체 회귀: `./mvnw.cmd -B -ntp test` → Expected `Tests run: 146, Failures: 0`.

- [ ] **Step 9: 커밋한다**

```bash
git add -A
git commit -m "refactor(menu): serve the user menu tree from api.MenuController at /api/v1.0/menu

Moves api.session.{SessionMenuController,MenuTreeItem} to api.MenuController and
app.menu.{models.Menu,service.MenuService}, updates the front-end call and path
allowlist, and drops the old /api/v1.0/session/menu path.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Vb3CBKRkZDo8GoFJtAUgjT"
```

---

## Task 5: i18n 관리자 측 분리 — `AdminMessage*` 이름, `AdminMessageMapper`, core mapper 축소

**Files:**
- Move+rename (main): `app/admin/i18n/models/{MessageContent→AdminMessage, MessagePersistRequest→AdminMessagePersistRequest, MessageSearchParams→AdminMessageParams}.java`, `app/admin/i18n/exceptions/{MessageConflictException→AdminMessageConflictException, MessageValidationException→AdminMessageValidationException}.java`, `app/admin/i18n/service/MessageAdminService.java→AdminMessageService.java`, `api/admin/i18n/MessageAdminController.java→api/admin/AdminMessageController.java`
- Move (main): `core/i18n/models/{MessageCode,MessageCodeRow}.java → app/admin/i18n/models/`
- Create: `app/admin/i18n/mapper/AdminMessageMapper.java`, `src/main/resources/mapper/postgres/app/admin/i18n/AdminMessageMapper.xml`
- Modify: `core/i18n/mapper/I18nMessageMapper.java`(축소), `src/main/resources/mapper/postgres/core/i18n/I18nMessageMapper.xml`(축소)
- Test (move+edit): `app/admin/i18n/service/MessageAdminServiceTest.java → AdminMessageServiceTest.java`, `api/admin/i18n/MessageAdminControllerTest.java → api/admin/AdminMessageControllerTest.java`, `core/i18n/models/MessageCodeTest.java → app/admin/i18n/models/MessageCodeTest.java`, `core/i18n/service/KkdugiMessageSourceTest.java`(픽스처를 JdbcTemplate으로)

**Interfaces:**
- Consumes: `kkdugi.core.i18n.models.I18nMessage`(core에 유지), `kkdugi.core.i18n.service.KkdugiMessageSource#refresh(String msgCode, String langCode)`.
- Produces: `kkdugi.app.admin.i18n.mapper.AdminMessageMapper` — `List<MessageCodeRow> searchDistinctCodes(String msgCode, String msgText, int offset, int pageSize)`, `List<I18nMessage> findByCode(String msgCode)`, `List<I18nMessage> findByCodes(List<String> msgCodes)`, `int insert(I18nMessage)`, `int update(I18nMessage)`, `int deleteByCode(String msgCode)`. `kkdugi.core.i18n.mapper.I18nMessageMapper`는 `List<I18nMessage> selectAll()`, `Optional<I18nMessage> findByCodeAndLang(String msgCode, String langCode)`만 남는다. `AdminMessage`(구 `MessageContent`), `AdminMessageParams`, `AdminMessagePersistRequest`, `AdminMessageService`(`search(AdminMessageParams)`, `persist(AdminMessagePersistRequest)`).

- [ ] **Step 1: 테스트를 새 위치·이름으로 옮기고 새 구조에 맞게 고친다 (실패하는 테스트)**

```bash
cd /c/projects/kkdugi/kkdugi-admin
T=src/test/java/kkdugi
git mv $T/app/admin/i18n/service/MessageAdminServiceTest.java $T/app/admin/i18n/service/AdminMessageServiceTest.java
git mv $T/api/admin/i18n/MessageAdminControllerTest.java $T/api/admin/AdminMessageControllerTest.java
mkdir -p $T/app/admin/i18n/models
git mv $T/core/i18n/models/MessageCodeTest.java $T/app/admin/i18n/models/MessageCodeTest.java

cat > "$TEMP/i18n-rename.sed" <<'EOF'
s/\bMessageContent\b/AdminMessage/g
s/\bMessagePersistRequest\b/AdminMessagePersistRequest/g
s/\bMessageSearchParams\b/AdminMessageParams/g
s/\bMessageConflictException\b/AdminMessageConflictException/g
s/\bMessageValidationException\b/AdminMessageValidationException/g
s/\bMessageAdminServiceTest\b/AdminMessageServiceTest/g
s/\bMessageAdminControllerTest\b/AdminMessageControllerTest/g
s/\bMessageAdminService\b/AdminMessageService/g
s/\bMessageAdminController\b/AdminMessageController/g
s/kkdugi\.core\.i18n\.models\.MessageCodeRow/kkdugi.app.admin.i18n.models.MessageCodeRow/g
s/kkdugi\.core\.i18n\.models\.MessageCode\b/kkdugi.app.admin.i18n.models.MessageCode/g
s/kkdugi\.api\.admin\.i18n/kkdugi.api.admin/g
EOF
cat > "$TEMP/i18n-mapper.sed" <<'EOF'
s/kkdugi\.core\.i18n\.mapper\.I18nMessageMapper/kkdugi.app.admin.i18n.mapper.AdminMessageMapper/g
s/\bI18nMessageMapper\b/AdminMessageMapper/g
EOF
sed -i -E -f "$TEMP/i18n-rename.sed" -f "$TEMP/i18n-mapper.sed" \
  $T/app/admin/i18n/service/AdminMessageServiceTest.java $T/api/admin/AdminMessageControllerTest.java
sed -i 's/^package kkdugi\.core\.i18n\.models;/package kkdugi.app.admin.i18n.models;/' $T/app/admin/i18n/models/MessageCodeTest.java
```

`KkdugiMessageSourceTest.java`는 core 테스트라 `app.admin`의 mapper를 쓰면 안 되므로 픽스처를 `JdbcTemplate`으로 넣도록 Edit 도구로 다섯 곳을 고친다.

(1) old:
```java
import java.time.LocalDateTime;
import java.util.Locale;
```
new:
```java
import java.util.Locale;
```

(2) old:
```java
import kkdugi.KkdugiAdminApplication;
import kkdugi.core.i18n.mapper.I18nMessageMapper;
import kkdugi.core.i18n.models.I18nMessage;
```
new:
```java
import kkdugi.KkdugiAdminApplication;
```
(2-b) old:
```java
import org.springframework.boot.test.context.SpringBootTest;
```
new:
```java
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
```

(3) old:
```java
    @Autowired
    private I18nMessageMapper mapper;

    @AfterEach
    void cleanUp() {
        mapper.delete("test.msg.temp", "ko_KR");
        mapper.delete("test.msg.args", "ko_KR");
```
new:
```java
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM kkdugi_i18n_msg WHERE msg_cd IN (?, ?) AND lang_cd = ?",
                "test.msg.temp", "test.msg.args", "ko_KR");
```

(4) old:
```java
        I18nMessage message = new I18nMessage("test.msg.temp", "ko_KR", "DB 메시지");
        message.setCreatedAt(LocalDateTime.now());
        message.setCreatorId("SYSTEM");
        mapper.insert(message);
```
new:
```java
        insertMessage("test.msg.temp", "DB 메시지");
```
old:
```java
        I18nMessage message = new I18nMessage("test.msg.args", "ko_KR", "{0}님 환영합니다");
        message.setCreatedAt(LocalDateTime.now());
        message.setCreatorId("SYSTEM");
        mapper.insert(message);
```
new:
```java
        insertMessage("test.msg.args", "{0}님 환영합니다");
```

(5) old (파일 끝):
```java
        assertThat(result).isEqualTo("철수님 환영합니다");
    }
}
```
new:
```java
        assertThat(result).isEqualTo("철수님 환영합니다");
    }

    private void insertMessage(String code, String text) {
        jdbcTemplate.update("INSERT INTO kkdugi_i18n_msg (msg_cd, lang_cd, msg_val, reg_id) VALUES (?, ?, ?, ?)",
                code, "ko_KR", text, "SYSTEM");
    }
}
```

- [ ] **Step 2: 컴파일이 실패하는지 확인한다**

Run: `./mvnw.cmd -B -ntp test-compile`
Expected: `BUILD FAILURE` — `package kkdugi.app.admin.i18n.mapper does not exist`, `cannot find symbol AdminMessage`.

- [ ] **Step 3: main 소스를 옮기고 이름을 바꾼다**

```bash
cd /c/projects/kkdugi/kkdugi-admin
M=src/main/java/kkdugi
mkdir -p $M/app/admin/i18n/mapper
git mv $M/core/i18n/models/MessageCode.java    $M/app/admin/i18n/models/MessageCode.java
git mv $M/core/i18n/models/MessageCodeRow.java $M/app/admin/i18n/models/MessageCodeRow.java
git mv $M/app/admin/i18n/models/MessageContent.java        $M/app/admin/i18n/models/AdminMessage.java
git mv $M/app/admin/i18n/models/MessagePersistRequest.java $M/app/admin/i18n/models/AdminMessagePersistRequest.java
git mv $M/app/admin/i18n/models/MessageSearchParams.java   $M/app/admin/i18n/models/AdminMessageParams.java
git mv $M/app/admin/i18n/exceptions/MessageConflictException.java   $M/app/admin/i18n/exceptions/AdminMessageConflictException.java
git mv $M/app/admin/i18n/exceptions/MessageValidationException.java $M/app/admin/i18n/exceptions/AdminMessageValidationException.java
git mv $M/app/admin/i18n/service/MessageAdminService.java $M/app/admin/i18n/service/AdminMessageService.java
git mv $M/api/admin/i18n/MessageAdminController.java $M/api/admin/AdminMessageController.java

sed -i 's/^package kkdugi\.core\.i18n\.models;/package kkdugi.app.admin.i18n.models;/' \
  $M/app/admin/i18n/models/MessageCode.java $M/app/admin/i18n/models/MessageCodeRow.java
sed -i -E -f "$TEMP/i18n-rename.sed" \
  $M/app/admin/i18n/models/*.java $M/app/admin/i18n/exceptions/*.java \
  $M/app/admin/i18n/service/AdminMessageService.java $M/api/admin/AdminMessageController.java
sed -i -E -f "$TEMP/i18n-mapper.sed" $M/app/admin/i18n/service/AdminMessageService.java
grep -n "^package" $M/app/admin/i18n/models/MessageCode.java $M/api/admin/AdminMessageController.java
grep -n "Mapper" $M/app/admin/i18n/service/AdminMessageService.java
```
Expected: `package kkdugi.app.admin.i18n.models;` / `package kkdugi.api.admin;`, 서비스에는 `import kkdugi.app.admin.i18n.mapper.AdminMessageMapper;`, `private final AdminMessageMapper mapper;`, 생성자 `AdminMessageMapper mapper`.

- [ ] **Step 4: `AdminMessageMapper` 인터페이스와 XML을 만든다**

Create `src/main/java/kkdugi/app/admin/i18n/mapper/AdminMessageMapper.java`:
```java
package kkdugi.app.admin.i18n.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import kkdugi.app.admin.i18n.models.MessageCodeRow;
import kkdugi.core.i18n.models.I18nMessage;

@Mapper
public interface AdminMessageMapper {

    List<MessageCodeRow> searchDistinctCodes(@Param("msgCode") String msgCode,
                                             @Param("msgText") String msgText,
                                             @Param("offset") int offset,
                                             @Param("pageSize") int pageSize);

    List<I18nMessage> findByCode(@Param("msgCode") String msgCode);

    List<I18nMessage> findByCodes(@Param("msgCodes") List<String> msgCodes);

    int insert(I18nMessage message);

    int update(I18nMessage message);

    int deleteByCode(@Param("msgCode") String msgCode);
}
```
Create `src/main/resources/mapper/postgres/app/admin/i18n/AdminMessageMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.app.admin.i18n.mapper.AdminMessageMapper">

  <resultMap id="i18nMessageResultMap" type="kkdugi.core.i18n.models.I18nMessage"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="msgCode" column="msg_cd"/>
    <id property="langCode" column="lang_cd"/>
    <result property="msgText" column="msg_val"/>
  </resultMap>

  <resultMap id="messageCodeRowResultMap" type="kkdugi.app.admin.i18n.models.MessageCodeRow">
    <id property="code" column="code"/>
    <result property="totalSize" column="total_size"/>
  </resultMap>

  <sql id="columns">
<![CDATA[
msg_cd, lang_cd, msg_val, reg_dtm, reg_id, upd_dtm, upd_id
]]>
  </sql>

  <!--
    * QueryID=searchDistinctCodes
    * Description=Search distinct message codes with paging (total count via
    *             window function in the same query, no separate count call)
    -->
  <select id="searchDistinctCodes" resultMap="messageCodeRowResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.i18n.mapper.AdminMessageMapper.searchDistinctCodes */
SELECT code, COUNT(*) OVER() AS total_size
FROM (
    SELECT DISTINCT msg_cd AS code
    FROM kkdugi_i18n_msg
]]>
    <where>
      <if test="msgCode != null and msgCode != ''">
<![CDATA[
AND msg_cd LIKE '%' || #{msgCode} || '%'
]]>
      </if>
      <if test="msgText != null and msgText != ''">
<![CDATA[
AND msg_val LIKE '%' || #{msgText} || '%'
]]>
      </if>
    </where>
<![CDATA[
) distinct_codes
ORDER BY code
OFFSET #{offset} LIMIT #{pageSize}
]]>
  </select>

  <!--
    * QueryID=findByCode
    * Description=Find all language rows for one message code
    -->
  <select id="findByCode" resultMap="i18nMessageResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.i18n.mapper.AdminMessageMapper.findByCode */
SELECT
]]>
    <include refid="columns"/>
<![CDATA[
FROM kkdugi_i18n_msg
WHERE msg_cd = #{msgCode}
]]>
  </select>

  <!--
    * QueryID=findByCodes
    * Description=Find all language rows for a set of message codes
    -->
  <select id="findByCodes" resultMap="i18nMessageResultMap">
<![CDATA[
/* QueryID=kkdugi.app.admin.i18n.mapper.AdminMessageMapper.findByCodes */
SELECT
]]>
    <include refid="columns"/>
<![CDATA[
FROM kkdugi_i18n_msg
WHERE msg_cd IN
]]>
    <foreach item="code" collection="msgCodes" open="(" separator="," close=")">
      #{code}
    </foreach>
<![CDATA[
ORDER BY msg_cd, lang_cd
]]>
  </select>

  <!--
    * QueryID=insert
    * Description=Insert one message row
    -->
  <insert id="insert" parameterType="kkdugi.core.i18n.models.I18nMessage">
<![CDATA[
/* QueryID=kkdugi.app.admin.i18n.mapper.AdminMessageMapper.insert */
INSERT INTO kkdugi_i18n_msg (msg_cd, lang_cd, msg_val, reg_dtm, reg_id)
VALUES (#{msgCode}, #{langCode}, #{msgText}, #{createdAt}, #{creatorId})
]]>
  </insert>

  <!--
    * QueryID=update
    * Description=Update one message row
    -->
  <update id="update" parameterType="kkdugi.core.i18n.models.I18nMessage">
<![CDATA[
/* QueryID=kkdugi.app.admin.i18n.mapper.AdminMessageMapper.update */
UPDATE kkdugi_i18n_msg
SET msg_val = #{msgText},
    upd_dtm = #{updatedAt},
    upd_id  = #{updaterId}
WHERE msg_cd = #{msgCode}
  AND lang_cd = #{langCode}
]]>
  </update>

  <!--
    * QueryID=deleteByCode
    * Description=Delete all language rows for one message code
    -->
  <delete id="deleteByCode">
<![CDATA[
/* QueryID=kkdugi.app.admin.i18n.mapper.AdminMessageMapper.deleteByCode */
DELETE FROM kkdugi_i18n_msg
WHERE msg_cd = #{msgCode}
]]>
  </delete>

</mapper>
```

- [ ] **Step 5: core `I18nMessageMapper`를 `MessageSource`가 쓰는 메서드만 남기고 줄인다**

Overwrite `src/main/java/kkdugi/core/i18n/mapper/I18nMessageMapper.java`:
```java
package kkdugi.core.i18n.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.core.i18n.models.I18nMessage;

import java.util.List;
import java.util.Optional;

/** Spring {@code MessageSource}(캐시 적재/갱신)가 쓰는 read 전용 쿼리. 관리자 쓰기는 {@code AdminMessageMapper}. */
@Mapper
public interface I18nMessageMapper {

    List<I18nMessage> selectAll();

    Optional<I18nMessage> findByCodeAndLang(@Param("msgCode") String msgCode, @Param("langCode") String langCode);
}
```
Overwrite `src/main/resources/mapper/postgres/core/i18n/I18nMessageMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kkdugi.core.i18n.mapper.I18nMessageMapper">

  <resultMap id="i18nMessageResultMap" type="kkdugi.core.i18n.models.I18nMessage"
             extends="kkdugi.core.models.CommonMapper.baseResultMap">
    <id property="msgCode" column="msg_cd"/>
    <id property="langCode" column="lang_cd"/>
    <result property="msgText" column="msg_val"/>
  </resultMap>

  <sql id="columns">
<![CDATA[
msg_cd, lang_cd, msg_val, reg_dtm, reg_id, upd_dtm, upd_id
]]>
  </sql>

  <!--
    * QueryID=selectAll
    * Description=Select all i18n messages (for cache load)
    -->
  <select id="selectAll" resultMap="i18nMessageResultMap">
<![CDATA[
/* QueryID=kkdugi.core.i18n.mapper.I18nMessageMapper.selectAll */
SELECT
]]>
    <include refid="columns"/>
<![CDATA[
FROM kkdugi_i18n_msg
]]>
  </select>

  <!--
    * QueryID=findByCodeAndLang
    * Description=Find one message by code and language
    -->
  <select id="findByCodeAndLang" resultMap="i18nMessageResultMap">
<![CDATA[
/* QueryID=kkdugi.core.i18n.mapper.I18nMessageMapper.findByCodeAndLang */
SELECT
]]>
    <include refid="columns"/>
<![CDATA[
FROM kkdugi_i18n_msg
WHERE msg_cd = #{msgCode}
  AND lang_cd = #{langCode}
]]>
  </select>

</mapper>
```
```bash
grep -rn "I18nMessageMapper" src/main src/test | grep -v "core/i18n"
grep -rn "kkdugi\.core\.i18n\.models\.\(MessageCode\|MessageCodeRow\)" src
```
Expected: 첫 grep 결과 없음(`I18nMessageMapper`는 core/i18n 안에서만 쓰인다), 둘째도 결과 없음.

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./mvnw.cmd -B -ntp test -Dtest='AdminMessageServiceTest,AdminMessageControllerTest,KkdugiMessageSourceTest,MessageCodeTest'`
Expected: `Tests run: 17, Failures: 0, Errors: 0` (서비스 6 + 컨트롤러 4 + MessageSource 4 + MessageCode 3), `BUILD SUCCESS`.

이어서 전체 회귀: `./mvnw.cmd -B -ntp test` → Expected `Tests run: 146, Failures: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: 커밋한다**

```bash
git add -A
git commit -m "refactor(i18n): split admin message writes into app.admin.i18n and trim the core mapper

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Vb3CBKRkZDo8GoFJtAUgjT"
```

---

## Task 6: 문서 정리와 최종 검증

살아있는 문서(`docs/api`, `CLAUDE.md`, `docs/conventions`)는 새 구조로 고치고, 당시 결정의 기록인 ADR/설계 문서는 본문을 고치지 않고 ADR-0016 참조 노트만 붙인다. 마지막에 전체 테스트와 의존 규칙(`app.admin` ↔ `app.<기능>` 상호 import 금지, core → app 금지)을 grep으로 검증한다.

**Files:**
- Modify: `docs/api/{common-code,menu,i18n-message}.md`, `CLAUDE.md`, `docs/conventions/common-base-model.md`, `docs/adr/README.md`
- Modify(노트만 추가): `docs/adr/{0010,0011,0012,0014,0015}-*.md`, `docs/{i18n-system-design,common-code-system-design}.md`

**Interfaces:**
- Consumes: Task 1·3·5가 만든 `$TEMP/{code,menu,i18n}-rename.sed`(없으면 각 Task의 Step 1 블록으로 다시 만든다).
- Produces: 없음(문서/검증 전용).

- [ ] **Step 1: `docs/api`, `CLAUDE.md`, conventions의 옛 클래스 이름·경로를 일괄 치환한다**

```bash
cd /c/projects/kkdugi
cat > "$TEMP/docs-path.sed" <<'EOF'
s#kkdugi/api/admin/(code|menu|i18n)/#kkdugi/api/admin/#g
EOF
sed -i -E -f "$TEMP/code-rename.sed" -f "$TEMP/menu-rename.sed" -f "$TEMP/i18n-rename.sed" -f "$TEMP/docs-path.sed" \
  docs/api/common-code.md docs/api/menu.md docs/api/i18n-message.md CLAUDE.md docs/conventions/common-base-model.md
grep -n "AdminCodeController\|AdminMenuController\|AdminMessageController" docs/api/common-code.md docs/api/menu.md docs/api/i18n-message.md | head
```
Expected: 세 문서 각각 `구현: [`AdminXxxController`](../../kkdugi-admin/src/main/java/kkdugi/api/admin/AdminXxxController.java) /` 형태의 줄이 나온다.

- [ ] **Step 2: `CLAUDE.md`를 고친다 (패키지 트리, 의존 규칙, mvnw 서술, 경로 예시, 메뉴 노트)**

`CLAUDE.md`는 LF, 아래 스크립트는 줄바꿈 종류와 무관하다. 스크립트를 스크래치 파일에 저장하지 않고 그대로 실행한다.

````bash
cd /c/projects/kkdugi
python - <<'PY'
import re

p = 'CLAUDE.md'
s = open(p, encoding='utf-8', newline='').read()
nl = '\r\n' if '\r\n' in s else '\n'

tree = '''## Package structure

```
kkdugi
├─ core             — shared infrastructure only (no feature CRUD)
│   ├─ models       — BaseModel, BaseParams, Page<T>, Tree
│   ├─ util         — CommonUtils, DateUtils, SessionUtils, SerialUtils, MessageUtils, TreeUtils
│   ├─ enums        — CodeEnums, AuthorityType, PasswordStatus, Rbac, UserStatus
│   ├─ mybatis      — CodeEnumTypeHandler, SessionUserTypeHandler
│   ├─ serial       — SerialConfig + mapper/service (calls the fn_get_serial DB function)
│   ├─ exceptions   — ExceptionMessage, Restful*Exception(s), advice
│   ├─ security     — JWT auth, session (SessionUser/SessionMenu), filters, config
│   └─ i18n         — Spring MessageSource infrastructure only: I18nMessage,
│                     I18nMessageMapper{selectAll,findByCodeAndLang},
│                     KkdugiMessageSource, I18nMessageSourceConfig
├─ app
│   ├─ code         — user-facing (use=Y, language-resolved names):
│   │                 models(Code, CodeParams) / mapper(CodeMapper) / service(CodeService)
│   ├─ menu         — user-facing (session-based tree, no mapper):
│   │                 models(Menu implements core.models.Tree) / service(MenuService)
│   └─ admin
│       ├─ code     — models(AdminCode, AdminCodeParams, AdminCodePersistRequest, AdminCodeLocale,
│       │             CodeBase, CodeLang, CodeValue), exceptions(AdminCode{Validation,Conflict}Exception),
│       │             mapper(AdminCodeMapper), service(AdminCodeService — SerialUtils.next(config))
│       ├─ menu     — models(AdminMenu implements Tree, AdminMenuLocale, AdminMenuPersistRequest,
│       │             MenuBase, MenuLang), exceptions(AdminMenu*), mapper(AdminMenuMapper),
│       │             service(AdminMenuService — SerialUtils.next(config))
│       └─ i18n     — models(AdminMessage, AdminMessageParams, AdminMessagePersistRequest,
│                     MessageCode, MessageCodeRow), exceptions(AdminMessage*),
│                     mapper(AdminMessageMapper), service(AdminMessageService)
├─ api              — controllers stay flat (not split into subpackages)
│   ├─ CodeController (/api/v1.0/code), MenuController (/api/v1.0/menu)
│   └─ admin        — AdminCodeController, AdminMenuController, AdminMessageController
│                     (/api/v1.0/admin/{code,menu,i18n}); validation/conflict exceptions
│                     map to `ExceptionMessage`, see `kkdugi.core.exceptions`
└─ web.admin        — Thymeleaf/Pragma entry points (IndexController, LoginController, PragmaController)
```

**Dependency rule**: `api → app → core`. `app.admin.<feature>` and `app.<feature>` never
import each other (each depends only on `core`), and `core` never imports `app`. What a user
sees and what admin needs differ, so the two sides deliberately keep separate
models/mappers/services; each `app.<feature>` is meant to be extractable into its own library
later. See [ADR-0016](docs/adr/0016-app-and-admin-feature-split.md).'''.replace('\n', nl)

m = re.search(r'## Package structure\r?\n\r?\n```\r?\n.*?\r?\n```', s, re.S)
assert m, 'package structure block not found'
s = s[:m.start()] + tree + s[m.end():]

def sub_once(pattern, repl, flags=0):
    global s
    new, n = re.subn(pattern, lambda _m: repl, s, count=1, flags=flags)
    assert n == 1, 'pattern not found: ' + pattern
    s = new

sub_once(r"Maven isn't on PATH.*?first for Postgres\.",
         "Run tests with the project wrapper from `kkdugi-admin/`: `./mvnw.cmd -B -ntp test` (Java) and "
         "`node --test src/test/js/*.test.mjs` (front-end contract tests — pass a glob, not the directory). "
         "Requires `docker-compose up -d` first for Postgres.", re.S)
s = s.replace('`kkdugi.core.code.mapper.CodeBaseMapper`', '`kkdugi.app.code.mapper.CodeMapper`')
s = s.replace('`mapper/postgres/core/code/CodeBaseMapper.xml`', '`mapper/postgres/app/code/CodeMapper.xml`')
sub_once(r'Menu management \(.*?\)',
         'Menu management (`kkdugi.app.admin.menu`/`kkdugi.api.admin`; the user-facing tree is '
         '`kkdugi.app.menu`/`kkdugi.api.MenuController` at `/api/v1.0/menu`)', re.S)

open(p, 'w', encoding='utf-8', newline='').write(s)
print('CLAUDE.md updated')
PY
grep -nE "CodeAdmin|MessageAdmin|MenuAdmin|SessionMenuController|MenuTreeItem|CodeBaseMapper|CodeLangMapper|MenuBaseMapper|MenuLangMapper|CodeContent|MenuContent|MessageContent|core\.code|core\.menu|mapper/postgres/core/(code|menu)|no project-level" CLAUDE.md
````
Expected: `CLAUDE.md updated`, 마지막 grep 결과 없음. (`python`이 `Microsoft Store` 스텁이라 실패하면 `python3`로 바꿔 실행한다.)

- [ ] **Step 3: `docs/conventions/common-base-model.md`를 고친다**

이 파일은 CRLF다. 같은 방식으로 줄바꿈에 무관하게 처리한다.

```bash
cd /c/projects/kkdugi
python - <<'PY'
import re

p = 'docs/conventions/common-base-model.md'
s = open(p, encoding='utf-8', newline='').read()
nl = '\r\n' if '\r\n' in s else '\n'

def sub_once(pattern, repl, flags=0):
    global s
    new, n = re.subn(pattern, lambda _m: repl.replace('\n', nl), s, count=1, flags=flags)
    assert n == 1, 'pattern not found: ' + pattern
    s = new

sub_once(r'- 최초 적용 사례: `kkdugi\.core\.i18n`/`kkdugi\.app\.admin\.i18n`',
         '- 최초 적용 사례: `kkdugi.core.i18n`/`kkdugi.app.admin.i18n`\n'
         '- 사용자용/관리자용 분리(2026-09-19): [ADR-0016](../adr/0016-app-and-admin-feature-split.md)')

sub_once(r'예시 \(`kkdugi\.core\.i18n`, `kkdugi\.app\.admin\.i18n`\):.*?(?=컨트롤러 계층\()',
         '예시 (사용자용 `kkdugi.app.code`, 관리자용 `kkdugi.app.admin.i18n`, `MessageSource` 인프라인 `kkdugi.core.i18n`):\n'
         '\n'
         '```\n'
         'kkdugi.app.code                      ← 사용자용 (관리자용 app.admin.code와 모델·mapper 공유 안 함)\n'
         '├─ models   — Code, CodeParams\n'
         '├─ mapper   — CodeMapper\n'
         '└─ service  — CodeService\n'
         '\n'
         'kkdugi.app.admin.i18n                ← 관리자용\n'
         '├─ models      — AdminMessageParams, AdminMessage, AdminMessagePersistRequest, MessageCode(코드 검증), MessageCodeRow\n'
         '├─ exceptions  — AdminMessageValidationException, AdminMessageConflictException\n'
         '├─ mapper      — AdminMessageMapper\n'
         '└─ service     — AdminMessageService\n'
         '\n'
         'kkdugi.core.i18n                     ← Spring MessageSource 인프라만\n'
         '├─ models   — I18nMessage\n'
         '├─ mapper   — I18nMessageMapper (selectAll, findByCodeAndLang)\n'
         '├─ service  — KkdugiMessageSource\n'
         '└─ config   — I18nMessageSourceConfig\n'
         '```\n'
         '\n', re.S)

s = s.replace('`kkdugi.core.code.mapper.CodeBaseMapper`', '`kkdugi.app.code.mapper.CodeMapper`')
s = s.replace('`mapper/postgres/core/code/CodeBaseMapper.xml`', '`mapper/postgres/app/code/CodeMapper.xml`')
sub_once(r'전체 예시는 `I18nMessageMapper\.xml`.*?맞춰져 있다\.',
         '전체 예시는 `AdminCodeMapper.xml`/`AdminMenuMapper.xml`/`CodeMapper.xml`/`SerialMapper.xml`을 참고한다 — '
         '이 서식으로 맞춰져 있다.', re.S)

s = s.rstrip('\r\n') + nl + nl + '\n'.join([
    '## 사용자용(`app.<기능>`)과 관리자용(`app.admin.<기능>`) 분리 (2026-09-19)',
    '',
    '- 사용자에게 보이는 데이터와 관리자 기능이 필요로 하는 데이터는 다르므로 모델·mapper·서비스를 공유하지 않는다.',
    '  같은 테이블을 읽는 쿼리가 양쪽 mapper에 각각 있는 것은 의도된 중복이다.',
    '- 의존 방향은 `api → app → core`. `app.admin.<기능>`과 `app.<기능>`은 서로 import하지 않고 `core`만 의존한다.',
    '  `core`는 `app`을 import하지 않는다(그래서 `KkdugiMessageSourceTest` 같은 core 테스트도 `app.admin` mapper를 쓰지 않는다).',
    '- `app.admin` 쪽 mapper/서비스/컨트롤러/콘텐츠·파라미터·요청·예외 클래스에는 `Admin` 접두사를 붙인다. DB 행 모델',
    '  (`CodeBase`, `CodeLang`, `MenuBase`, `MenuLang`)과 검증기(`CodeValue`, `MessageCode`)는 이름을 유지한다.',
    '- 관리자 `Admin<기능>Mapper`는 read+write를 한 인터페이스/XML에 둔다. 사용자용 mapper는 read 전용이다.',
    '- 컨트롤러: 사용자용 `kkdugi.api.<Feature>Controller`(`/api/v1.0/<feature>`), 관리자용 `kkdugi.api.admin.Admin<Feature>Controller`',
    '  (`/api/v1.0/admin/<feature>`). 자세한 배경은 [ADR-0016](../adr/0016-app-and-admin-feature-split.md).',
    '',
]).replace('\n', nl)

open(p, 'w', encoding='utf-8', newline='').write(s)
print('conventions updated')
PY
grep -nE "CodeAdmin|MessageAdmin|MenuAdmin|CodeBaseMapper|CodeLangMapper|CodeContent|MessageContent|MessageSearchParams|core/code|mapper/postgres/core/code" docs/conventions/common-base-model.md
```
Expected: `conventions updated`, 마지막 grep 결과 없음.

- [ ] **Step 4: ADR/설계 문서에 ADR-0016 참조 노트를 붙이고 ADR 인덱스에 행을 추가한다**

```bash
cd /c/projects/kkdugi
python - <<'PY'
import glob, re

NOTE = ('> **패키지 구조 갱신 (2026-09-19):** 이 문서가 서술하는 `core.<기능>`/`app.admin.<기능>` 배치는 '
        '[ADR-0016]({link})으로 사용자용(`app.<기능>`)과 관리자용(`app.admin.<기능>`)을 분리하는 구조로 바뀌었다. '
        '아래 내용은 당시 결정의 기록이다.')

targets = {
    'adr/0016-app-and-admin-feature-split.md': [
        'docs/i18n-system-design.md', 'docs/common-code-system-design.md'],
    '0016-app-and-admin-feature-split.md': [
        f for pat in ('0010', '0011', '0012', '0014', '0015') for f in glob.glob(f'docs/adr/{pat}-*.md')],
}
for link, files in targets.items():
    assert files, link
    for p in files:
        s = open(p, encoding='utf-8', newline='').read()
        if '패키지 구조 갱신 (2026-09-19)' in s:
            continue
        nl = '\r\n' if '\r\n' in s else '\n'
        first, sep, rest = s.partition(nl)
        s = first + nl + nl + NOTE.format(link=link) + nl + (rest if rest.startswith(nl) else nl + rest)
        open(p, 'w', encoding='utf-8', newline='').write(s)
        print('noted', p)

p = 'docs/adr/README.md'
s = open(p, encoding='utf-8', newline='').read()
nl = '\r\n' if '\r\n' in s else '\n'
row = ('| [0016](0016-app-and-admin-feature-split.md) | 사용자용(app.<기능>)과 관리자용(app.admin.<기능>) 기능 분리, '
       '세션 메뉴 /api/v1.0/menu 이전 | Accepted |')
if '0016-app-and-admin-feature-split' not in s:
    s, n = re.subn(r'(\| \[0015\][^\r\n]*)', lambda m: m.group(1) + nl + row, s, count=1)
    assert n == 1
    open(p, 'w', encoding='utf-8', newline='').write(s)
    print('index updated')
PY
git diff --stat -- docs/adr | tail -3
```
Expected: `noted ...` 7줄(ADR 5 + 설계 문서 2), `index updated`.

- [ ] **Step 5: 전체 테스트를 돌린다**

```bash
cd /c/projects/kkdugi/kkdugi-admin
./mvnw.cmd -B -ntp test
node --test src/test/js/*.test.mjs
```
Expected: Maven `Tests run: 146, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. JS `pass 24`, `fail 0`.

- [ ] **Step 6: 옛 구조가 남지 않았는지, 의존 규칙이 지켜지는지 grep으로 검증한다**

```bash
cd /c/projects/kkdugi/kkdugi-admin
find src -type d -empty -print -delete
echo "--- 옛 이름 잔존 (없어야 함)"
grep -rnE "kkdugi\.core\.(code|menu)\b|kkdugi\.core\.i18n\.models\.MessageCode|kkdugi\.api\.session|kkdugi\.api\.admin\.(code|menu|i18n|session)|CodeAdmin(Service|Controller)|MenuAdmin(Service|Controller)|MessageAdmin(Service|Controller)|SessionMenuController|MenuTreeItem|\bCodeContent\b|\bMenuContent\b|\bMessageContent\b" src
echo "--- 의존 규칙 위반 (없어야 함)"
grep -rln "kkdugi\.app\.admin" src/main/java/kkdugi/app --include=*.java | grep -v "^src/main/java/kkdugi/app/admin/"
grep -rln "import kkdugi\.app\.\(code\|menu\)" src/main/java/kkdugi/app/admin --include=*.java
grep -rln "kkdugi\.app\." src/main/java/kkdugi/core --include=*.java
echo "--- 최종 구조"
find src/main/java/kkdugi/app src/main/java/kkdugi/api -name "*.java" | sort
find src/main/resources/mapper -name "*.xml" | sort
```
Expected: 세 grep 구간 모두 출력 없음. 최종 구조에 `app/code/{models,mapper,service}`, `app/menu/{models,service}`, `app/admin/{code,menu,i18n}/...`, `api/{CodeController,MenuController}.java`, `api/admin/Admin{Code,Menu,Message}Controller.java`가 있고, mapper XML은 `mapper/postgres/app/code/CodeMapper.xml`, `app/admin/{code,menu,i18n}/Admin*Mapper.xml`, `core/{i18n/I18nMessageMapper,models/CommonMapper,security/*,serial/SerialMapper}.xml`이다.

- [ ] **Step 7: 커밋한다**

```bash
cd /c/projects/kkdugi
git add -A
git commit -m "docs: update package structure, conventions and API docs for the app/app.admin split

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Vb3CBKRkZDo8GoFJtAUgjT"
git status -sb | head -3
```
Expected: 작업 트리가 깨끗하다. push는 사용자가 요청할 때 한다(`git push`, 업스트림은 이미 `origin/refactor/app-structure`).
