# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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

Every domain module (code, message, menu, authority, user — all implemented
— and any new one) must follow
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
│       ├─ authority — models(AdminAuthority, AdminAuthorityParams, AdminAuthorityPersistRequest,
│       │             AdminAuthorityMenu, AdminAuthorityUser, AuthorityBase, ...),
│       │             exceptions(AdminAuthority{Validation,Conflict,NotFound}Exception),
│       │             mapper(AdminAuthorityMapper), service(AdminAuthorityService — SerialUtils.next(config))
│       └─ user     — models(UserBase, AdminUser, AdminUserParams, AdminUserPersistRequest, AdminUserIds,
│                     AdminUserChangeStatusRequest, UserAuthority, AdminUserAuthority,
│                     AdminUserAuthoritiesRequest), exceptions(AdminUser*), mapper(AdminUserMapper),
│                     service(AdminUserService, TemporaryPasswordGenerator — SerialUtils.next(config), prefix U)
├─ api              — controllers stay flat (not split into subpackages)
│   ├─ CodeController (/api/v1.0/code), MenuController (/api/v1.0/menu)
│   └─ admin        — AdminCodeController, AdminMenuController, AdminMessageController,
│                     AdminAuthorityController, AdminUserController
│                     (/api/v1.0/admin/{code,menu,i18n,authority,user}); validation/conflict exceptions
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

- Organization/org-chart features are explicitly out of scope for
  implementation. (The `erd/` directory that originally modeled them was
  removed 2026-09-20; the Flyway migrations under
  `src/main/resources/db/migration` are now the schema source of truth.)
- No common response envelope exists. Exception handling is two-layered:
  `kkdugi.core.exceptions.advice.RestfulExceptionAdvice` (`@RestControllerAdvice`)
  handles `Restful*Exception(s)`, authentication/access-denied errors, and a
  500 fallback; the per-domain `Admin*ValidationException`/`*ConflictException`/
  `*NotFoundException` are still mapped to `ExceptionMessage` locally by
  `@ExceptionHandler` methods in each admin controller.
- `REG_ID`/`UPD_ID` (and `code_base`'s equivalents) are still written as a
  fixed `"SYSTEM"` (a private `SYSTEM_USER_ID` constant in each
  `app.admin.*` service) even though the session/auth system now exists
  (`SessionUtils.getUser()`); wiring the real user id in is not done yet.
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
- The session system (login → JWT + `SessionUser`/`SessionMenu`, menu-based
  authorization, the `/pragma/{menuId}` screen fragments) is implemented in
  `kkdugi.core.security` / `kkdugi.web.admin`; see
  [docs/api/auth.md](docs/api/auth.md),
  [docs/api/session.md](docs/api/session.md),
  [docs/api/request-context.md](docs/api/request-context.md), and
  [docs/api/authority.md](docs/api/authority.md) for authority management.

<!-- graft:start -->
## Graft — repo context graph

This repo is indexed in `graft/`: small linked markdown nodes that explain each
system and carry exact file:line spans, kept in sync with the code through git.

For ANY task here — understanding how something works, finding where code lives,
or scoping a change — get context from the graph before grepping or opening
source files. Re-ask freely (it's cheap) and reuse literal identifiers you
already have (symbol, error string, file name) as the query. New to this repo?
Run `graft map` first — a token-budgeted orientation (dir clusters, hubs,
hotspots), no LLM, no key.

- Run `graft ask "<your question>" --source` → ranked nodes with the relevant
  code spans inlined (each hit's ≤8-line crux by default; `--full` for whole
  definitions when the crux isn't enough). Match the tool to the task shape:
  for understanding or editing, the top node IS the answer — cite its
  `covers:` file:line spans and edit straight from `--source`. For
  exhaustive tasks ("every occurrence / every caller of this pattern"), ranked
  results are top-N, not complete — run `graft grep "<literal>"` instead
  (exhaustive over indexed files, grouped by enclosing symbol), falling back
  to raw `grep -rn` only for unindexed files.
- `graft skeleton <file>` → every definition's signature + span, ~10× cheaper
  than reading the file; use it to skim an API surface.
- `graft callers <symbol>` gives precomputed, exact edges — who calls this.
  Add `--direction out` for what it calls, or `--depth N` to walk
  transitively for the full blast radius. For structural questions, skip
  ranking and use this directly.
- Or browse: `graft/INDEX.md` lists every node; follow the links.
- Monorepos and folders of multiple repos rank fairly across sub-projects —
  hits carry `[scope/]` labels naming which one they're from. Narrow with
  `graft ask "<task>" --in <scope>/` once you know where you're working.

If a returned span is truncated ("+N more lines"), open the file at that exact
range before finalizing. Only open source files when a node genuinely lacks a
needed detail, and then at the exact file:line the node points to — never
re-read whole files.

After big code changes, refresh the graph with `graft build` (deterministic,
no API key, $0).
<!-- graft:end -->

<!-- ollama:start -->
# Local Ollama delegation (main agent + local models)

Any agent working in this repo (Codex, Claude Code, others) may delegate small,
well-scoped work to local Ollama models through `scripts/ollama_implements.py`.
The script needs Python 3 and a running Ollama server (`localhost:11434`).

- **Main agent (you)**: analyze the request, decide which files change, and split
  the work into small units. Parts that need many convention judgments
  (e.g. service logic) should be written by you directly.
- **Delegate by role** with `--role`. The role -> model mapping is fixed in the
  script's `ROLES`; to change a model, edit only the script.

  | Role | Model | Use for |
  |---|---|---|
  | `coder` | `qwen2.5-coder:7b` | Boilerplate: model classes, simple mappers |
  | `reviewer` | `qwen3.8:latest` | Reviewing generated or existing code (bugs, project-rule violations) |

- **How to call**:
  ```bash
  python scripts/ollama_implements.py --role coder "<requirements for one work unit>" -c <reference file> [-c <another file>]
  python scripts/ollama_implements.py --role reviewer "<review request>" -c <file to review>
  ```
  - Pass reference code with `-c <file path>` (repeatable, `-` reads stdin); do not paste code into the arguments.
  - The script injects the project rules (no records, extend `BaseModel`, package split, etc.) as the system prompt; do not repeat them in the request.
  - It prints the model's reply to stdout and does not write any files.
  - `reviewer` (27B) is slow on first load and per reply (a single small file took 4-6 minutes); use it only for reviews that matter.
- **Run sequentially, never in parallel**: the local machine has 32GB RAM and only 8GB VRAM, so the 27B model runs mostly from system RAM.
  - Run one call at a time and wait for it to finish before starting the next; do not launch `coder` and `reviewer` at the same time or fan calls out to parallel subagents.
  - The script enforces this with a machine-wide lock file (`kkdugi-ollama.lock` in the OS temp dir). If another call is running, it exits immediately with code 3 and names the holder; wait for that call to finish and run yours again. Never delete the lock file (the OS releases it automatically if the holder dies).
  - The script's timeout is 30 minutes and it asks Ollama to keep the model loaded for 30 minutes (`TIMEOUT_SEC`, `KEEP_ALIVE`). Do not retry a slow call: a retry queues a second request behind the first.
  - Agent shell tools often cap a command at about 10 minutes. Run `reviewer` in the background (or with the longest allowed timeout) and wait for it to complete.
- **Verify**: never trust local-model output as-is. Review it, apply it yourself,
  fix rule violations, then run `./mvnw.cmd -B -ntp test` (from `kkdugi-admin/`)
  and report failures as they are.
<!-- ollama:end -->