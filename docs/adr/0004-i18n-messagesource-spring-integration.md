# ADR-0004: 다국어 API - Spring `MessageSource` 인터페이스 구현

- 상태: Accepted
- 날짜: 2026-09-15

## 컨텍스트

메시지 조회 API를 어떤 인터페이스로 노출할지 정해야 한다. 검토한 대안:

1. `org.springframework.context.MessageSource`를 직접 구현
2. 독자적인 서비스 인터페이스(예: `MessageService`)를 새로 설계

## 결정

**Spring의 `MessageSource`를 직접 구현한다 (`KkdugiMessageSource extends
AbstractMessageSource`).**

## 근거

- Bean Validation(`@NotNull(message = "{...}")` 등), 향후 웹 계층(Thymeleaf
  `#{...}` 등)과 별도 연동 작업 없이 Spring 표준 생태계에 그대로 편입된다.
- `AbstractMessageSource`가 `useCodeAsDefaultMessage`, `MessageFormat` 인자
  치환 등을 템플릿 메서드로 제공하므로, "메시지를 찾지 못하면 코드를 직접
  반환"하는 요구사항을 `setUseCodeAsDefaultMessage(true)`로 그대로 만족시킬
  수 있어 직접 구현 비용이 낮다.
- 독자 인터페이스(대안 2)는 자유도는 높지만 Bean Validation 등과의 연동을
  위해 결국 `MessageSource` 어댑터를 별도로 만들어야 하므로 이중 작업이다.

## 결과

- `KkdugiMessageSource`는 `resolveCode(code, locale)`을 오버라이드하여 ①
  DB 캐시 조회 ② 내부 `ResourceBundleMessageSource`로 위임 순서로 동작한다
  ([ADR-0002](0002-i18n-message-priority-db-first.md)).
- Spring 컨테이너에 `MessageSource` 빈으로 등록되므로, `MessageSourceAware`를
  구현하는 다른 Spring 컴포넌트(예: `LocalValidatorFactoryBean`)와 자연스럽게
  연결된다.

## 보강 (2026-09-15): 두 어댑터를 parent 체이닝으로 연결

구현 방식을 더 구체화하면서, "내부적으로 위임"이라는 표현이 두 가지로
오해될 수 있다는 점이 드러났다 (KkdugiMessageSource가 properties 소스를
필드로 들고 수동으로 호출하는 방식인지, 아니면 Spring이 제공하는
parent-child 체이닝인지). 아래와 같이 확정한다.

**두 개의 `MessageSource` 어댑터**가 존재한다. 각각 서로 다른 저장소를
`MessageSource` 계약에 맞춰주는 구현체라는 의미다.

| 어댑터 | 감싸는 저장소 | 구현 주체 |
|---|---|---|
| `KkdugiMessageSource` | DB(`I18nMessageMapper` + 인메모리 캐시) | 직접 구현 |
| `ReloadableResourceBundleMessageSource` | classpath `messages*.properties` | Spring 기본 제공 (설정만) |

이 둘은 수동 위임 코드 대신, Spring의 `HierarchicalMessageSource.
setParentMessageSource(...)` 체이닝으로 연결한다.

- **루트(자식) 빈 = `KkdugiMessageSource`.** Spring 컨테이너에 `MessageSource`
  빈으로 노출되는 것은 이것 하나뿐이다. `resolveCode(code, locale)`은 오직
  DB 캐시만 조회하고 properties는 전혀 알지 못한다(관심사 분리).
- **parent = `ReloadableResourceBundleMessageSource`.** `KkdugiMessageSource.
  setParentMessageSource(reloadableSource)`로 연결한다. `AbstractMessageSource.
  getMessageInternal()`이 루트의 `resolveCode()`가 null을 반환했을 때만
  parent의 `getMessageInternal()`을 직접 호출하므로(parent가
  `AbstractMessageSource`이기 때문에 parent 자신의 `getMessage()`나
  `useCodeAsDefaultMessage`는 끼어들지 않는다), DB → properties 순서가 그대로
  보장된다.
- **`useCodeAsDefaultMessage(true)`는 루트(`KkdugiMessageSource`)에만
  설정한다.** 체인 전체(DB, properties)가 모두 못 찾았을 때만 코드 문자열
  자체가 반환된다.
- "parent"라는 이름 때문에 헷갈리기 쉬운데, Spring에서 parent는 항상
  **fallback**을 의미한다("우선순위가 높다"는 뜻이 아니다). DB가 우선이므로
  DB 쪽이 자식/루트, properties가 parent가 되어야 한다 — 반대로 연결하면
  [ADR-0002](0002-i18n-message-priority-db-first.md)의 우선순위가 뒤집힌다.
- properties 쪽 구현체는 `ResourceBundleMessageSource` 대신
  **`ReloadableResourceBundleMessageSource`**를 사용하기로 했다(오너 결정).
  당장은 `classpath:messages` 베이스네임만 사용하고 파일시스템 경로나 짧은
  `cacheSeconds`는 쓰지 않지만, 이후 파일시스템 기반 재로딩이 필요해져도
  클래스 교체 없이 설정만으로 대응할 수 있다.
