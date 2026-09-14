# ADR-0001: 기술 스택 - Java + Spring Boot + Maven

- 상태: Superseded by [ADR-0008](0008-backend-stack-revision-java17-springboot4.md) (버전만 변경, Java/Spring Boot/Maven 조합 자체는 유지)
- 날짜: 2026-09-15

## 컨텍스트

저장소가 2026-09-15에 리셋되어 애플리케이션 코드가 없는 상태에서 새 프로젝트를
시작한다. 이전(삭제된) 구현은 Java/Spring Boot + Maven 멀티모듈이었다. 사용자가
지시한 패키지 구조(`kkdugi.core`, `kkdugi.api.admin`, `kkdugi.app.admin`)는
JVM 계열 언어의 패키지 개념을 전제로 한다.

## 결정

- 언어: Java 21
- 프레임워크: Spring Boot 3.3.x
- 빌드 도구: Maven, 단일 모듈(이전처럼 멀티모듈로 분리하지 않음)
- groupId `kkdugi`, artifactId `kkdugi-admin`, 루트 패키지 `kkdugi`

Kotlin, Gradle 등 대안도 검토했으나 이전 구현과의 연속성 및 사용자 선택에 따라
Java + Maven으로 확정했다.

## 결과

- Maven 표준 디렉터리 구조(`src/main/java`, `src/main/resources`,
  `src/test/java`)를 따른다.
- 멀티모듈이 아니므로 `core` / `api.admin` / `app.admin`은 별도 Maven 모듈이
  아니라 단일 모듈 내부의 패키지 경계로만 존재한다. 모듈 간 강제 경계(순환
  참조 금지 등)는 빌드 도구가 아닌 코드 리뷰/컨벤션으로 관리해야 한다.
