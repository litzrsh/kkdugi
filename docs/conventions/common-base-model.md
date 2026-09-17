# 공통 규약: 도메인 모델/페이징/패키지 배치

- 상태: Accepted — 새 도메인(공통코드, 메뉴, 권한, 사용자, 세션 등)을
  계획/구현할 때 반드시 참조한다.
- 결정 배경/근거: [ADR-0010](../adr/0010-common-base-model-adoption.md)
  (최초 도입, `BaseModel`/`BaseParams` 상속 방식 — 이후 대체됨),
  [ADR-0011](../adr/0011-api-define-admin-contract-and-record-models.md)
  (record 기반으로 전환, 계층별 패키지 배치 규칙 추가 및 이후 addendum으로
  `exceptions`/`events` 세분화 추가)
- 최초 적용 사례: `kkdugi.core.i18n`/`kkdugi.app.admin.i18n`

이 문서는 "왜 이렇게 결정했는가"가 아니라 **"새 도메인을 만들 때 무엇을
어떻게 따라야 하는가"**를 정리한 표준 참조 문서다. ADR은 결정 당시의
맥락을 기록하는 이력이고, 이 문서는 그 결정이 낳은 현재 유효한 규칙만
간결하게 유지한다 — 규칙이 바뀌면 이 문서를 갱신하고, 왜 바뀌었는지는
새 ADR에 남긴다. (2026-09-16에 ADR-0011 기준으로 전면 개정 —
`BaseModel`/`BaseParams` 상속 규칙 삭제. 같은 날, 예외/이벤트 패키지
세분화 규칙 추가.)

## 적용 대상

DB 테이블과 매핑되는 모든 새 도메인, 그 목록을 조회하는 모든 요청/응답.
조직/공통코드/메뉴/권한/사용자 등 앞으로 구현할 모든 모듈이 대상이다.

## 1. 패키지 배치 — 기능마다 역할별 하위 패키지로 나눈다

기능이 걸치는 계층(`core.<feature>`, `app.admin.<feature>` 등)마다 역할별로
하위 패키지를 둔다:

- **`{package}.models`**: DB 행과 매핑되는 도메인 모델("database
  transaction object")과, 목록 조회에 쓰는 검색 파라미터 객체("search
  parameter"). 커맨드/결과 같은 그 외의 순수 데이터 객체도 특별한 이유가
  없으면 여기 둔다.
- **`{package}.mapper`**: MyBatis 매퍼 인터페이스.
- **`{package}.service`**: 비즈니스 로직을 담는 서비스 클래스.
- **`{package}.exceptions`**: 그 기능이 던지는 예외 클래스. `models`에
  두지 않는다.
- **`{package}.events`**: 그 기능이 발행/구독하는 이벤트 객체(현재 이
  프로젝트에 이벤트를 쓰는 기능은 없다 — 필요해지면 이 하위 패키지를
  만든다).
- **`{package}.config`**: Spring 빈 설정(필요한 경우에만).

예시 (`kkdugi.core.i18n`, `kkdugi.app.admin.i18n`):

```
kkdugi.core.i18n
├─ models   — I18nMessage(record), MessageCode(코드 검증)
├─ mapper   — I18nMessageMapper
├─ service  — KkdugiMessageSource
└─ config   — I18nMessageSourceConfig

kkdugi.app.admin.i18n
├─ models      — MessageSearchParams, MessageContent, MessagePersistRequest, MessageError
├─ exceptions  — MessageValidationException, MessageConflictException
└─ service     — MessageAdminService
```

컨트롤러 계층(`kkdugi.api.admin.<feature>`)은 이 규칙 대상이 아니다 —
패키지 루트에 그대로 둔다. 요청/응답 형태가 app 계층 모델과 동일하면(아래
4번) 컨트롤러가 `app.admin.<feature>.models`의 타입을 직접 재사용해도 된다.

## 2. 필드 네이밍 원칙 — DB 컬럼명을 모델/DTO 필드명으로 쓰지 않는다

**원칙: JSON(또는 Java) 필드명만 보고 DB 컬럼명을 유추할 수 없어야 한다.**

- DB 컬럼명(스네이크 케이스, `*_CD`/`*_VAL`/`*_DTM`/`*_ID`/`*_NM` 같은 레거시
  축약 관례)을 단순히 camelCase로 바꾼 이름을 그대로 쓰지 않는다. 예:
  `msg_cd` → `msgCd`(❌) 대신 `msgCode`(✅), `msg_val` → `msgVal`(❌) 대신
  `msgText`(✅), `reg_dtm`/`upd_dtm` → `regDtm`/`updDtm`(❌) 대신
  `createdAt`/`updatedAt`(✅).
- 컬럼명과의 연결은 MyBatis `resultMap`/파라미터 바인딩(`#{}`)에서만
  이뤄진다. Java/JSON 필드명과 DB 컬럼명 사이에 "이름이 비슷해서 알 수
  있는" 암묵적 결합을 만들지 않는다.
- **예외**: 매퍼 인터페이스의 `@Param(...)` 이름처럼 JSON으로 노출되지
  않는 내부 쿼리 파라미터 이름은 이 원칙의 적용 대상이 아니다.
- 점검 방법: 필드명에 `Cd`/`Dtm`/`Val`/`Nm` 등 DB 컬럼 축약 표기가 그대로
  남아 있으면 위반이다.

## 3. 도메인 모델과 앱 계층 데이터 객체는 record로 작성한다

- **Java record는 클래스를 상속할 수 없다.** `BaseModel`처럼 감사 필드를
  상속으로 재사용하는 방식은 record와 근본적으로 맞지 않는다 — 이전
  버전의 이 규약은 `BaseModel extends`를 요구했으나, i18n을 record로
  옮기는 과정에서 이 제약이 드러나 폐기했다([ADR-0011](../adr/0011-api-define-admin-contract-and-record-models.md)).
- DB 행과 1:1 대응하는 도메인 모델은 record로 선언하고, 감사 필드
  (`createdAt`/`createdId`/`updatedAt`/`updatedId` 등)가 필요하면 그
  record에 직접 필드로 넣는다. 상속으로 공유하지 않는다 — 반복이 생기더라도
  record의 불변성/단순함이 주는 이점이 더 크다고 판단했다.
- 검색 파라미터, 커맨드, 결과, 저장 요청/응답 같은 순수 데이터 객체도
  전부 record로 작성한다. 클래스로 만들어야 할 이유(가변 상태가 꼭
  필요한 경우 등)가 없으면 record가 기본값이다.
- **예외(`{package}.exceptions`)와 이벤트(`{package}.events`)는 이 규칙
  대상이 아니다.** 예외는 `RuntimeException` 등을 상속해야 하므로 항상
  클래스다. 이벤트는 필요해지면 그때 record/클래스 여부를 정한다.

## 4. app/api 계층 DTO를 굳이 분리하지 않아도 된다

과거([ADR-0007](../adr/0007-admin-crud-single-endpoint-batch-save.md))에는
app 계층 커맨드/결과와 api 계층 JSON DTO를 항상 분리했다(Jackson 관심사
분리, 다른 진입점에서 서비스 재사용 목적). 이 분리가 실제로 가치 있는
경우(둘의 모양이 다르거나, Jackson 전용 애너테이션이 app 계층에 새면 안
되는 경우)에는 계속 분리한다. 하지만 **요청/응답이 단순한 plain record이고
둘의 모양이 완전히 같다면**, `app.admin.<feature>.models`의 타입을
컨트롤러가 그대로 재사용해 중복을 없앤다([ADR-0011](../adr/0011-api-define-admin-contract-and-record-models.md)의
i18n 재구현이 이 예).

## 5. 목록 조회 응답 — `kkdugi.core.models.Page<T>`를 재사용한다

```java
public record Page<T>(
        int page,
        int pageSize,
        long totalItems,
        long totalPages,
        List<T> contents
) {
    public static <T> Page<T> of(List<T> contents, int page, int pageSize, long totalItems) {
        long totalPages = Math.max(1, (long) Math.ceil((double) totalItems / pageSize));
        return new Page<>(page, pageSize, totalItems, totalPages, contents);
    }
}
```

- 필드 이름(`page`/`pageSize`/`totalItems`/`totalPages`/`contents`)은
  `docs/api-define-admin.md`의 모든 목록 응답 형태와 정확히 일치하므로,
  화면마다 새 응답 DTO를 정의하지 말고 컨트롤러가 `Page<T>`를 그대로
  반환한다.
- `T`에는 어떤 제약도 없다 — 이전 버전(`T extends BaseModel`)의 제약은
  BaseModel 폐기와 함께 없앴다.
- 목록 조회 요청 파라미터는 공용 베이스 클래스 없이, 화면마다
  `{package}.models`에 `code`/`page`/`pageSize` 같은 필드를 직접 선언한
  record로 만든다(예: `MessageSearchParams`). `page`는 1-base, 기본값
  1이고 `pageSize` 기본값/상한은 200으로 통일한다(`docs/api-define-admin.md`의
  모든 예시가 `"page": 1, "pageSize": 200`을 쓴다). 페이징 계산은
  표준 SQL `OFFSET (page-1)*pageSize LIMIT pageSize`를 쓴다 — 예전
  `BaseParams.getOffset()`/`getLimit()`이 갖고 있던 ROWNUM `BETWEEN` 스타일
  의미의 모호함은 이 방식으로 해소되었다.

## 6. 공통 유틸(`kkdugi.core.util`)은 필요할 때만 만든다

과거 버전은 `CommonUtils`/`DateUtils`/`SessionUtils`를 미리 만들어 뒀지만,
실제로 쓰는 곳이 생기기 전까지는 존재 이유가 없어 i18n 재구현 과정에서
제거했다. 여러 도메인에서 반복되는 null/blank 체크나 날짜 연산이 실제로
나타나면 그때 `core.util`을 다시 만든다 — 미리 만들어두지 않는다(YAGNI).
같은 이유로 `{package}.events`도 실제로 이벤트를 발행/구독하는 기능이
생기기 전까지는 빈 채로 만들어두지 않는다.

## 7. MyBatis 매퍼 XML 서식

`core.serial.mapper.SerialMapper.xml`에 오너가 직접 확립한 형식을 모든
매퍼 XML에 적용한다:

- XML 선언은 공백 없이 `<?xml version="1.0" encoding="UTF-8"?>`.
- `<mapper>` 바로 아래 요소는 2-space 들여쓰기.
- **모든 SQL 본문은 `<![CDATA[ ... ]]>`로 감싼다.** CDATA 블록 자체와 그
  안의 SQL 텍스트는 들여쓰기 없이 항상 컬럼 0에서 시작한다(주변 요소의
  들여쓰기와 무관하게).
- `<where>`/`<if>`/`<choose>`/`<foreach>`/`<include>` 같은 MyBatis 동적
  태그는 **CDATA 밖에** 실제 XML 요소로 둔다(CDATA는 XML 파싱 자체를
  끄므로 동적 태그를 CDATA 안에 넣으면 그냥 문자로 취급된다). 정적 SQL
  조각 하나하나를 CDATA로 감싸고, 그 사이사이에 동적 태그를 실제 XML로
  끼워 넣는 식으로 작성한다(`CodeBaseMapper.findChildren`이 `<where>`/
  `<choose>`/`<if>`를 이렇게 CDATA와 섞어 쓰는 예시).
- 각 `<select>/<insert>/<update>/<delete>` 바로 위에 문서화용 XML 주석을
  둔다:
  ```xml
  <!--
    * QueryID=<statementId>
    * Description=<한 줄 설명>
    -->
  ```
- 그 statement의 **첫 번째** CDATA 블록 맨 위 줄에 SQL 주석으로 전체
  경로를 반복한다: `/* QueryID=<namespace>.<statementId> */`. 같은
  statement 안에 CDATA가 여러 개 나뉘어도(동적 태그로 끊긴 경우)
  이 주석은 첫 블록에만 쓴다.
- **주의**: 이 주석에 `#{...}`처럼 보이는 텍스트를 절대 넣지 않는다 —
  MyBatis는 SQL 주석을 이해하지 못하고 텍스트에 있는 `#{...}`를 전부
  실제 바인드 파라미터로 센다. 예전에 `SerialMapper.xml`의 문서용 주석
  안에 `#{key}`/`#{size}`/`#{id}`가 그대로 남아 있어서 바인딩 개수가
  안 맞는 실제 버그가 났었다(수정 경위는 ADR-0012 addendum 참고).
- `<sql id="...">` 재사용 조각도 CDATA로 감싼다.
- `resultMap`은 이 규칙(CDATA/QueryID) 대상이 아니다 — 필요하면 일반
  XML 주석으로 설명만 남긴다(예: record라 `<constructor>`를 쓴다는 설명).

전체 예시는 `I18nMessageMapper.xml`/`CodeBaseMapper.xml`/`CodeLangMapper.xml`/
`SerialMapper.xml`을 참고한다 — 넷 다 이 서식으로 맞춰져 있다.

## 8. 코드성 enum은 `CodeEnums`를 구현하고 `kkdugi.core.enums`에 둔다

DB에 짧은 코드 문자열로 저장되면서 화면에는 i18n 라벨로 보여줘야 하는
고정된 값 집합(사용자 상태 등)은 일반 Java enum이 아니라
`kkdugi.core.enums.CodeEnums`를 구현하는 enum으로 만든다. 이런 enum은
특정 기능 하나에 속한다기보다 여러 기능이 공유하는 값 집합이라(예:
`UserStatus`는 사용자 화면뿐 아니라 세션/권한 쪽에서도 참조될 수 있다)
`{package}.models`가 아니라 최상위 `kkdugi.core.enums`에 모아 둔다 —
`core.models`/`core.util`/`core.serial`과 같은 위치의, 기능에 종속되지
않는 공용 인프라 패키지다.

```java
public interface CodeEnums {
    String getCode();       // DB에 저장되는 짧은 코드 (예: "10")
    String getLabelCode();  // i18n 메시지 코드 (예: "user.status.10")

    default String getLabel() {
        return MessageUtils.getMessage(getLabelCode());
    }

    static <E extends CodeEnums> E fromCode(Class<E> clazz, String code) { ... }
}

@RequiredArgsConstructor
@Getter
public enum UserStatus implements CodeEnums {
    PEND("10", "user.status.10"),
    NORM("20", "user.status.20"),
    ...;

    private final String code;
    private final String labelCode;

    public static UserStatus fromCode(String code) {
        return CodeEnums.fromCode(UserStatus.class, code); // 인터페이스명을 반드시 붙인다 —
    }                                                       // 인터페이스의 static 메서드는
}                                                            // 구현 클래스에 상속되지 않는다.
```

- `getLabelCode()`는 [메시지 코드 규칙](../i18n-system-design.md#메시지-코드-규칙)
  (`{영역}.{유형}.{코드}`, 소문자/숫자/`_`)을 따른다.
- `getLabel()`은 `kkdugi.core.util.MessageUtils`(정적 서비스 로케이터,
  `SerialUtils`와 같은 패턴)를 통해 메시지를 조회한다 — 실제
  `MessageSource`가 아직 연결 전이거나 그 코드에 해당하는 메시지가
  DB/properties 어디에도 없으면 `getLabelCode()` 값 그대로 반환된다(예외
  없이 항상 문자열 반환 — i18n 시스템의 기본 동작과 동일).
- **Lombok**을 쓴다(`@Getter`/`@RequiredArgsConstructor`) — `pom.xml`에
  이미 `org.projectlombok:lombok`이 있다.
- **MyBatis 연동**: `CodeEnums`를 구현한 enum은 컬럼마다 `typeHandler=`를
  지정할 필요가 없다. `kkdugi.core.mybatis.CodeEnumTypeHandler`(범용
  TypeHandler는 기능에 종속되지 않으므로 `core.enums`가 아니라
  `core.mybatis`에 둔다 — one-off 핸들러인 `SessionUserTypeHandler`도
  같은 패키지)를 `application.yml`의
  `mybatis.configuration.default-enum-type-handler`로
  한 번만 등록해두면, MyBatis가 `CodeEnums` 구현 enum 타입마다 이 핸들러를
  자동으로 붙여 `getCode()` 문자열로 저장하고 `CodeEnums.fromCode(...)`로
  복원한다. **주의**: 이 설정은 프로젝트의 모든 enum에 기본 적용되므로,
  DB 컬럼에 매핑되는 코드성 enum은 반드시 `CodeEnums`를 구현해야 한다 —
  구현하지 않은 순수 enum을 그대로 매핑하려 하면 이 핸들러가 `getCode()`를
  호출하다 실패한다.

## 새 도메인 착수 시 체크리스트

- [ ] `{package}.models`/`.mapper`/`.service`(/`.config`)로 나뉘어 있는가
- [ ] 예외는 `{package}.exceptions`에, 이벤트는 `{package}.events`에 있는가
      (`models`에 섞여 있지 않은가)
- [ ] 필드명에서 DB 컬럼명(축약형 포함)이 그대로 유추되지 않는가 (2번)
- [ ] 도메인 모델/데이터 객체가 record인가(예외·이벤트 제외)
- [ ] record를 매핑하는 resultMap이 `<constructor>`를 쓰는가(setter가
      없으므로 `<id>`/`<result>`만으로는 매핑이 실패한다)
- [ ] 목록 응답이 `core.models.Page<T>`로 감싸지는가, 매퍼가
      1-base `page`/`pageSize`로부터 표준 `OFFSET`/`LIMIT`을 계산하는가
- [ ] app 계층과 api 계층 DTO를 분리해야 할 실질적 이유가 있는지 확인했는가
      (없으면 하나로 합친다)
- [ ] 매퍼 XML이 7번의 CDATA/QueryID 서식을 따르는가, 주석 안에
      `#{...}`가 남아있지 않은가
- [ ] DB에 코드로 저장되는 고정값 집합을 enum으로 만들 때 `CodeEnums`를
      구현했는가(`kkdugi.core.enums`), 컬럼마다 `typeHandler=`를 따로
      지정하지 않았는가(8번 — `default-enum-type-handler`가 자동 처리)

## 알려진 예외 (규약 미적용 상태)

- `kkdugi.core.i18n.mapper.I18nMessageMapper`의 `@Param` 이름은 JSON
  비노출 내부 파라미터라 2번 원칙의 적용 대상이 아니다(위 2번 "예외"
  참고) — 위반이 아니라 명시적 범위 제외다.
- `kkdugi.app.admin.i18n`에는 아직 `events` 패키지가 없다 — 이 기능에
  이벤트가 없기 때문이며, 규약 위반이 아니다.
- 공통코드/메뉴/권한/사용자 4개 화면은 아직 미구현 상태다. 구현 시 이
  문서의 규칙(패키지 배치, `Page<T>` 재사용 포함)을 그대로 적용한다.
