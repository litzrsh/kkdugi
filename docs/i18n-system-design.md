# kkdugi-admin 초기 프로젝트 + 다국어(i18n) 시스템 설계

> **패키지 구조 갱신 (2026-09-19):** 이 문서가 서술하는 `core.<기능>`/`app.admin.<기능>` 배치는 [ADR-0016](adr/0016-app-and-admin-feature-split.md)으로 사용자용(`app.<기능>`)과 관리자용(`app.admin.<기능>`)을 분리하는 구조로 바뀌었다. 아래 내용은 당시 결정의 기록이다.

- 작성일: 2026-09-15 (2026-09-16 api-define-admin.md 계약에 맞춰 전면 개정)
- 작성자: litzrsh (with Claude)
- 상태: 승인됨, 구현 완료
- 관련 ADR: [docs/adr](adr/) 0001~0011 (특히 [ADR-0011](adr/0011-api-define-admin-contract-and-record-models.md)이
  현재 계약의 근거)

## 배경

`CLAUDE.md`에 기록된 대로 이 저장소는 2026-09-15에 리셋되어 애플리케이션 코드가
전혀 없는 상태였다. 프로젝트 오너는 "관리자 시스템"과 "AI Agent 구동 부분"
두 축으로 구성된 프로그램을 만들 계획이며, 그 중 관리자 시스템의 핵심 공통
기능(다국어, 코드, 세션)부터 순서대로 구현하기로 했다. 이 문서는 그 첫 단계인
**프로젝트 초기화**와 **다국어(i18n) 시스템**의 설계를 다룬다. 조직(조직도) 관련
기능은 ERD에는 존재하지만 이번 구현 범위에서 명시적으로 제외한다.

2026-09-16, 오너가 관리자 시스템 5개 화면(공통코드/메시지/메뉴/권한/사용자)
전체의 REST API를 `docs/api-define-admin.md`(구 `api-define.md`)에 직접
정의하고 이를 확정 계약으로 지정했다. 다국어 시스템은 이 문서의 2절("메시지
관리") 계약에 맞춰 재구현되었다 — 엔드포인트, 요청/응답 모델(코드 단위 +
언어 pivot), 패키지 배치 규칙까지 모두 이 계약과 그 재구현 결정
([ADR-0011](adr/0011-api-define-admin-contract-and-record-models.md))을
반영한 최신 상태다.

UI(화면/템플릿)는 이번 범위에서 다루지 않는다. REST API와 서비스 로직까지만
구현한다.

## 범위

**포함**
- Maven 기반 Spring Boot 프로젝트 뼈대 (`kkdugi` 패키지, `core` / `api.admin` /
  `app.admin` 구조)
- `KKDUGI_I18N_MSG` 테이블에 대한 Flyway 마이그레이션
- DB(JDBC/MyBatis) 우선 + properties fallback을 지원하는 `MessageSource` 구현
- 메시지 코드 검증 (`{영역}.{유형}.{코드}` 패턴)
- `docs/api-define-admin.md` 2절 계약에 따른 메시지 관리 API(코드 단위 조회/저장)

**제외 (이번 범위 아님, 후속 작업)**
- 공통코드/메뉴/권한/사용자 화면 (`api-define-admin.md`의 1·3·4·5절 — 다음 단계)
- 세션 시스템
- 조직(조직도) 기능
- 전역 예외 처리 / 공통 응답 포맷 체계
- 화면/템플릿, 프론트엔드
- Redis 기반 캐시 공유 (다중 인스턴스 환경은 이후 재검토)

## 기술 스택

### Backend

- Java 17, Spring Boot 4.0.4, Maven(단일 모듈, 멀티모듈 아님)
- 영속성: MyBatis (`mybatis-spring-boot-starter`)
- 스키마 관리: Flyway (`spring-boot-starter-flyway`, `flyway-database-postgresql`)
- DB: PostgreSQL 17 (docker-compose 제공, `kkdugi_dev`)
- groupId `kkdugi`, artifactId `kkdugi-admin`, 루트 패키지 `kkdugi`

근거: [ADR-0008](adr/0008-backend-stack-revision-java17-springboot4.md)
(구 [ADR-0001](adr/0001-tech-stack-java-spring-maven.md) 대체),
[ADR-0005](adr/0005-persistence-mybatis.md),
[ADR-0006](adr/0006-schema-migration-flyway.md)

### Frontend (참고용 — 이번 범위는 의존성 추가까지만)

화면(UI)은 이번 범위가 아니다. 향후 방향은 ADR로 기록해두었고,
`spring-boot-starter-thymeleaf` 의존성 추가만 이번에 함께 적용한다.

| 항목 | 결정 | 이번 범위 반영 |
|---|---|---|
| 서버 사이드 렌더링 | Thymeleaf | 의존성만 추가, 템플릿 없음 |
| SPA 연동 | Vue | TODO |
| CSS | Custom CSS 전제 | TODO |
| 폰트 | Pretendard | TODO (추후 제공) |
| 아이콘팩 | line-awesome | TODO (추후 제공) |
| 그리드 | ag-grid | TODO (추후 제공) |

근거: [ADR-0009](adr/0009-frontend-direction-thymeleaf-dependency-only.md)

## 패키지 구조

기능마다 `models`/`mapper`/`service`(/`config`/`exceptions`/`events`)로
나누는 공통 규약을 따른다 — 자세한 규칙은
[공통 규약: 도메인 모델/페이징/패키지 배치](conventions/common-base-model.md)
문서를, 결정 배경은 [ADR-0011](adr/0011-api-define-admin-contract-and-record-models.md)을
참고한다.

```
kkdugi
├─ core
│   ├─ models
│   │   └─ Page.java          # 목록 응답 공통 래퍼 (record, page/pageSize/totalItems/totalPages/contents)
│   └─ i18n
│       ├─ models
│       │   ├─ I18nMessage.java   # DB 행 그대로의 도메인 모델 (record)
│       │   └─ MessageCode.java   # 코드 패턴 검증
│       ├─ mapper
│       │   └─ I18nMessageMapper.java   # MyBatis 매퍼 인터페이스
│       ├─ service
│       │   └─ KkdugiMessageSource.java # MessageSource 구현체 (DB 캐시 + properties fallback)
│       └─ config
│           └─ I18nMessageSourceConfig.java  # KkdugiMessageSource + ReloadableResourceBundleMessageSource 빈 설정
├─ app
│   └─ admin
│       └─ i18n
│           ├─ models
│           │   ├─ MessageSearchParams.java     # 조회 파라미터 (code, message, page, pageSize)
│           │   ├─ MessageContent.java          # 코드 단위 pivot 모델 (code, locale: Map<lang, text>)
│           │   ├─ MessagePersistRequest.java   # 저장 요청 (insert/update/delete: List<MessageContent>)
│           │   └─ MessageError.java            # 오류 항목 (code, reason)
│           ├─ exceptions
│           │   ├─ MessageValidationException.java
│           │   └─ MessageConflictException.java
│           └─ service
│               └─ MessageAdminService.java     # search(...)/persist(...)
└─ api
    └─ admin
        └─ i18n
            ├─ MessageAdminController.java   # POST 조회 + POST /persist 저장
            └─ MessageErrorResponse.java      # { errors: [MessageError] }
```

컨트롤러는 `app.admin.i18n.models`의 타입(`MessageSearchParams`,
`MessageContent`, `MessagePersistRequest`)을 요청/응답 바디로 직접
재사용한다 — 요청/응답 형태가 완전히 같고 Jackson 전용 애너테이션이
필요 없어서, api 계층 전용 DTO를 별도로 두지 않기로 했다
([ADR-0011](adr/0011-api-define-admin-contract-and-record-models.md) 4절).

Maven 의존성:
- `spring-boot-starter-web` — REST 컨트롤러
- `spring-boot-starter-thymeleaf` — 의존성만 추가, 템플릿/화면은 이번 범위
  아님 ([ADR-0009](adr/0009-frontend-direction-thymeleaf-dependency-only.md))
- `spring-boot-starter-jdbc`, `mybatis-spring-boot-starter`, `postgresql`
- `spring-boot-starter-flyway`, `flyway-database-postgresql`
- `spring-boot-starter-test`, `mybatis-spring-boot-starter-test`

리소스:
- `resources/mapper/postgres/I18nMessageMapper.xml`
- `resources/db/migration/V1__create_i18n_msg.sql`
- `resources/application.yml` — `mybatis.mapper-locations: classpath:mapper/postgres/*Mapper.xml`
- `resources/messages/messages.properties` (+ `messages_ko_KR.properties`,
  `messages_en_US.properties`, UTF-8, basename `classpath:messages/messages`)

## 데이터 모델

ERD(`erd/kkdugi`)에 설계된 `KKDUGI_I18N_MSG`를 그대로 따른다. DB 컬럼명은
변경되지 않았다.

| 컬럼 | 설명 | 비고 |
|---|---|---|
| MSG_CD | 메시지 코드 | PK(1/2), `{영역}.{유형}.{코드}` 패턴 |
| LANG_CD | 언어 코드 | PK(2/2), `ko_KR`/`en_US` 형식 |
| MSG_VAL | 메시지 값 | |
| REG_DTM / REG_ID | 등록일시/등록자 | |
| UPD_DTM / UPD_ID | 수정일시/수정자 (nullable) | |

**Java 매핑**: `I18nMessage`는 record이며 `msgCode`/`langCode`/`msgText`/
`createdAt`/`createdId`/`updatedAt`/`updatedId` 필드를 직접 선언한다(공통
베이스 클래스 상속 없음 — record는 클래스를 상속할 수 없다,
[공통 규약](conventions/common-base-model.md#3-도메인-모델과-앱-계층-데이터-객체는-record로-작성한다)
참고). `I18nMessageMapper.xml`의 `resultMap`이 `MSG_CD`/`LANG_CD`/`MSG_VAL`/
`REG_DTM`/`REG_ID`/`UPD_DTM`/`UPD_ID` 컬럼을 이 필드들로 직접 매핑한다
(공용 `CommonMapper.xml`은 두지 않는다 — 도메인이 하나뿐인 지금은 과설계라고
판단했다).

**주의**: 이 표의 컬럼명은 DB 스키마 전용이다. `I18nMessage`의 필드명은
컬럼명을 그대로 camelCase로 옮긴 것이 아니다(`msgCode`≠`msgCd`,
`msgText`≠`msgVal`) — [공통 규약의 필드 네이밍 원칙](conventions/common-base-model.md#2-필드-네이밍-원칙--db-컬럼명을-모델dto-필드명으로-쓰지-않는다)
참고.

## 메시지 코드 규칙

- 패턴: `{영역}.{유형}.{코드}` — 정확히 점(`.`)으로 구분된 3개 구간
- 각 구간은 소문자, 숫자, `_` 만 허용
- 정규식(`MessageCode.java` 실제 구현): `^[a-z0-9]+(_[a-z0-9]+)*(\.[a-z0-9]+(_[a-z0-9]+)*){2}\z`
  — Java의 `$`는 문자열 끝의 줄바꿈 앞에서도 매치되는 문제가 있어 `\z`(절대
  끝)를 사용한다.
- 예: `system.err.default` (허용), `System.Err.Default` / `system.err` / `system.err.default!` (거부)
- `MessageCode.validate(String)`/`MessageCode.matches(String)`이 저장
  시점(`MessageAdminService`)에서 모든 `insert`/`update`/`delete` 항목의
  `code`에 대해 강제한다.

## 메시지 조회 흐름 (Spring `MessageSource`)

이 절은 관리 API(아래)와 별개로, 애플리케이션 내부에서 `MessageSource`를
통해 메시지를 조회하는 런타임 경로를 설명한다.

두 개의 `MessageSource` 어댑터를 Spring의 parent 체이닝으로 연결한다
(자세한 근거는 [ADR-0004](adr/0004-i18n-messagesource-spring-integration.md)의
"보강" 참고).

- **루트(자식) 빈 - `KkdugiMessageSource`** (`kkdugi.core.i18n.service`):
  `AbstractMessageSource`를 상속. `resolveCode(code, locale)`은 DB 캐시만
  조회한다. Spring 컨테이너에 노출되는 `MessageSource` 빈은 이것 하나뿐이며,
  반드시 빈 이름 `"messageSource"`로 등록해야 한다
  (`@Bean(name = "messageSource")`) — Spring의
  `AbstractApplicationContext.initMessageSource()`가 타입이 아니라 이 빈
  이름으로 컨텍스트의 `MessageSource`를 찾기 때문이다.
- **parent(fallback) - `ReloadableResourceBundleMessageSource`**: classpath의
  `messages*.properties`(basename `classpath:messages/messages`,
  `defaultEncoding=UTF-8`)를 조회한다.
  `KkdugiMessageSource.setParentMessageSource(...)`로 연결한다.

빈 설정은 `kkdugi.core.i18n.config.I18nMessageSourceConfig`에 있다. DB 캐시
적재(`loadAll()`)는 `@Bean` 팩토리 메서드 안이 아니라 별도의
`CommandLineRunner` 빈에서 수행한다 — MyBatis는 JPA와 달리 Flyway 마이그레이션
이후 순서를 자동으로 보장하지 않으므로, `ApplicationContext` 초기화가 끝난
뒤(`CommandLineRunner` 실행 시점)에 조회해야 테이블이 아직 없는 상태에서
조회하는 위험을 피할 수 있다.

흐름:

1. 앱 기동 시 `I18nMessageMapper.selectAll()`로 DB의 전체 메시지를
   `ConcurrentHashMap<String, String>`(키: 메시지 코드와 언어 코드를 공백으로
   이어붙인 문자열)에 적재한다.
2. 조회(`getMessage`) 시:
   - DB 캐시에 키가 있으면 그 값을 사용하고, 인자가 있으면
     `java.text.MessageFormat`으로 `{0}` 등 자리표시자를 치환한다
     (`resolveCode`). 인자가 없는 호출은 `resolveCodeWithoutArguments`를
     별도로 오버라이드해 캐시 값을 그대로 반환한다.
   - 없으면(`resolveCode`가 null) Spring이 자동으로 parent인
     `ReloadableResourceBundleMessageSource`의 조회로 넘어간다.
   - 거기서도 못 찾으면 루트의 `setUseCodeAsDefaultMessage(true)` 설정에
     의해 코드 문자열 자체가 반환된다(예외 없이 항상 문자열 반환).
     `useCodeAsDefaultMessage`는 루트에만 설정한다.
3. 로케일은 Java `Locale`을 기준으로 하며, DB `LANG_CD` 값(`ko_KR`, `en_US`)과
   `Locale.toString()` 표현이 1:1로 대응하도록 저장/조회 시 변환한다.

근거: [ADR-0002](adr/0002-i18n-message-priority-db-first.md),
[ADR-0004](adr/0004-i18n-messagesource-spring-integration.md)

## 관리 API 모델

`docs/api-define-admin.md` 2절 계약을 그대로 구현한다
([ADR-0011](adr/0011-api-define-admin-contract-and-record-models.md)).

### 엔드포인트

- `POST /api/v1.0/admin/i18n` — 조회 (코드/메시지 조건, 코드 단위 페이징)
- `POST /api/v1.0/admin/i18n/persist` — INSERT/UPDATE/DELETE 저장

### Row 모델: 코드 단위 + 언어 pivot

한 항목(`MessageContent`)은 메시지 코드 하나와, 그 코드에 등록된 언어별
값을 담은 `locale` 맵으로 구성된다. `(msgCd, langCd)` 플랫 행 모델
([ADR-0007](adr/0007-admin-crud-single-endpoint-batch-save.md), 지금은
대체됨)과 달리, 같은 코드의 모든 언어가 한 항목에 모여 있어 코드 단위
페이징이 페이지 경계에서 흩어지지 않는다.

### 조회 요청/응답

```json
POST /api/v1.0/admin/i18n
{
  "code": "system.err",
  "message": "오류",
  "page": 1,
  "pageSize": 200
}
```

```json
{
  "page": 1,
  "pageSize": 200,
  "totalItems": 1,
  "totalPages": 1,
  "contents": [
    {
      "code": "system.err.default",
      "locale": { "ko_KR": "시스템 오류가 발생하였습니다", "en_US": "A system error has occurred" }
    }
  ]
}
```

- `code`/`message`는 선택 조건(부분 일치). `code`는 메시지 코드에,
  `message`는 등록된 언어 중 아무 언어의 값에라도 일치하면 그 코드가
  결과에 포함된다.
- 페이징은 **서로 다른 코드 수** 기준이다. `totalItems`는 조건에 맞는
  distinct 코드 수. `page`는 1-base, `pageSize` 기본/최대 200.
- 응답 형태는 `kkdugi.core.models.Page<MessageContent>`를 그대로
  직렬화한 것이다.

### 저장 요청

```json
POST /api/v1.0/admin/i18n/persist
{
  "insert": [
    { "code": "system.msg.test1", "locale": { "ko_KR": "값1" } }
  ],
  "update": [
    { "code": "system.msg.test2", "locale": { "ko_KR": "수정 값", "en_US": "new language" } }
  ],
  "delete": [
    { "code": "system.msg.test3", "locale": {} }
  ]
}
```

`insert`/`update`/`delete`는 각각 생략(= 빈 배열 취급) 가능하다. 성공 시
`persist`는 본문 없이 200을 반환한다 — `api-define-admin.md`에 이 엔드포인트의
응답 본문 예시가 없어 이렇게 결정했다([ADR-0011](adr/0011-api-define-admin-contract-and-record-models.md)
"미해결 이슈" 참고, 추후 오너 확인 필요).

### 처리 순서 (`MessageAdminService.persist`)

1. **필드 검증 (DB 접근 전)**: `insert`/`update`/`delete` 전 항목의 `code`에
   대해 `MessageCode.matches`를 검사한다. `insert`/`update` 항목은 `locale`
   맵이 비어있지 않아야 하고, 그 안의 모든 값이 공백이 아니어야 한다.
   하나라도 실패하면 DB에 전혀 손대지 않고 400(`MessageValidationException`)으로
   전체 요청을 거부한다.
2. **insert**: `locale`의 각 언어에 대해 새 행을 INSERT한다. 이미 존재하는
   `(code, lang)`이면 409(`MessageConflictException`)로 전체 롤백한다.
3. **update**: 대상 `code`가 DB에 전혀 없으면 409(새 코드는 `insert`로
   만들어야 한다). 코드는 있지만 `locale`의 특정 언어가 아직 없으면 그
   언어는 INSERT로(기존 코드에 새 언어 추가), 이미 있으면 UPDATE로
   처리한다.
4. **delete**: 요청 항목의 `locale` 값과 무관하게, 그 코드에 등록된
   **모든 언어 행을 함께 삭제**한다(`docs/design/adr/0003-message-pivot-i18n.md`
   결정 #6). 대상 코드가 없으면 409.
5. **전체 트랜잭션**: 하나의 `@Transactional` 메서드 안에서 위 세 버킷을
   순서대로 처리하며, 어느 하나라도 실패하면 전체를 롤백한다
   ([ADR-0007](adr/0007-admin-crud-single-endpoint-batch-save.md)의 전체
   트랜잭션 원칙은 계약 변경 후에도 유지).
6. **커밋 후 캐시 갱신**: 캐시 갱신은 트랜잭션 커밋 후
   (`TransactionSynchronization.afterCommit`)에만, 이번 요청으로 영향받은
   `(code, lang)` 키만 갱신한다(삭제된 경우 캐시에서 제거). 전체 리로드는
   하지 않는다. (근거: [ADR-0003](adr/0003-i18n-cache-strategy-in-memory-evict-on-save.md))

### 범위에서 제외한 것: 낙관적 잠금

여러 관리자가 동시에 같은 메시지를 편집하는 경우의 충돌 방지(변경 시점 버전
체크)는 이번 범위에서 다루지 않는다. 나중 저장이 이전 저장을 덮어쓴다
(last-write-wins). 실제로 동시 편집 충돌이 문제가 되면 `updatedAt` 비교
기반의 낙관적 잠금을 후속 작업으로 추가한다.

## 에러 처리

- 필드 검증 실패(코드 패턴 위반, 필수값 누락)는 `MessageValidationException`
  으로 표현하고 400으로 매핑한다.
- DB 처리 중 대상 없음(update/delete) 또는 중복(insert)은
  `MessageConflictException`으로 표현하고 409로 매핑한다.
- 두 예외 모두 `{ errors: [{ code, reason }] }`(`MessageErrorResponse`)
  형태로 응답한다.
- 전역 예외 처리 체계를 새로 만들지 않고, `MessageAdminController`에 로컬
  `@ExceptionHandler`를 두어 위 두 예외만 처리한다.

## 테스트 전략

- `MessageCodeTest` (`core.i18n.models`): 패턴 검증 단위 테스트
- `KkdugiMessageSourceTest` (`core.i18n.service`): docker-compose Postgres
  (Flyway 자동 적용) 기준으로 DB 메시지 우선순위, properties fallback,
  코드 자체 반환, 파라미터 치환을 검증
- `MessageAdminServiceTest` (`app.admin.i18n.service`):
  - insert 후 update로 값 갱신 + 새 언어 추가가 캐시에 반영되는지 검증
  - delete가 코드의 모든 언어를 함께 지우는지 검증
  - 잘못된 코드 형식은 DB에 손대지 않고 거부되는지 검증
  - 중복 insert, 존재하지 않는 코드에 대한 update가 409로 처리되는지 검증
- `MessageAdminControllerTest` (`api.admin.i18n`): `MockMvc` 기반으로 저장
  성공(200)/검증 실패(400)/대상 없음(409)/조회 응답 형태를 HTTP 계층에서
  검증

테스트는 Testcontainers를 도입하지 않고 기존 `docker-compose.yml`이 제공하는
로컬 Postgres(127.0.0.1:5432)를 사용한다. 실행 전 `docker-compose up -d`가
필요하다.

### 테스트 실행 정책

**2026-09-17부터 변경**: 작성된 테스트가 실제로 통과하는지 `mvn test`로
확인하고, 실패하면 고친다. 이전에는 테스트 코드는 작성하되 실행은 하지
않는 정책이었으나(도구/모델이 `mvn`을 실행할 수 없던 초기 제약), 실제로
`mvn test`를 돌려보니 통과하는 파일이 하나도 없어 오너가 정책을
전환했다. 로컬 Postgres(`docker-compose up -d`)가 떠 있어야 하며, DB
스키마가 Flyway 히스토리와 어긋나 있으면(예: `flyway_schema_history` 없이
테이블만 있는 경우) 먼저 스키마를 초기화해야 한다.

## 후속 작업 (이번 범위 아님)

- (완료: 공통코드는 이후 [ADR-0012](adr/0012-common-code-system.md)로 구현됨)
  나머지 화면(메뉴/권한/사용자)은 옛 `docs/api-define-admin.md`(2026-09-18
  삭제, 원문은 [docs/archive/api-define-admin.md](archive/api-define-admin.md)
  3·4·5절 참고 — 더 이상 살아있는 계약 아님, 착수 전 오너 재확인 필요) — 이번에
  정리한 패키지 배치 규약과 `Page<T>` 재사용 방식을 그대로 적용한다.
- `POST /api/v1.0/admin/i18n/persist`의 응답 본문 형태 확정(현재는 빈 200 —
  [ADR-0011](adr/0011-api-define-admin-contract-and-record-models.md) "미해결
  이슈" 참고)
- 세션 시스템 (사용자 정보 + 권한 + 메뉴)
- 전역 예외 처리 / 공통 응답 포맷
- 프론트엔드: Vue 연동, Custom CSS 전략, Pretendard 폰트, line-awesome
  아이콘팩, ag-grid 적용 ([ADR-0009](adr/0009-frontend-direction-thymeleaf-dependency-only.md))
