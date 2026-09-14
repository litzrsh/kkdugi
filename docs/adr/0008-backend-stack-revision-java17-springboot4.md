# ADR-0008: 백엔드 스택 버전 확정 - Java 17 + Spring Boot 4.0.4

- 상태: Accepted
- 날짜: 2026-09-15
- 이전 결정: [ADR-0001](0001-tech-stack-java-spring-maven.md) (Java 21 + Spring Boot 3.3.x)을 대체

## 컨텍스트

[ADR-0001](0001-tech-stack-java-spring-maven.md)에서 Java 21 + Spring Boot
3.3.x로 잠정 결정했으나, 프로젝트 오너가 실제 운영/배포 환경 제약을 반영해
백엔드 버전을 다시 지정했다.

## 결정

- 언어: **Java 17**
- 프레임워크: **Spring Boot 4.0.4**
- 빌드 도구: Maven, 단일 모듈 (ADR-0001의 결정 유지)
- 영속성: MyBatis (ADR-0001 및 [ADR-0005](0005-persistence-mybatis.md) 유지)

## 근거

- 프로젝트 오너의 명시적 지정. Java 21의 최신 언어 기능(가상 스레드 등)보다
  버전 고정성/운영 환경 호환을 우선한다.
- Spring Boot 4.0(Spring Framework 7 기반)은 Java 17을 최소 베이스라인으로
  요구하므로 Java 17과 짝을 이루는 조합 자체는 유효하다.

## 결과

- `pom.xml`의 `java.version`(또는 `<properties><java.version>`)을 17로,
  `spring-boot-starter-parent` 버전을 4.0.4로 지정한다.
- MyBatis, Flyway 등 나머지 의존성은 Spring Boot 4.0.4 라인과 호환되는
  버전으로 선택해야 한다. 이 문서 작성 시점에는 정확한 호환 버전을 확정하지
  않았으므로, 프로젝트 스캐폴딩(구현 착수) 시점에 실제 사용 가능한
  `mybatis-spring-boot-starter` / `flyway-database-postgresql` 버전을
  확인하고 고정한다.
- ADR-0002~0007에서 결정한 아키텍처(메시지 우선순위, 캐시 전략,
  `MessageSource` 통합, 영속성, 스키마 관리, 관리 CRUD API 모델)는 이번
  버전 변경과 무관하게 그대로 유지된다.
