# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

kkdugi-admin is a Java/Spring Boot admin system. Per the project owner, the
long-term goal is a program built around workflows driven by collaboration
between multiple AI agents, split into an admin system (this project) and a
separate AI agent execution/runtime part (not started). Backend-only for
now — frontend work is handled elsewhere; do not add templates/UI here
unless explicitly asked.

The REST API contract for the whole admin system (common codes, messages,
menu, authority, users) was originally defined by the project owner
directly in `docs/api-define-admin.md`. That file was deleted 2026-09-18
per the owner's instruction; its content is preserved at
[docs/archive/api-define-admin.md](docs/archive/api-define-admin.md) for
reference only — it is **not** a live contract to implement against as-is
(see that file's header note). For common codes, messages, and menu, the
implemented-and-current source of truth is now
[docs/api/common-code.md](docs/api/common-code.md),
[docs/api/i18n-message.md](docs/api/i18n-message.md), and
[docs/api/menu.md](docs/api/menu.md) (see
[docs/api/README.md](docs/api/README.md)). Authority and users are now
implemented too; their current contract is
[docs/api/authority.md](docs/api/authority.md) and
[docs/api/user.md](docs/api/user.md) — the archived doc's sections 4–5 are
only the original record of that spec, no longer something to implement
against.

Implementation began 2026-09-15 with the multi-language (i18n) message
system, then was reworked 2026-09-16 to match `api-define-admin.md`,
common codes were added the same day, and menu management followed on
2026-09-18. See
[docs/i18n-system-design.md](docs/i18n-system-design.md),
[docs/common-code-system-design.md](docs/common-code-system-design.md), and
[docs/adr/](docs/adr/) — especially
[ADR-0011](docs/adr/0011-api-define-admin-contract-and-record-models.md)
(API contract + package conventions) and
[ADR-0012](docs/adr/0012-common-code-system.md) (ID generation, hierarchy
storage — its addendum documents the current DB-function-based ID scheme)
— before making changes to those areas.

Every new domain module (menu, permissions, users, session — all still
upcoming) must follow
[docs/conventions/common-base-model.md](docs/conventions/common-base-model.md):
per feature, split into `{package}.models` (domain models + search
params + commands/results), `{package}.mapper` (MyBatis interfaces),
`{package}.service` (business logic), `{package}.exceptions` (exception
classes — not `.models`), `{package}.events` (once a feature actually needs
events — none do yet), and `{package}.config` where a Spring config is
needed. **No records** — DB-row domain models extend
`kkdugi.core.models.BaseModel` (audit fields `createdAt`/`creatorId`/
`updatedAt`/`updaterId` come from the base class; own-fields constructor +
inherited setters for the audit fields), list search params extend
`kkdugi.core.models.BaseParams` (provides `page`/`pageSize` and
`resolvedPage()`/`resolvedPageSize()`/`getOffset()`/`getLimit()`), and
everything else (commands/results/errors/options) is a plain class with
`final` fields + an all-args constructor, no shared base (see
[ADR-0014](docs/adr/0014-revert-to-base-model-inheritance.md), which
reverts ADR-0011's record-based approach). Field names must never let a
JSON field reveal the underlying DB column name directly, and list
responses reuse `kkdugi.core.models.Page<T>`. As of 2026-09-18, `Page<T>`
requires `T extends BaseModel` and does query-level paging: the total
count is read off `contents.get(0).getTotalSize()` rather than a separate
`countX()` call, so list queries must select a `COUNT(*) OVER()` (or, for
a query already using `SELECT DISTINCT`, an outer query wrapping it) as
`total_size` and map it to `BaseModel.totalSize` in the resultMap — no
separate count mapper method. This means content classes that aren't
themselves a DB row (e.g. `AdminCode`, `AdminMessage`, which pivot
locale text onto a row) must still extend `BaseModel` to be usable as
`Page<T>`'s `T`, with `@JsonIgnoreProperties({"rownum", "createdAt",
"creatorId", "updatedAt", "updaterId"})` on the class to keep those
inherited fields out of the JSON response (`totalSize` is already
`@JsonIgnore`d on `BaseModel` itself). Before calling `Page.of(contents,
params)`, the service must normalize the params object
(`params.setPage(params.resolvedPage());
params.setPageSize(params.resolvedPageSize());`) — `Page`'s constructor
reads the raw `getPage()`/`getPageSize()`, not the resolved ones, so an
unnormalized `params` would report an unresolved (e.g. `0`) page/pageSize
in the response. Read `kkdugi.core.models.Page`/`BaseModel` and
`AdminCodeService`/`AdminMessageService` (the two current call sites)
before planning or implementing any new domain. New entities that need a server-generated ID
should implement `kkdugi.core.serial.SerialConfig` and call
`kkdugi.core.util.SerialUtils.next(config)` (see ADR-0012 addendum) rather
than inventing another ID scheme — the actual counter increment happens in
the Postgres function `fn_get_serial`, not in Java, because it must stay
correct under concurrent access from multiple app instances. Fixed,
code-backed value sets (status flags, types) implement
`kkdugi.core.enums.CodeEnums` and live in `kkdugi.core.enums` — MyBatis
converts them to/from their `getCode()` string automatically via
`mybatis.configuration.default-enum-type-handler` in `application.yml`, so
never add a per-column `typeHandler=`.

**Test policy (changed 2026-09-17)**: tests are written *and* run —
`mvn test` must actually pass before considering work done. (The earlier
"write but never execute" policy from the initial i18n build is gone; see
[docs/i18n-system-design.md](docs/i18n-system-design.md#테스트-실행-정책).)
Run tests with the project wrapper from `kkdugi-admin/`: `./mvnw.cmd -B -ntp test` (Java) and `node --test src/test/js/*.test.mjs` (front-end contract tests — pass a glob, not the directory). Requires `docker-compose up -d` first for Postgres.

## Tech stack

- Java 17, Spring Boot 4.0.8, Maven (single module — `core` / `api.admin` /
  `app.admin` are package boundaries, not separate Maven modules). Note:
  Spring Boot 4 defaults to **Jackson 3** (`tools.jackson.databind.ObjectMapper`),
  not the classic `com.fasterxml.jackson.databind.ObjectMapper` — a Jackson 2
  jar is still on the classpath (pulled in transitively by `flyway-core`),
  so the old import compiles but Spring won't autowire it as a bean. Always
  import `tools.jackson.databind.ObjectMapper` in this project.
- Lombok (`org.projectlombok:lombok`) is available — used by `core.enums`.
- Persistence: MyBatis (`mybatis-spring-boot-starter`)
- Schema migrations: Flyway (`spring-boot-starter-flyway` +
  `flyway-database-postgresql`), files under `src/main/resources/db/migration`
- DB: PostgreSQL 17, Redis 7 — both via `docker-compose.yml`
  (`docker-compose up -d`), Postgres bound to `127.0.0.1:5432` (db/user
  `kkdugi_dev`), Redis to `127.0.0.1:6379`. Redis is provisioned but not yet
  used by any code.
- Frontend: only the `spring-boot-starter-thymeleaf` dependency is present;
  no templates exist yet. Vue, CSS strategy, fonts, icons, and the grid
  library are recorded as TODO in
  [ADR-0009](docs/adr/0009-frontend-direction-thymeleaf-dependency-only.md).
  This is intentional — frontend is being built elsewhere; this repo's work
  is backend API only.

## Package structure

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
│       ├─ i18n     — models(AdminMessage, AdminMessageParams, AdminMessagePersistRequest,
│       │             MessageCode, MessageCodeRow), exceptions(AdminMessage*),
│       │             mapper(AdminMessageMapper), service(AdminMessageService)
│       └─ user     — models(UserBase, AdminUser, AdminUserParams, AdminUserPersistRequest, AdminUserIds,
│                     AdminUserChangeStatusRequest, UserAuthority, AdminUserAuthority,
│                     AdminUserAuthoritiesRequest), exceptions(AdminUser*), mapper(AdminUserMapper),
│                     service(AdminUserService, TemporaryPasswordGenerator — SerialUtils.next(config), prefix U)
├─ api              — controllers stay flat (not split into subpackages)
│   ├─ CodeController (/api/v1.0/code), MenuController (/api/v1.0/menu)
│   └─ admin        — AdminCodeController, AdminMenuController, AdminMessageController,
│                     AdminUserController (/api/v1.0/admin/{code,menu,i18n,user}); validation/conflict exceptions
│                     map to `ExceptionMessage`, see `kkdugi.core.exceptions`
└─ web.admin        — Thymeleaf/Pragma entry points (IndexController, LoginController, PragmaController)
```

**Dependency rule**: `api → app → core`. `app.admin.<feature>` and `app.<feature>` never
import each other (each depends only on `core`), and `core` never imports `app`. What a user
sees and what admin needs differ, so the two sides deliberately keep separate
models/mappers/services; each `app.<feature>` is meant to be extractable into its own library
later. See [ADR-0016](docs/adr/0016-app-and-admin-feature-split.md).

`core.util.SerialUtils` is a static service-locator wrapping `SerialService`
(registered via `InitializingBean.afterPropertiesSet()`) — it lets any code
call `SerialUtils.next(config)` without constructor injection.

Root package `kkdugi`, groupId `kkdugi`, artifactId `kkdugi-admin`.

Resource layout: mapper XML files live under
`src/main/resources/mapper/postgres/`, mirroring each mapper interface's
Java package below that root (e.g. `kkdugi.app.code.mapper.CodeMapper`
→ `mapper/postgres/app/code/CodeMapper.xml`) — one file per mapper
interface. (This replaced the earlier flat layout on 2026-09-18; MyBatis's
`mapper-locations` glob was already recursive
(`classpath:mapper/postgres/**/*Mapper.xml`), so only the files moved, no
config change.) Properties fallback messages live under
`src/main/resources/messages/`. The `fn_get_serial` Postgres function is
created by a Flyway migration (`V4__create_fn_get_serial.sql`), not a
mapper XML file — mapper XML only calls it.

## Build and run

- Build: `mvn compile`
- Run tests: `mvn test`
- Run the app: `mvn spring-boot:run` (requires `docker-compose up -d` first
  for the datasource; Flyway migrates the schema automatically on startup)

## Scope notes

- Organization/org-chart features exist in the ERD (`erd/`) but are
  explicitly out of scope for implementation.
- No global exception handling or common response envelope exists yet; each
  controller handles its own exceptions locally
  (`AdminMessageController`/`AdminCodeController`).
- `REG_ID`/`UPD_ID` (and `code_base`'s equivalents) are written as a fixed
  `"SYSTEM"` placeholder — there is no session/auth system yet to supply a
  real user id.
- Common codes: `code` (the path segment) and `parentId` are immutable
  after creation — changing either is rejected (409). Moving/renaming a
  node in the tree requires delete + recreate for now (see ADR-0012 "미해결
  이슈"). Delete cascades to all descendants.
- ID generation: counter increments happen inside the Postgres function
  `fn_get_serial`, not in application code — this is deliberate, so the
  counter stays correct under concurrent access across multiple app
  instances. Don't reintroduce a Java-side read-then-write counter.
- Menu management (`kkdugi.app.admin.menu`/`kkdugi.api.admin`; the user-facing tree is `kkdugi.app.menu`/`kkdugi.api.MenuController` at `/api/v1.0/menu`) was implemented 2026-09-18, mirroring the code
  domain's package/naming conventions and reusing `SerialConfig`/
  `SerialUtils` (`M` prefix). Two real differences from
  [docs/archive/api-define-admin.md](docs/archive/api-define-admin.md)
  section 3 (which didn't specify everything): `insert`/`update`/`delete`
  items carry `parentId` (the archived spec's examples omit it, but the
  hierarchy can't be built without it), and there's no 409-duplicate case
  (menu has no unique "value" column like code's `code_val`, so nothing to
  collide on) — see [docs/api/menu.md](docs/api/menu.md) for the actual
  contract.
- User management (`kkdugi.app.admin.user`/`kkdugi.api.admin.AdminUserController`
  at `/api/v1.0/admin/user`) was implemented 2026-09-19 (`U` prefix via
  `SerialConfig`/`SerialUtils`). Temporary passwords are generated on
  register/reset and, until mail sending exists, are logged in plaintext
  (`TODO(mail)`); protecting the last `SYS_ADMIN` user is deliberately not
  done (owner's decision). Details in
  [docs/user-system-design.md](docs/user-system-design.md) and
  [docs/api/user.md](docs/api/user.md).
- Session system (user + permissions + menu) is a separate next step beyond
  the API sections above.
