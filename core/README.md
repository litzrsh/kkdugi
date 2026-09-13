# kkdugi core

Shared Java library for kkdugi applications, providing datasource routing,
MyBatis integration, common codes, localization, and utility classes. This
module has no application entry point and is intended to be used by a host
Spring Boot application.

## Requirements

- JDK 17 or later (`JAVA_HOME` configured).
- Maven Wrapper included in this directory; the first run downloads Maven and
  dependencies.
- A configured database and its JDBC driver for use in a host application.
  H2 is included only for tests.

The module uses Spring Boot 4.0.8 and MyBatis Spring Boot Starter 4.0.1, with
Spring MVC, Security, JDBC, AspectJ, Thymeleaf, and Lombok dependencies.

## Build and test

Run commands from `core`.

```powershell
# Windows PowerShell: package the library without running tests
.\mvnw.cmd -DskipTests package

# Run tests
.\mvnw.cmd test

# Install the library into the local Maven repository without running tests
.\mvnw.cmd -DskipTests install
```

On macOS/Linux, use `./mvnw` instead of `.\mvnw.cmd`.
The packaged library is `target/core-0.0.1-SNAPSHOT.jar`.

`CoreApplicationTests` uses `@SpringBootTest`, but this module does not provide
a `@SpringBootConfiguration` application class. That context test needs an
application configuration before the full test suite can pass.

## Use in an application

After installing the library locally, add this dependency to the host project:

```xml
<dependency>
    <groupId>kkdugi</groupId>
    <artifactId>core</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

The host application must register the required core configuration classes,
components, and mapper interfaces, supply a JDBC driver, and prepare the
database schema. Configuration classes currently use `@Configuration`; the
module's `META-INF/spring.factories` registers only environment post-processors.

Example datasource and mapper settings for a PostgreSQL host application:

```yaml
kkdugi:
  datasource:
    primary-key: main
    main:
      driver-class-name: org.postgresql.Driver
      url: ${DB_URL}
      username: ${DB_USERNAME}
      password: ${DB_PASSWORD}

mybatis:
  mapper-locations: classpath*:mappers/postgres/**/*.xml
```

Set `primary-key` to a configured datasource name. Add additional named entries
under `kkdugi.datasource` to define other databases. Use `@Database("name")` on
Spring-managed service classes or methods to select a datasource through the
routing aspect.

Current integration gaps: the routing aspect's pointcut references
`kkdugi.core.datasource.Database`, while the annotation is in
`kkdugi.core.datasource.annotations`. Also, both
`KkdugiMessageSourceAutoConfigurer` and `CoreI18nMessageAutoConfigurer` declare
`jdbcRoutableMessageSource`. Resolve these wiring issues before enabling these
features together in a host application.

## Features

- **Datasource routing:** named datasources with a primary datasource,
  annotation-based selection, and a thread-local routing context.
- **MyBatis session parameters:** `SessionProcessingInterceptor` exposes the
  current user as `session`, allowing mapper expressions such as `#{session.id}`.
- **Common codes:** mapper and service classes for retrieving and caching codes.
- **Localization:** message bundles under `messages/messages` with a JDBC-backed
  parent message source.
- **Encrypted properties:** values wrapped in `enc(...)` are decrypted using
  `KKDUGI_PROPERTY_ENCRYPTION_KEY`. If the key is absent or decryption fails,
  the value inside the wrapper is used unchanged.
- **Default properties:** sets `spring.jackson.default-property-inclusion` to
  `non_null` at the lowest precedence, allowing application overrides.
- **Shared models and utilities:** users, authorities, menus, trees, base
  models, dates, sessions, and messages.

## Source layout

```text
src/main/java/kkdugi/core/
  code/        Common-code configuration, models, mapper, and service
  config/      Datasource, message-source, cache, and default configuration
  crypto/      AES-GCM property decryption and environment processing
  datasource/  Routing datasource, context, annotation, and aspect
  i18n/        Database-backed localization
  models/      Shared domain models and parameters
  mybatis/     Session parameter interceptor
  props/       Datasource configuration properties
  util/        Common, date, message, session, and tree helpers
src/main/resources/
  META-INF/    Environment post-processor registration
  mappers/     PostgreSQL MyBatis mapper XML
  messages/    Message bundles
src/test/java/ Test sources
```
