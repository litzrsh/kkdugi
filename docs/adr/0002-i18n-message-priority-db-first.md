# ADR-0002: 다국어 메시지 우선순위 - DB 우선, properties는 fallback

- 상태: Accepted
- 날짜: 2026-09-15

## 컨텍스트

다국어 시스템은 JDBC(DB, `KKDUGI_I18N_MSG`) 기반 메시지와 classpath
properties 기반 메시지 두 소스를 모두 지원해야 한다. 두 소스 사이의 우선순위와
역할 분담을 정해야 한다. 검토한 대안:

1. properties를 fallback으로: DB를 1순위로 조회하고, DB에 없거나 DB
   장애/초기구동 시점에는 properties로 대체
2. 영역별 완전 분리: 메시지 코드의 첫 영역(예: `system.*`)은 항상
   properties, 나머지는 항상 DB로 고정 배정, fallback 없음
3. properties가 1순위, DB는 override

## 결정

**properties를 fallback으로 사용한다 (대안 1).**

- DB(`KKDUGI_I18N_MSG`)를 항상 1순위로 조회한다.
- DB에 값이 없으면 classpath의 `messages*.properties`로 대체한다.
- 둘 다 없으면 메시지 코드 문자열 자체를 반환한다 ([ADR-0004](0004-i18n-messagesource-spring-integration.md) 참고).

## 근거

- 관리자가 DB로 메시지를 수정하면 즉시 반영되어야 하므로 DB가 항상 우선이어야
  한다.
- 시스템 부팅/필수 메시지(예: DB 연결 실패 자체를 알리는 메시지)는 DB에
  의존할 수 없으므로 properties가 안전망 역할을 해야 한다.
- 영역별 완전 분리(대안 2)는 두 저장소 중 하나가 비어 있는 경우에 대한
  안전장치가 없어 위험하다고 판단해 제외했다.

## 결과

- `KkdugiMessageSource`는 DB 캐시 조회 실패 시에만 내부 `ResourceBundleMessageSource`로
  위임하는 2단계 조회 구조를 가진다.
- properties 파일에 있는 메시지라도 같은 코드가 DB에 추가되면 그 순간부터
  DB 값이 우선한다. 이는 의도된 동작이다.
