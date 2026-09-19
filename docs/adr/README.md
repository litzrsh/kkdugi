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
| [0007](0007-admin-crud-single-endpoint-batch-save.md) | 관리 CRUD API: 단일 엔드포인트 배치 저장, flat 행 모델, 전체 트랜잭션 | Superseded by 0011 (전체 트랜잭션 원칙만 계승) |
| [0008](0008-backend-stack-revision-java17-springboot4.md) | 백엔드 스택 버전 확정: Java 17 + Spring Boot 4.0.4 | Accepted |
| [0009](0009-frontend-direction-thymeleaf-dependency-only.md) | 프론트엔드 방향: Thymeleaf 의존성만 지금 추가, 나머지 TODO | Accepted |
| [0010](0010-common-base-model-adoption.md) | 공통 베이스 모델 도입 (`core.models`/`core.util`) 및 i18n 필드 리네이밍 | Superseded by 0011 (BaseModel/BaseParams 상속 메커니즘에 한함) |
| [0011](0011-api-define-admin-contract-and-record-models.md) | i18n API를 api-define-admin.md 계약으로 재구현, record 기반 모델과 계층별 패키지 규약 도입 | Superseded by 0014 (BaseModel/BaseParams 상속 전환 절에 한함) |
| [0012](0012-common-code-system.md) | 공통코드 시스템: 채번 방식(core.serial), 계층 저장/삭제, 검색 범위 확정 | Accepted |
| [0013](0013-code-enums-mybatis-integration.md) | 코드성 enum(`CodeEnums`)과 MyBatis 자동 변환(`default-enum-type-handler`) | Accepted |
| [0014](0014-revert-to-base-model-inheritance.md) | BaseModel/BaseParams 상속 기반으로 재전환 (ADR-0011 되돌림) | Accepted |
| [0015](0015-menu-management-system.md) | 메뉴 관리 시스템: 전체 트리 응답(Page<T> 미사용), parentId 불변, 409 중복 없음 | Accepted |
| [0016](0016-app-and-admin-feature-split.md) | 사용자용(app.<기능>)과 관리자용(app.admin.<기능>) 기능 분리, 세션 메뉴 /api/v1.0/menu 이전 | Accepted |
| [0017](0017-menu-context-security-aspect.md) | 요청 메뉴 기반 Aspect 인가: 역할·RBAC·프로그램·배치 작업 검증 | Accepted |
| [0018](0018-authority-management-system.md) | 권한 관리 시스템: 단일 기능 패키지, 전체 교체 저장, SYS_ADMIN 예약 role 코드, RBAC 숫자 코드 키 | Accepted |
