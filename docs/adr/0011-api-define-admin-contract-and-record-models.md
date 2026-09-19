# ADR-0011: i18n API를 api-define-admin.md 계약으로 재구현, record 기반 모델과 계층별 패키지 규약 도입

> **패키지 구조 갱신 (2026-09-19):** 이 문서가 서술하는 `core.<기능>`/`app.admin.<기능>` 배치는 [ADR-0016](0016-app-and-admin-feature-split.md)으로 사용자용(`app.<기능>`)과 관리자용(`app.admin.<기능>`)을 분리하는 구조로 바뀌었다. 아래 내용은 당시 결정의 기록이다.

- 상태: Accepted, 단 "2. `BaseModel`/`BaseParams` 상속을 record 기반
  모델로 전환" 절은 [ADR-0014](0014-revert-to-base-model-inheritance.md)로
  Superseded — API 계약/패키지 배치/DTO 통합/persist 의미 결정 등
  나머지 절은 그대로 유효하다.
- 날짜: 2026-09-16
- 대체: [ADR-0007](0007-admin-crud-single-endpoint-batch-save.md)의 엔드포인트/Row 모델 결정 전체를 대체.
  [ADR-0010](0010-common-base-model-adoption.md)의 `BaseModel`/`BaseParams`/
  `CommonMapper.xml` 상속 메커니즘을 대체(단, 감사 필드 분리·페이징 응답
  래퍼라는 문제의식 자체는 유지).

## 컨텍스트

오너가 `docs/api-define-admin.md`(구 `api-define.md`)에 관리자 시스템
5개 화면(공통코드/메시지/메뉴/권한/사용자) 전체의 REST API를 직접
정의했다. 이 문서가 실제로 구현해야 할 API 계약이라고 확정했다
(2026-09-16). 메시지 관리(2절)의 계약은 기존 구현과 다음 지점에서
근본적으로 다르다:

- 엔드포인트: `/api/admin/i18n/messages` (GET/POST) 대신
  `POST /api/v1.0/admin/i18n`(조회) / `POST /api/v1.0/admin/i18n/persist`(저장).
- Row 모델: `(msgCd, langCd)` 플랫 행 + `crudType` 필드 대신, 코드 단위로
  묶고 언어별 값을 `locale` 맵으로 pivot한 `{ code, locale: { ko_KR: "...",
  ... } }` 모델. 저장 요청은 행별 `crudType` 대신 `insert`/`update`/`delete`
  세 배열로 분리.
- 목록 응답: `{ rows, totalCount, page, size }` 대신
  `{ page, pageSize, totalItems, totalPages, contents }`.

이 계약을 반영하는 과정에서, 오너가 별도로 지시한 패키지 규약(2026-09-16)도
함께 적용한다: "Database transaction object, search parameter의 경우
`{package}.models` 하위에 위치, mapper는 `{package}.mapper` 하위에 위치,
service는 `{package}.service` 하위에 위치."

이 재구현 직전, `core.i18n`을 `models`/`mapper`/`config` 하위 패키지로
나누고 `I18nMessage`를 `BaseModel`을 상속하는 클래스 대신 플랫 record로
바꾸는 작업이 이미 부분적으로 진행되어 있었으나, 호출부(`MessageAdminService`,
`MessageAdminController`)가 갱신되지 않아 컴파일이 깨진 채로 남아 있었다
(경위는 대화 기록 참고). 이번 ADR은 그 미완성 상태를 api-define-admin.md
계약에 맞춰 완전히 재구현하면서 기록한다.

## 결정

### 1. 패키지 규약: 계층마다 `models`/`mapper`/`service`(/`config`)로 분리

```
kkdugi.core.i18n
├─ models   — I18nMessage(record, DB 행 그대로), MessageCode(코드 검증)
├─ mapper   — I18nMessageMapper (MyBatis 매퍼 인터페이스)
├─ service  — KkdugiMessageSource (MessageSource 구현체)
└─ config   — I18nMessageSourceConfig (Spring 빈 설정)

kkdugi.app.admin.i18n
├─ models      — MessageSearchParams, MessageContent, MessagePersistRequest, MessageError
├─ exceptions  — MessageValidationException, MessageConflictException
└─ service     — MessageAdminService
```

(위 트리는 이 ADR의 최종 상태를 보여준다 — 예외를 `exceptions` 하위
패키지로 분리하는 규칙은 같은 날 뒤늦게 전달되어 아래 addendum에서
적용했다.)

`api.admin.i18n`(컨트롤러 계층)은 이 규약 대상이 아니다 — 오너의 지시가
"database transaction object/search parameter/mapper/service"만 언급했고
컨트롤러는 언급하지 않았으므로, 기존처럼 패키지 루트에 둔다.
`MessageAdminController`는 `app.admin.i18n.models`의 타입들을 요청/응답
바디로 직접 재사용한다(아래 3번 참고).

### 2. `BaseModel`/`BaseParams` 상속을 record 기반 모델로 전환

**Java 레코드는 클래스를 상속할 수 없다** — `record I18nMessage(...)`는
`extends BaseModel`을 쓸 수 없다. 이미 코드가 record로 옮겨간 상태였고,
불변 데이터 객체로서 record가 명확히 더 적합하므로, 이 제약을 계기로
[ADR-0010](0010-common-base-model-adoption.md)의 상속 기반 접근을
공식적으로 대체한다.

- `BaseModel`/`BaseParams`(추상 클래스)와 `CommonMapper.xml`의
  `baseResultMap`은 삭제한다. `kkdugi.core.models`에는 `Page<T>` record만
  남긴다.
- 감사 필드(등록/수정 일시·사용자)가 필요한 도메인 모델은 각자의 record에
  직접 필드로 선언한다(예: `I18nMessage`의 `createdAt`/`createdId`/
  `updatedAt`/`updatedId`). 상속 대신 필드 반복을 감수하는 것이 record
  제약 하에서의 현실적 선택이다.
- `kkdugi.core.models.Page<T>`는 유지한다: `record Page<T>(int page, int
  pageSize, long totalItems, long totalPages, List<T> contents)` +
  정적 팩토리 `Page.of(contents, page, pageSize, totalItems)`. 필드 이름이
  api-define-admin.md의 모든 목록 응답 형태(`page`/`pageSize`/`totalItems`/
  `totalPages`/`contents`)와 정확히 일치하므로, 응답 DTO를 매번 새로 정의하지
  않고 컨트롤러가 `Page<T>`를 그대로 반환한다.
- [ADR-0010](0010-common-base-model-adoption.md)은 위 메커니즘(BaseModel/
  BaseParams/CommonMapper.xml) 부분에 한해 **Superseded**로 표시한다.
  다만 그 ADR이 제기한 문제의식(감사 필드 반복 방지, 통일된 페이징
  응답 형태)은 여전히 유효하며, 이번 결정이 다른 방식(직접 필드 선언 +
  공용 `Page<T>`)으로 계승한다.

### 3. app/api 계층 DTO 중복 제거

[ADR-0007](0007-admin-crud-single-endpoint-batch-save.md)은 `app.admin.i18n`
커맨드/결과와 `api.admin.i18n`의 JSON DTO를 분리했다(Jackson 관심사 분리,
다른 진입점에서 서비스 재사용). 이번 계약에서는 요청/응답 형태가 아주 단순한
plain record(`MessageContent(code, locale)`, `MessagePersistRequest(insert,
update, delete)`, `MessageSearchParams(code, message, page, pageSize)`)이고
Jackson 특화 애너테이션이 전혀 필요 없어, 이 분리가 주는 이점보다 중복
비용이 커졌다고 판단했다. `app.admin.i18n.models`의 타입을
`MessageAdminController`가 `@RequestBody`/응답 타입으로 직접 재사용한다.
과거 `MessageRowRequest`/`MessageRowResponse`/`MessageSaveRequest`/
`MessageSaveResponse`/`MessageListResponse`/`MessageRowErrorResponse`/
`MessageSaveErrorResponse`(api 계층)와 `MessageRowCommand`/`MessageRowResult`/
`MessageSaveResult`/`MessageSearchResult`/`CrudType`(app 계층)는 모두
삭제했다.

### 4. 메시지 저장(persist) 의미 확정 (이전에 미확정이었던 부분)

api-define-admin.md와 `docs/design/adr/0003-message-pivot-i18n.md`도
완전히 명시하지 않은 세부 동작을 아래와 같이 확정한다:

- **insert**: `locale` 맵의 각 언어에 대해 새 행을 INSERT한다. 이미 존재하는
  `(code, lang)`이면 409(`MessageConflictException`)로 전체 트랜잭션을
  롤백한다.
- **update**: 대상 `code`가 DB에 전혀 없으면 409(새 코드는 `update`가
  아니라 `insert`로 만들어야 한다). 코드는 존재하되 `locale` 맵의 특정
  언어가 아직 없으면 그 언어는 INSERT로 처리한다(기존 코드에 새 언어
  번역을 추가하는 흐름을 `update`로 표현할 수 있게 하기 위함). 이미 있는
  언어는 UPDATE한다.
- **delete**: 요청 항목의 `locale` 값은 무시하고, 그 `code`에 등록된
  **모든 언어 행을 함께 삭제**한다(`docs/design/adr/0003-message-pivot-i18n.md`
  결정 #6을 따름 — 코드 행 삭제는 하위 모든 언어에 DELETE를 생성해야
  한다). 대상 코드가 없으면 409.
- 저장 전체는 하나의 `@Transactional` 메서드에서 처리하며, 위 세 버킷 중
  하나라도 실패하면 전체 롤백한다([ADR-0007](0007-admin-crud-single-endpoint-batch-save.md)의
  전체 트랜잭션 결정은 계약 변경과 무관하게 유지).
- 캐시 갱신은 여전히 커밋 후(`TransactionSynchronization.afterCommit`)에만,
  영향받은 `(code, lang)` 키만 갱신한다([ADR-0003](0003-i18n-cache-strategy-in-memory-evict-on-save.md)).
- `POST /api/v1.0/admin/i18n/persist`의 응답 본문은 api-define-admin.md에
  예시가 없다(요청 예시와 상태 코드 표만 존재) — 응답 본문 없이 200을
  반환하도록 구현했다. 검증 실패(400)/충돌(409) 시에는
  `{ errors: [{ code, reason }] }` 형태로 응답한다(스펙에 명시되지 않은
  부분이므로 오너 확인 시 바뀔 수 있다).
- 목록 조회(`POST /api/v1.0/admin/i18n`)는 `code`(부분 일치),
  `message`(임의 언어의 값에 대한 부분 일치) 조건으로 **서로 다른 코드
  수** 기준 페이징한다: distinct `msg_cd` 목록을 먼저 페이징 조회하고,
  그 페이지의 코드들에 대해서만 전체 언어 행을 조회해 `locale` 맵으로
  묶는다. `page`는 1-base(api-define-admin.md의 `"page": 1` 예시와 일치),
  `pageSize`는 기본/최대 200.

## 근거

- api-define-admin.md는 오너가 직접 확정한 계약이므로 그대로 따른다.
- Java record는 클래스를 상속할 수 없다는 언어 제약이 명확하므로,
  `BaseModel` 상속을 고집하는 대신 record + 공용 `Page<T>` 조합으로
  전환하는 것이 유일하게 일관된 선택이다.
- 코드 단위 삭제가 하위 모든 언어에 전파돼야 한다는 규칙은 이미
  `kkdugi-design`의 화면 기획 문서에서 검토·확정된 내용이라 그대로
  채택했다.

## 결과

- `docs/conventions/common-base-model.md`를 이 ADR의 내용에 맞춰
  전면 개정했다(`BaseModel`/`BaseParams` 상속 규칙 삭제, record +
  `Page<T>` 규칙과 `models`/`mapper`/`service`/`config` 패키지 배치
  규칙으로 대체).
- [ADR-0007](0007-admin-crud-single-endpoint-batch-save.md)은 이 ADR로
  전체(엔드포인트·Row 모델 모두) 대체되어 `Superseded by ADR-0011`로
  표시했다. 다만 "전체 트랜잭션(all-or-nothing)" 원칙은 이번 ADR의
  persist 구현에도 그대로 계승되어 있다.
- [ADR-0010](0010-common-base-model-adoption.md)은 `BaseModel`/
  `BaseParams`/`CommonMapper.xml` 메커니즘에 한해 `Superseded by
  ADR-0011`로 표시했다.
- `docs/i18n-system-design.md`를 이 계약을 반영해 다시 작성했다.

## 미해결 이슈 (다음 착수 대상)

- 공통코드/메뉴/권한/사용자 4개 화면은 아직 구현되지 않았다 — 이번
  패키지 규약과 `Page<T>` 재사용 방식을 그대로 적용할 것.
- ~~`POST /api/v1.0/admin/i18n/persist`의 정상/오류 응답 본문 형태는
  api-define-admin.md에 명시되지 않아 이 ADR에서 잠정 결정한 것이다 —
  문서가 구체화되면 재확인이 필요하다.~~ → 2026-09-18 addendum에서 오류
  응답 형태를 `{code, message}` 단일 메시지로 확정.
- `update` 버킷에서 신규 언어를 INSERT로 처리하는 규칙(위 4번)은
  api-define-admin.md/kkdugi-design 어느 쪽에도 명시적으로 쓰여 있지
  않은, 이번 ADR의 해석이다 — 오너 검토 후 확정한다.

## Addendum (2026-09-16): 예외/이벤트 패키지 세분화

오너가 패키지 규약에서 누락된 부분을 추가로 전달했다: **예외는
`{package}.exceptions`, 이벤트는 `{package}.events` 하위에 둔다.** 위
1번 결정 당시에는 예외를 `models`에 함께 두었는데, 이 지시에 따라 다음과
같이 정리한다.

- `kkdugi.app.admin.i18n.models.MessageValidationException`/
  `MessageConflictException`을 `kkdugi.app.admin.i18n.exceptions`로
  옮겼다. `MessageAdminService`/`MessageAdminController`/
  `MessageAdminServiceTest`의 import를 함께 갱신했다.
- `kkdugi.core.i18n`에는 현재 예외 타입이 없어 `core.i18n.exceptions`는
  만들지 않았다 — 필요해지면 그때 만든다.
- 이 프로젝트에는 아직 이벤트를 발행/구독하는 기능이 없어 `events` 하위
  패키지는 어디에도 만들지 않았다 — 6번의 YAGNI 원칙(공통 규약 문서)과
  동일하게, 실제로 이벤트가 필요해지는 시점에 만든다.
- [공통 규약: 도메인 모델/페이징/패키지 배치](../conventions/common-base-model.md)
  1번(패키지 배치)·3번(record 규칙)·체크리스트를 이 규칙에 맞춰 갱신했다.

## Addendum (2026-09-17): 테스트 실행 정책 전환 + 실제 실행으로 드러난 버그 수정

오너가 `mvn test`를 직접 돌려보니 36개 테스트 중 통과하는 파일이 하나도
없었다. 이를 계기로 테스트 정책이 바뀌었다: **테스트는 작성 후 반드시
`mvn test`로 실제 통과를 확인하고, 실패하면 고친다**(이전의 "작성만 하고
실행하지 않는다" 정책은 폐기 —
[docs/i18n-system-design.md의 "테스트 실행 정책"](../i18n-system-design.md#테스트-실행-정책)
참고). 실제로 돌려서 드러난 버그 세 가지를 이 자리에서 함께 기록한다.

1. **record를 매핑하는 resultMap이 setter 기반(`<id>`/`<result>`)이라
   전부 깨져 있었다.** `I18nMessage`/`CodeBase`/`CodeLang`는 record라
   setter가 없는데, `<id property="..." column="..."/>` 스타일은 MyBatis가
   내부적으로 setter를 호출해 값을 채우려 한다 —
   `ReflectionException: There is no setter for property...`로 모든 조회가
   실패했다. `#{property}` 파라미터 바인딩(INSERT/UPDATE 쪽)은 record
   접근자로도 동작해 문제가 없었다 — 조회(결과를 새 인스턴스로 만드는 쪽)만
   깨졌다. 세 매퍼 XML 모두 `<constructor>` 기반 매핑으로 바꿨다.
2. **`SerialMapper.xml`의 문서용 주석 안에 실제 `#{...}` 플레이스홀더가
   남아 있었다.** MyBatis는 SQL 주석(`/* ... */`)을 이해하지 못하고 텍스트
   안의 `#{...}`를 전부 바인드 파라미터로 세므로, 실제 3개(`id`/`key`/
   `size`) 대신 9개를 바인딩하려다 "parameter #4" 오류가 났다. 문서용
   주석을 지우고 실제 SQL만 남겼다 — 로직 설명은 `fn_get_serial` 함수
   자체(`V4__create_fn_get_serial.sql`)에 있으므로 중복 문서화하지 않는다.
3. **Spring Boot 4가 기본으로 Jackson 3(`tools.jackson.*`)를 쓰는데,
   컨트롤러 테스트가 Jackson 2(`com.fasterxml.jackson.databind.ObjectMapper`)를
   import하고 있었다.** 클래스 자체는 classpath에 있었지만(Flyway가
   전이 의존성으로 끌어온 Jackson 2) Spring이 실제로 등록하는 빈은 Jackson
   3 타입이라 `@Autowired`가 못 찾았다. import를
   `tools.jackson.databind.ObjectMapper`로 바꿨다.

이어서 오너가 `SerialMapper.xml`에 직접 확립한 매퍼 XML 서식(CDATA로 SQL
감싸기, `QueryID`/`Description` 주석)을 나머지 세 매퍼 XML에도 동일하게
적용했다 — 규칙은
[공통 규약의 7번](../conventions/common-base-model.md#7-mybatis-매퍼-xml-서식)에
정리했다. 이후 메뉴/권한/사용자 매퍼도 이 서식을 따른다.

## Addendum (2026-09-18): 에러 응답을 단일 메시지로 단순화 (위 137번 미해결 이슈 확정)

[ADR-0012](0012-common-code-system.md)의 같은 날짜 addendum에서 공통코드
쪽 에러 응답을 `{ errors: [{id, code, reason}] }`에서 `{code, message}`
단일 메시지로 바꾸면서, 오너가 같은 방침을 메시지 관리에도 적용하라고
지시했다: **"오류가 발생한 경우 어떤 오류가 발생했는지만 명확하게 짚어내면
됨 — 어디서/무엇 때문에 발생했는지는 서버 사이드 로그에만 남긴다."** 이로써
위 "미해결 이슈" 2번(`persist` 오류 응답 형태가 잠정 결정이었던 부분)이
다음과 같이 확정된다.

- `MessageValidationException`/`MessageConflictException`은 `List<MessageError>`
  대신 메시지 코드 문자열 하나(`String code`)만 들고 있다. `MessageError`/
  `MessageErrorResponse` 클래스는 삭제했다.
- `MessageAdminController`의 두 `@ExceptionHandler`는
  `kkdugi.core.exceptions.ExceptionMessage`(`{code, message}`)를 반환한다.
  `RestfulException`/`RestfulExceptionAdvice`(checked exception + 전역
  advice) 체계로 갈아타지는 않았다 — 이유는 ADR-0012 addendum과 동일
  (`persist`/`search` 시그니처 변경과 `@Transactional(rollbackFor=...)`
  추가가 필요해 파급 범위가 커짐).
- `MessageAdminService`가 도메인 메시지 코드 상수(`ERR_INVALID_FORMAT`,
  `ERR_LOCALE_REQUIRED`, `ERR_DUPLICATE`, `ERR_NOT_FOUND`)를 노출하고,
  예외를 던지기 직전 SLF4J `log.warn(...)`으로 `code`/`lang` 등 구체적
  맥락을 남긴다. `messages.properties`/`messages_ko_KR.properties`/
  `messages_en_US.properties`에 각 코드의 기본 문구를 등록했다.
- `validateBucket()`이 항목별 오류를 리스트로 모으던 방식에서 **첫 번째로
  발견한 위반에서 즉시 던지는 방식**으로 바뀌었다. 부수 효과로, `code`
  값이 비어 있는지 별도로 검사하던 코드가 없어졌다 — `MessageCode.matches(null)`이
  이미 `false`를 반환하므로 형식 검증 하나로 흡수된다(공통코드 쪽
  `CodeValue`와 동일한 정리).
- [`docs/api/i18n-message.md`](../api/i18n-message.md)의 400/409 에러
  응답 형식 절을 이 결정에 맞춰 갱신했다.
- 이 결정은 공통코드/메시지 두 도메인 모두에 적용됐다 — 앞으로 메뉴/권한/
  사용자를 구현할 때도 같은 패턴(도메인별 로컬 예외 클래스가 단일 메시지
  코드만 들고, `ExceptionMessage`로 응답, 구체적 맥락은 로그로만)을
  따른다.
