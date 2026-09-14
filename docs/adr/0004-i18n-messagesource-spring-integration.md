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
