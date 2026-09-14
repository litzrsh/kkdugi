# ADR-0005: 영속성 프레임워크 - MyBatis

- 상태: Accepted
- 날짜: 2026-09-15

## 컨텍스트

`KKDUGI_I18N_MSG` 및 향후 공통코드/세션 등 관리자 시스템 테이블에 대한
JDBC 접근 방식을 정해야 한다. 이전(삭제된) 구현도 MyBatis 기반이었다.

## 결정

**MyBatis(`mybatis-spring-boot-starter`)를 사용한다.** 매퍼는 인터페이스 +
XML(`resources/mapper/*.xml`) 조합으로 작성한다.

## 근거

- 이전 구현과의 연속성 - 팀이 이미 MyBatis 컨벤션(매퍼 XML, 동적 SQL)에
  익숙하다.
- `KKDUGI_CODE_BASE`처럼 계층형/동적 조건 조회(`CODE_PATH` 기반 조회 등)가
  필요한 테이블이 이미 ERD에 존재하므로, JPA의 객체-관계 매핑보다 SQL을
  직접 제어하는 MyBatis가 적합하다.
- Spring Data JPA는 이번 설계에서 검토하지 않았다(팀 컨벤션 및 이전 구현과의
  일관성이 우선 기준이었음).

## 결과

- `I18nMessageMapper`는 인터페이스로 선언하고, 실제 SQL은
  `resources/mapper/I18nMessageMapper.xml`에 작성한다.
- 엔티티 대신 단순 도메인 레코드(`I18nMessage`)를 매퍼 반환 타입으로 사용한다.
