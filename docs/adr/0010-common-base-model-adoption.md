# ADR-0010: 공통 베이스 모델 도입 (`core.models`/`core.util`) 및 i18n 필드 리네이밍

- 상태: Superseded by [ADR-0011](0011-api-define-admin-contract-and-record-models.md)
  — `BaseModel`/`BaseParams`/`CommonMapper.xml` 상속 메커니즘에 한함
  (아래 마지막 addendum 참고). 필드 리네이밍·감사 필드 분리라는
  문제의식과 `Page` 응답 래퍼 개념은 ADR-0011에도 유지됨.
- 날짜: 2026-09-15
- 비고: 이 ADR은 오너가 다국어 시스템 구현 완료 후 프로젝트 구조를 직접 다듬은
  내용을 사후에 기록한 것이다. 코드는 이미 이 결정을 반영한 상태로 존재한다.
  이 결정이 낳은 실행 규약(무엇을 어떻게 따라야 하는가)은
  [공통 규약: 도메인 모델/페이징](../conventions/common-base-model.md)
  문서에 별도로 정리해, 앞으로의 계획/구현에서는 이 ADR 대신 그 문서를
  참조한다. **단, 그 문서는 이후 ADR-0011에 맞춰 다시 개정되었다 —
  현재 유효한 규칙은 항상 그 문서의 최신 버전을 따른다.**

## 컨텍스트

다국어(i18n) 시스템 구현 당시 `I18nMessage`는 자체적으로 `msgCd`/`langCd`/
`msgVal`/`regDtm`/`regId`/`updDtm`/`updId` 필드를 모두 선언하는 독립 모델이었다.
그러나 다음 단계로 예정된 공통코드 시스템, 세션 시스템 등도 등록/수정
감사(audit) 필드와 페이징 규약을 동일하게 필요로 하므로, 도메인마다 이를
반복 선언하면 컨벤션이 흩어질 위험이 있었다. 이에 오너가 `core.models`/
`core.util`에 공통 기반 클래스를 먼저 만들고 `I18nMessage`를 여기에 맞춰
리팩터링했다.

## 결정

**공통 기반 클래스를 `kkdugi.core.models`/`kkdugi.core.util`에 두고, 이후
모든 도메인 모델이 이를 상속/재사용한다.** (실행 규약 전문은
[공통 규약: 도메인 모델/페이징](../conventions/common-base-model.md) 참고)

- `kkdugi.core.models.BaseModel` (`Serializable`): 모든 도메인 모델이
  상속하는 공통 베이스. `totalSize`(페이징 총 건수, `@JsonIgnore`),
  `rownum`, `createdId`(`@JsonIgnore`), `createdAt`, `updatedId`
  (`@JsonIgnore`), `updatedAt` 필드를 갖는다.
- `kkdugi.core.models.BaseParams`: 목록 조회 요청 파라미터 공통 베이스.
  `page`(기본 1), `pageSize`(기본 200, 둘 다 `@JsonProperty(WRITE_ONLY)`)와
  파생 값 `getOffset()`(`(page-1)*pageSize`), `getLimit()`
  (`page*pageSize`)을 제공한다.
- `kkdugi.core.models.Page<T extends BaseModel>`: 조회 결과 리스트와
  `BaseParams`를 받아 `page`/`pageSize`/`totalItems`(리스트 첫 행의
  `totalSize`에서 추출)/`totalPages`를 계산해 감싸는 공통 응답 래퍼.
- `mapper/postgres/CommonMapper.xml` (namespace `kkdugi.core.models.CommonMapper`):
  `baseResultMap`이 `REG_DTM`/`REG_ID`/`UPD_DTM`/`UPD_ID`(+ `TOTAL_SIZE`/
  `RNUM`) 컬럼을 `BaseModel`의 `createdAt`/`createdId`/`updatedAt`/
  `updatedId`(+`totalSize`/`rownum`)로 매핑한다. 각 도메인 매퍼는 자신의
  `resultMap`에서 이를 `extends`해 감사 필드 매핑을 반복하지 않는다.
- `kkdugi.core.util`: `CommonUtils`(`trim`/`isEmpty`/`isNotEmpty` — 문자열,
  배열, 컬렉션 공통), `DateUtils`(`Date` ± `Period`/`Duration`, `diff`),
  `SessionUtils`(현재는 빈 placeholder — 세션 시스템 구현 시 채워질 예정).
- **i18n 필드 리네이밍**: `I18nMessage`가 `BaseModel`을 상속하도록 변경되어
  `msgCd`→`msgCode`, `langCd`→`langCode`, `msgVal`→`msgText`로 이름이
  바뀌었고, `regDtm`/`regId`/`updDtm`/`updId`는 자체 선언 대신 상속받은
  `createdAt`/`createdId`/`updatedAt`/`updatedId`로 대체되었다. **DB
  컬럼명(`msg_cd`/`lang_cd`/`msg_val`/`reg_dtm`/`reg_id`/`upd_dtm`/
  `upd_id`)은 변경되지 않았다** — 리네이밍은 MyBatis 매핑 계층(Java 프로퍼티
  이름)에만 적용된다.
- **레이어 경계상 이름은 유지**: `app.admin.i18n`/`api.admin.i18n`의
  커맨드·DTO(`MessageRowCommand`, `MessageRowResult`, `MessageRowRequest`,
  `MessageRowResponse` 등)는 기존 `msgCd`/`langCd`/`msgVal`/`updDtm` 이름을
  그대로 유지한다. 이는 이미 문서화된 관리 API의 JSON 계약([ADR-0007](0007-admin-crud-single-endpoint-batch-save.md))을
  바꾸지 않기 위함이며, `core.i18n.I18nMessage`(내부 도메인 모델)와
  `app`/`api` 계층 모델(외부 계약) 사이의 이름 불일치는 의도된 것이다.
  `MessageAdminController`/`MessageAdminService`가 그 변환을 담당한다.

## 근거

- 앞으로 추가될 모든 도메인(공통코드, 세션 등)이 등록/수정 감사 필드와
  페이징 규약을 매번 새로 정의하는 대신 공통 기반을 재사용하게 하기 위함.
- DB 컬럼명(스네이크 케이스, 레거시 네이밍 관례인 `REG_*`/`UPD_*`)과 Java
  프로퍼티명(`created*`/`updated*`)을 분리해, Java 쪽 네이밍을 프로젝트
  전반에서 일관되게 가져가면서도 기존 DB 스키마/마이그레이션은 건드리지
  않을 수 있다.
- API 계약(app/api 계층 이름)과 내부 도메인 모델(core 계층 이름)을
  분리해두면, 내부 리팩터링이 외부 클라이언트(그리드 UI 등)에 영향을 주지
  않는다.

## 결과

- 이 결정의 실행 규약은 [공통 규약: 도메인 모델/페이징](../conventions/common-base-model.md)
  문서로 별도 정리했다. 새 도메인(공통코드, 메뉴, 권한, 사용자, 세션 등)을
  계획/구현할 때는 이 ADR이 아니라 그 문서를 참조한다 — 이 ADR은 "왜"를
  설명하는 이력으로 남긴다.
- 리소스 경로 변경: `resources/mapper/I18nMessageMapper.xml` →
  `resources/mapper/postgres/I18nMessageMapper.xml`(+ 신규
  `resources/mapper/postgres/CommonMapper.xml`), `resources/messages*.properties`
  → `resources/messages/messages*.properties`. `application.yml`의
  `mybatis.mapper-locations`도 `classpath:mapper/postgres/*Mapper.xml`로,
  `I18nMessageSourceConfig`의 properties basename도
  `classpath:messages/messages`로 갱신되었다.
- `I18nMessageSourceConfig`가 `kkdugi.core.i18n` 패키지에서
  `kkdugi.core.i18n.config` 하위 패키지로 이동했다.
- `MessageAdminService.insertRow`/`updateRow`는 `I18nMessage`의 7-인자
  생성자 대신 3-인자 생성자(`msgCode`, `langCode`, `msgText`) + `BaseModel`이
  제공하는 setter(`setCreatedAt`/`setCreatedId`/`setUpdatedAt`/
  `setUpdatedId`)로 감사 필드를 채운다.
- `mapper/postgres/` 하위 경로 이름은 Postgres 외 다른 DB 방언(예: Oracle)
  매퍼를 나중에 `mapper/oracle/`처럼 나란히 둘 가능성을 시사하지만, 이번
  결정 범위에서 확정된 것은 아니다.

## 미해결 이슈

- `BaseParams.getOffset()`/`getLimit()`은 `(page-1)*pageSize` ~
  `page*pageSize` 구간을 반환하도록 설계되어 있어, 전형적인 SQL
  `OFFSET n LIMIT m` 관용구가 아니라 ROWNUM `BETWEEN` 스타일 페이징(한
  쿼리가 `TOTAL_SIZE`/`RNUM`까지 함께 반환)을 염두에 둔 것으로 보인다.
  그러나 `I18nMessageMapper.search`/`count`와 `MessageAdminService.search`는
  아직 이 공통 규약을 쓰지 않고, 기존의 별도 `count` 쿼리 + 0-base
  `offset`/`size` 파라미터 방식을 그대로 쓰고 있다 — `core.models`의 공통
  페이징 기반이 i18n 모듈에는 아직 반영되지 않은 상태다.
- 오너가 지적한 대로, 관리 화면의 목록 조회는 `(msgCd, langCd)` 행 단위가
  아니라 **`msgCode` 단위로 페이징하고, 언어별 값을 컬럼으로 pivot**하는
  모델이어야 페이징이 올바르게 동작한다(현재 flat 행 모델에서는 같은
  `msgCode`가 언어 수만큼 여러 페이지에 걸쳐 흩어질 수 있다). 이는
  [ADR-0007](0007-admin-crud-single-endpoint-batch-save.md)이 검토 후
  기각했던 "그룹 행(pivot)" 대안을 다시 채택해야 함을 의미한다. 구체적인
  재설계(요청/응답 모델, `crudType`을 행 단위가 아닌 언어별 셀 단위로 어떻게
  표현할지 등)는 아직 확정되지 않았으므로 이 ADR에서 결정하지 않는다 —
  설계가 확정되면 ADR-0007에 addendum을 추가하거나 이를 대체하는 별도
  ADR-0011로 기록한다. 설계 문서의 ["관리 API 모델" 절](../i18n-system-design.md#관리-api-모델)도
  함께 갱신한다.
- 참고: `docs/design/adr/0003-message-pivot-i18n.md`에 이 pivot
  재설계에 대한 별도의 기획/디자인 검토가 이미 존재한다("설계 채택, 조회 API
  보완 필요" 상태) — grouped 조회 API의 URI/DTO 미확정, 등록 언어 목록을
  `ko_KR`/`en_US` 두 개로 고정하지 않을 것, 코드 단위 삭제 시 등록된 모든
  언어에 DELETE를 생성해야 하는 점, 미등록 셀과 빈 문자열의 구분 등을
  다룬다. `kkdugi-design/`은 이 프로젝트의 화면 기획을 위한 별도 문서
  트랙이며 자체 ADR 번호 체계를 쓴다(`docs/adr/`와 번호가 겹치지만 서로
  다른 문서다) — 실제 구현에 착수할 때 두 문서를 함께 참고해야 한다.

## Addendum (2026-09-15): API 계층 필드명도 통일 (위 "레이어 경계상 이름은 유지" 결정을 뒤집음)

[공통 규약: 도메인 모델/페이징](../conventions/common-base-model.md)에 "필드
네이밍 원칙"(JSON 필드명만 보고 DB 컬럼명을 유추할 수 없어야 한다)이 새로
추가되면서, 위 "결정"의 "레이어 경계상 이름은 유지" 항목이 이 원칙과
정면으로 충돌한다는 점이 드러났다 — `msgCd`/`langCd`/`msgVal`/`updDtm`은
`msg_cd`/`lang_cd`/`msg_val`/`upd_dtm` 컬럼명이 필드명만으로 그대로
드러나는 이름이었다. 오너가 API 계층까지 통일하도록 지시해, 이 부분을
뒤집는다.

- `app.admin.i18n.MessageRowCommand`/`MessageRowResult`/`MessageRowError`,
  `api.admin.i18n.MessageRowRequest`/`MessageRowResponse`/
  `MessageRowErrorResponse`가 모두 `core.i18n.I18nMessage`와 동일한 이름
  (`msgCode`/`langCode`/`msgText`/`updatedAt`)을 쓰도록 변경되었다.
- 이는 **API 계약(요청/응답 JSON의 필드명) 자체가 바뀌는 변경**이다.
  [설계 문서의 "관리 API 모델"](../i18n-system-design.md#관리-api-모델) 절의
  요청/응답 예시가 현재 실제 계약을 반영해 갱신되었다.
- 계층별로 모델을 분리해 둔 것 자체(`I18nMessage` ↔ `MessageRowCommand` ↔
  `MessageRowRequest`)는 유지한다 — 그 이유는 이름 차이가 아니라 JSON
  직렬화 관심사 분리였으므로, 이번 변경과 무관하다.
- `I18nMessageMapper`(MyBatis 매퍼 인터페이스/XML)의 `@Param("msgCd")`/
  `@Param("langCd")`는 이번 통일 대상에서 제외했다 — JSON으로 노출되지
  않는 내부 쿼리 파라미터 이름이라 네이밍 원칙의 적용 대상이 아니다.
- [공통 규약: 도메인 모델/페이징](../conventions/common-base-model.md)의
  "알려진 위반" 항목은 이 addendum으로 해소되어 문서에서 제거했다.

## Addendum (2026-09-16): BaseModel/BaseParams 상속 메커니즘 대체됨

오너가 `docs/api-define-admin.md`를 API 계약으로 확정하면서 i18n을 그
계약에 맞춰 재구현했다. 이 과정에서 `I18nMessage`가 (이미 그 전 단계에서
시도되었던 대로) **불변 record**로 확정되었는데, **Java record는 클래스를
상속할 수 없다** — `record I18nMessage(...)`는 물리적으로 `extends
BaseModel`을 쓸 수 없다.

- `kkdugi.core.models.BaseModel`/`BaseParams`(추상 클래스)와
  `mapper/postgres/CommonMapper.xml`의 `baseResultMap`은 삭제했다.
- `kkdugi.core.models.Page<T>`는 record로 다시 만들어 유지한다(더 이상
  `T extends BaseModel` 제약 없음) — `page`/`pageSize`/`totalItems`/
  `totalPages`/`contents` 필드는 api-define-admin.md의 모든 목록 응답
  형태와 정확히 일치한다.
- 감사 필드(`createdAt`/`createdId`/`updatedAt`/`updatedId`)는 상속 대신
  각 도메인 record가 직접 필드로 선언한다.
- 자세한 내용과 새 패키지 배치 규칙(`{package}.models`/`.mapper`/
  `.service`/`.config`)은 [ADR-0011](0011-api-define-admin-contract-and-record-models.md)과
  갱신된 [공통 규약: 도메인 모델/페이징](../conventions/common-base-model.md)
  문서를 참고한다. 이 addendum이 위 "결과"/"미해결 이슈" 절의 `BaseModel`/
  `BaseParams` 관련 서술을 대체한다 — 그 절들은 이력으로만 남겨둔다.
