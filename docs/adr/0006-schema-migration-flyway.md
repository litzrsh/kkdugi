# ADR-0006: 스키마 관리 - Flyway 마이그레이션

- 상태: Accepted
- 날짜: 2026-09-15

## 컨텍스트

`KKDUGI_I18N_MSG` 테이블을 Postgres(`kkdugi_dev`, docker-compose 제공)에
생성하는 방식을 정해야 한다. 이후 공통코드/세션/메뉴 등 테이블도 순차적으로
추가될 예정이라, 단발성 방식이 아니라 계속 누적 가능한 방식이 필요하다.
검토한 대안:

1. Flyway 마이그레이션(`src/main/resources/db/migration`에 버전 관리되는
   SQL 스크립트)
2. Spring Boot 기본 `schema.sql` 초기화 기능

## 결정

**Flyway를 사용한다.** `V1__create_i18n_msg.sql`부터 시작하며, 이후 테이블도
같은 방식으로 버전을 누적한다.

## 근거

- `schema.sql`은 별도 의존성이 없어 간단하지만 버전 이력이 없고, 이미 적용된
  스키마에 대한 증분 변경(컬럼 추가 등)을 표현할 방법이 없다.
- 이 프로젝트는 앞으로 코드/세션/메뉴 테이블이 순차적으로 추가될 것이 예정돼
  있어, 처음부터 버전 관리되는 마이그레이션 방식이 필요하다.
- Flyway는 앱 기동 시 자동으로 미적용 마이그레이션을 실행하므로 별도
  수동 절차가 없다.

## 결과

- `pom.xml`에 `flyway-core`, `flyway-database-postgresql`을 추가한다.
- 마이그레이션 파일은 `src/main/resources/db/migration/V{n}__{설명}.sql`
  네이밍을 따르며, 한 번 커밋된 마이그레이션 파일은 이후 수정하지 않고
  새 버전 파일을 추가하는 방식으로 스키마를 변경한다.
