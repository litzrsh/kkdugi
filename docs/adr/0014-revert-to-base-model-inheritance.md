# ADR-0014: BaseModel/BaseParams 상속 기반으로 재전환 (ADR-0011 되돌림)

- 상태: Accepted
- 날짜: 2026-09-17
- 대체: [ADR-0011](0011-api-define-admin-contract-and-record-models.md)의
  "2. `BaseModel`/`BaseParams` 상속을 record 기반 모델로 전환" 절을
  대체한다. ADR-0011의 나머지 결정(`api-define-admin.md` API 계약,
  `models`/`mapper`/`service`/`exceptions` 패키지 배치, app/api 계층 DTO
  통합, persist 의미 확정)은 그대로 유지된다.

## 컨텍스트

오너가 "모든 model은 `kkdugi.core.models.BaseModel`/`BaseParams`를
상속해야 한다"는 규약을 다시 도입하라고 지시했다. 이는
[ADR-0010](0010-common-base-model-adoption.md)이 처음 도입했다가
[ADR-0011](0011-api-define-admin-contract-and-record-models.md)이
"Java record는 클래스를 상속할 수 없다"는 이유로 대체했던 결정을 다시
뒤집는 것이다.

오너가 다음을 명확히 지시했다:

1. **record는 하나도 남기지 않는다.** 지금 record로 선언된 모델 전부를
   클래스로 바꾼다 — 도메인 모델/검색 파라미터뿐 아니라 커맨드/결과/
   에러/옵션 같은 순수 DTO도 포함한다.
2. DB 행과 1:1 매핑되는 도메인 모델만 `BaseModel`을, 목록 검색
   파라미터만 `BaseParams`를 상속한다. 그 외 순수 DTO는 상속 없이
   플레인 클래스로만 전환한다 — 감사 필드가 무관한 객체(`LoginResponse`,
   `StatusOption` 등)에 억지로 붙지 않도록 하기 위함이며,
   [ADR-0010](0010-common-base-model-adoption.md) 원안의 적용 범위와도
   일치한다.
3. `BaseModel`의 감사 필드명은 `createdId`/`updatedId`(ADR-0010 원안)가
   아니라 **`creatorId`/`updaterId`**로 확정한다.

## 결정

### 1. `BaseModel`/`BaseParams`를 다시 실제 상속 기반으로 만든다

```java
public abstract class BaseModel {
    private Long totalSize;   // @JsonIgnore, 페이징 총 건수(현재 미사용)
    private Long rownum;       // 현재 미사용
    private LocalDateTime createdAt;
    private String creatorId;
    private LocalDateTime updatedAt;
    private String updaterId;
}
```

```java
public abstract class BaseParams {
    private int page;
    private int pageSize;

    public int resolvedPage() { ... }       // 기본값 1
    public int resolvedPageSize() { ... }   // 기본값/상한 200
    public int getOffset() { return (resolvedPage() - 1) * resolvedPageSize(); }
    public int getLimit() { return resolvedPageSize(); }
}
```

- `creatorId`/`updaterId`로 확정한 이유: 저장소에 이미 있던(커밋 이력
  없는) `BaseModel.java` 초안의 필드명을 그대로 따른 것 — 오너가 별도
  기준을 제시하지 않는 한 이 초안을 기준선으로 삼는다.
- `createdAt`/`updatedAt`은 `java.time.LocalDateTime`(ADR-0010 원안은
  `Date`/`String`을 섞어 썼는데, 프로젝트 전체가 `LocalDateTime`을 쓰므로
  버그로 보고 고쳤다). DB 컬럼(`reg_dtm`/`reg_id`/`upd_dtm`/`upd_id`)은
  바뀌지 않는다 — 매핑은 여전히 MyBatis 계층에서만 이뤄진다.
- **`getLimit()`을 ADR-0010 원안의 `page*pageSize`가 아니라
  `resolvedPageSize()`로 정의한다.** ADR-0010의 "미해결 이슈"가 지적한
  대로, 원안은 Oracle ROWNUM `BETWEEN` 스타일 페이징을 염두에 둔
  값이었는데, 이 프로젝트는 Postgres이고 매퍼가 실제로 쓰는 관용구는
  표준 `OFFSET n LIMIT m`
  ([공통 규약 5번](../conventions/common-base-model.md#5-목록-조회-응답--kkdugicoremodelspaget를-재사용한다))이다.
  `getLimit()`이 `pageSize` 그대로를 반환하도록 고쳐 이 모호함을
  해소한다.
- `totalSize`/`rownum`은 필드만 복원하고 어떤 매퍼도 채우지 않는다 —
  실제 ROWNUM 방식 페이징이 필요해지면 그때 연결한다(YAGNI).
- 저장소의 untracked 초안은 `page`/`pageSize`에
  `@JsonProperty(access = Access.WRITE_ONLY)`를 붙여 뒀는데, 이는 **버그로
  보고 제거했다**. WRITE_ONLY는 그 필드를 JSON 직렬화(출력) 대상에서
  제외한다 — 실제 브라우저 클라이언트가 JSON을 직접 만들어 보내는 요청
  경로에는 영향이 없지만, Java 코드가 `MessageSearchParams`/
  `CodeSearchParams`를 `ObjectMapper`로 직렬화해 왕복시키는 경로(대표적으로
  컨트롤러 테스트가 요청 바디를 만드는 방식)에서는 `page`/`pageSize`가
  출력에서 사라져 역직렬화 시 int 필드에 `null`이 들어가려다
  `MismatchedInputException`으로 500이 나는 실제 오류로 이어졌다. 이 값들이
  민감 정보도 아니고 숨겨야 할 이유가 없어 WRITE_ONLY 자체를 제거했다.

### 2. 적용 범위: 도메인 모델/검색 파라미터만 상속, 나머지는 플레인 클래스

| 대상 | 처리 |
|---|---|
| DB 행 1:1 매핑 도메인 모델(`I18nMessage`, `CodeBase`, `CodeLang`) | `BaseModel` 상속 |
| 목록 검색 파라미터(`MessageSearchParams`, `CodeSearchParams`) | `BaseParams` 상속 |
| 그 외 커맨드/결과/에러/옵션 DTO(`MessageContent`, `MessagePersistRequest`, `MessageError`, `CodeContent`, `CodeLocale`, `CodePersistRequest`, `CodeError`, `Page<T>`, `LoginRequest`, `LoginResponse`, `StatusOption`, `LanguageOption`, `AdminUiConfig`, `MessageErrorResponse`, `CodeErrorResponse`) | record → 플레인 클래스(상속 없음) |
| 예외(`{package}.exceptions`), 이벤트(`{package}.events`) | 변경 없음 — 이미 클래스 |

플레인 클래스로 전환된 것들은 불변성을 유지하기 위해 `final` 필드 +
전체 필드 생성자만 쓰고 setter는 두지 않는다(record의 불변성 성격을
최대한 보존). `MessagePersistRequest.insertOrEmpty()` 같은 record의
default 메서드는 인스턴스 메서드로 그대로 옮긴다.

도메인 모델(`I18nMessage`/`CodeBase`/`CodeLang`)은 ADR-0010이
`I18nMessage`에 이미 썼던 패턴을 그대로 따른다 — **자기 필드만 받는
생성자 + 감사 필드는 상속받은 setter로 채운다**:

```java
I18nMessage message = new I18nMessage(msgCode, langCode, msgText);
message.setCreatedAt(now);
message.setCreatorId("SYSTEM");
```

### 3. 매퍼 XML: `<constructor>` → setter 기반 `<id>`/`<result>`,
   `CommonMapper.xml`의 `baseResultMap` 재도입

`I18nMessageMapper.xml`/`CodeBaseMapper.xml`/`CodeLangMapper.xml`의
`resultMap`을 `<id>`/`<result>` 방식으로 되돌렸다. [ADR-0011의
addendum](0011-api-define-admin-contract-and-record-models.md#addendum-2026-09-17-테스트-실행-정책-전환--실제-실행으로-드러난-버그-수정)이
기록한 과거 버그(`record`는 setter가 없어 `ReflectionException`으로
전체 조회가 실패했던 문제)는, 이번에는 대상이 Lombok `@Setter`를 가진
클래스이므로 재발하지 않는다.

`mapper/postgres/CommonMapper.xml`(namespace
`kkdugi.core.models.CommonMapper`)을 새로 추가해 `baseResultMap`에
감사 필드 매핑을 한 번만 정의하고, 세 매퍼가 `extends`로 재사용한다
(ADR-0010 원안의 `CommonMapper.xml` 재현).

## 근거

- 이 규약을 다시 쓰겠다는 것은 오너의 명시적 결정이다 — 프로젝트
  구조에 대한 최종 권한은 오너에게 있다.
- 범위를 도메인 모델/검색 파라미터로 좁힌 것은 ADR-0010 원안이 애초에
  의도했던 범위(감사 필드/페이징이 실제로 의미 있는 대상)와 일치하며,
  `LoginResponse`/`StatusOption`처럼 감사·페이징 개념이 없는 DTO에
  불필요한 필드가 노출되는 것을 막는다.
- `getLimit()` 의미 변경은 실제 버그 예방이다 — 현재 매퍼가 쓰는
  Postgres `OFFSET n LIMIT m` 관용구에 `page*pageSize`를 그대로
  `LIMIT`에 넘기면 매 페이지가 이전 페이지 전체를 포함해 반환하는
  실질적 오류가 된다.

## 결과

- `kkdugi.core.models.BaseModel`/`BaseParams`가 실제로 쓰이는 상태로
  복원됐다(이전까지 커밋 이력 없이 방치돼 있던 초안을 수정해 연결).
- [ADR-0011](0011-api-define-admin-contract-and-record-models.md)의
  "2. BaseModel/BaseParams 상속을 record 기반 모델로 전환" 절은 이
  ADR로 대체됐다 — 해당 ADR 상단에 "Superseded by ADR-0014(해당 절에
  한함)" 표시를 추가했다. API 계약/패키지 배치/DTO 통합/persist 의미
  결정 등 나머지 절은 그대로 유효하다.
- [공통 규약: 도메인 모델/페이징/패키지 배치](../conventions/common-base-model.md)의
  3번 섹션과 체크리스트를 이 ADR에 맞춰 다시 개정했다.
- [CLAUDE.md](../../CLAUDE.md)의 "Domain/data objects are Java records"
  문장을 이 ADR의 규칙으로 교체했다.

## 미해결 이슈

- `totalSize`/`rownum`은 필드만 복원됐고 실제로 채우는 매퍼는 없다 —
  ROWNUM 방식 페이징이 실제로 필요해지면 그때 `baseResultMap`과 매퍼
  SQL을 함께 확장한다.
- 메뉴/권한/사용자 3개 화면은 아직 구현되지 않았다 — 구현 시 이 ADR의
  범위 구분(도메인 모델→`BaseModel`, 검색 파라미터→`BaseParams`, 나머지
  DTO→플레인 클래스)을 그대로 적용한다.
