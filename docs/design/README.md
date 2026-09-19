# kkdugi 디자인·프런트엔드 문서

2026-09-19: 기존 kkdugi-design 문서를 최상위 docs/design으로 통합했습니다. 디자인 ADR 번호는 기존 번호를 유지하며 백엔드 ADR과 별도로 관리합니다.

## 현재 기준

- [API 요청 메뉴 컨텍스트 적용](plan/17-api-menu-request-context.md)

- [메뉴관리 완료·검증](plan/16-menu-management-completion.md)

- [메뉴·코드 리팩토링과 관리 화면 연결](plan/15-menu-code-refactor-integration.md)

- [인증·세션 연동과 중복 로그인 확인 팝업](plan/14-session-auth-confirm-and-docs.md)
- [로그인 확인 팝업과 문서 통합 결정](adr/0011-session-cookie-login-confirm.md)
- [전체 API 계약](../api/README.md) · [인증](../api/auth.md) · [세션·Pragma](../api/session.md)
- [화면 기획](plan/01-screen-specification.md) · [디자인·퍼블리싱 기준](plan/02-design-publishing-guide.md) · [모바일 기준](plan/05-mobile-interaction-specification.md)
- [메뉴관리·현행 CRUD 연동](plan/13-current-api-sync.md)
- [역할별 파일 구조](plan/12-role-based-resource-layout.md)

실제 구현 위치는 kkdugi-admin입니다. 로그인은 HTML/CSS/vanilla JavaScript, 관리 화면은 Thymeleaf와 Vue SFC를 사용합니다. 기본 Pragma 화면은 templates/pragma/admin/{code,message,menu}.vue이며 파일 경로를 프로그램 코드로 사용합니다.

## 문서 작성 위치

| 내용 | 위치 |
|---|---|
| 디자인·화면 기획·퍼블리싱·프런트엔드 연동 계획 | design/plan |
| 디자인·프런트엔드 결정 기록 | design/adr |
| 실제 API 계약 | ../api |
| 백엔드 구조 결정·개발 규칙 | ../adr, ../conventions |

과거 문서는 설계 이력으로 유지합니다. 과거 JSON 로그인, sessionStorage 토큰, 독립 중복 로그인 화면, 폐기된 API 정의서는 현재 구현 계약이 아닙니다.

## 전체 문서

- [0001-admin-ui-foundation](adr/0001-admin-ui-foundation.md)
- [0002-grid-batch-and-hierarchy](adr/0002-grid-batch-and-hierarchy.md)
- [0003-message-pivot-i18n](adr/0003-message-pivot-i18n.md)
- [0004-thymeleaf-vue-sfc-runtime](adr/0004-thymeleaf-vue-sfc-runtime.md)
- [0005-unified-vue-dialog-responsive](adr/0005-unified-vue-dialog-responsive.md)
- [0006-admin-api-contract-and-publishing](adr/0006-admin-api-contract-and-publishing.md)
- [0007-login-challenge-flow](adr/0007-login-challenge-flow.md)
- [0008-existing-json-login-integration](adr/0008-existing-json-login-integration.md)
- [0009-pragma-menu-screen-architecture](adr/0009-pragma-menu-screen-architecture.md)
- [0010-role-based-resource-layout](adr/0010-role-based-resource-layout.md)
- [01-screen-specification](plan/01-screen-specification.md)
- [02-design-publishing-guide](plan/02-design-publishing-guide.md)
- [03-delivery-plan](plan/03-delivery-plan.md)
- [04-kluvo-thymeleaf-vue-analysis](plan/04-kluvo-thymeleaf-vue-analysis.md)
- [05-mobile-interaction-specification](plan/05-mobile-interaction-specification.md)
- [06-publishing-handoff](plan/06-publishing-handoff.md)
- [07-login-publishing](plan/07-login-publishing.md)
- [08-admin-login-analysis](plan/08-admin-login-analysis.md)
- [09-login-backend-integration](plan/09-login-backend-integration.md)
- [10-pragma-structure-migration](plan/10-pragma-structure-migration.md)
- [11-pragma-integration-handoff](plan/11-pragma-integration-handoff.md)
- [12-role-based-resource-layout](plan/12-role-based-resource-layout.md)
- [13-current-api-sync](plan/13-current-api-sync.md)
