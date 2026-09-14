# Architecture Decision Records

kkdugi-admin 프로젝트의 아키텍처 결정을 기록한다. 각 ADR은 번호순으로
쌓이며, 이후 결정이 이전 결정을 대체하면 기존 ADR의 상태를 `Superseded`로
바꾸고 새 ADR을 추가한다(기존 ADR은 삭제하지 않는다).

| 번호 | 제목 | 상태 |
|---|---|---|
| [0001](0001-tech-stack-java-spring-maven.md) | 기술 스택: Java + Spring Boot + Maven | Superseded by 0008 |
| [0002](0002-i18n-message-priority-db-first.md) | 다국어 메시지 우선순위: DB 우선, properties는 fallback | Accepted |
| [0003](0003-i18n-cache-strategy-in-memory-evict-on-save.md) | 다국어 캐시 전략: 인메모리, 저장 시점 즉시 갱신 | Accepted |
| [0004](0004-i18n-messagesource-spring-integration.md) | 다국어 API: Spring `MessageSource` 인터페이스 구현 | Accepted |
| [0005](0005-persistence-mybatis.md) | 영속성 프레임워크: MyBatis | Accepted |
| [0006](0006-schema-migration-flyway.md) | 스키마 관리: Flyway 마이그레이션 | Accepted |
| [0007](0007-admin-crud-single-endpoint-batch-save.md) | 관리 CRUD API: 단일 엔드포인트 배치 저장, flat 행 모델, 전체 트랜잭션 | Accepted |
| [0008](0008-backend-stack-revision-java17-springboot4.md) | 백엔드 스택 버전 확정: Java 17 + Spring Boot 4.0.4 | Accepted |
| [0009](0009-frontend-direction-thymeleaf-dependency-only.md) | 프론트엔드 방향: Thymeleaf 의존성만 지금 추가, 나머지 TODO | Accepted |
