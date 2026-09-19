# kkdugi admin 기획·디자인 문서

> 2026-09-18 현행 기준: API는 `C:/projects/kkdugi/docs/api/`를 참조합니다. 기존 `api-define-admin.md`는 폐기되었습니다. 이 README 아래의 초기 상태 및 과거 ADR의 계약 설명은 당시 기록입니다. 실제 로그인 연결은 [계획 09](plan/09-login-backend-integration.md), 최신 Pragma 구조 설계는 [계획 10](plan/10-pragma-structure-migration.md)과 [ADR-0009](adr/0009-pragma-menu-screen-architecture.md)를 우선합니다. 현재 기능 범위와 API 경로는 [최신 계약 재동기화](plan/13-current-api-sync.md), 최신 파일 구조는 [역할별 재배치](plan/12-role-based-resource-layout.md)와 [ADR-0010](adr/0010-role-based-resource-layout.md)을 참조합니다. 공통 셸·공통코드·메시지 화면을 kkdugi-admin에 이관했습니다.


- 작성일: 2026-09-15
- 현재 작업: 2026-09-16 사용자 요청에 따라 디자인·퍼블리싱 소스를 작성했다. 실제 백엔드 API 구현·외부 배포는 진행하지 않았다. [현재 인계 상태](plan/06-publishing-handoff.md) 참조.
- 제품: AI agent를 통합하고 프로젝트를 생성·진행하며 kkdugi를 유지보수하는 시스템.
- 이번 설계 범위: 시스템관리의 공통코드·메시지·메뉴·권한·사용자 5개 화면 및 공통 UI.
- AI agent 연결, 프로젝트 실행, 유지보수 실행 화면은 제품 맥락이며 이번 메뉴에 임의 추가하지 않는다.

## 문서 안내

| 문서 | 내용 |
|---|---|
| [화면 기획](plan/01-screen-specification.md) | 정보 구조, 화면별 필드·행동·검증·예외 |
| [디자인·퍼블리싱 기준](plan/02-design-publishing-guide.md) | 토큰, 레이아웃, 접근성, Thymeleaf/Vue, 다국어 |
| [작업 계획](plan/03-delivery-plan.md) | 후속 구현 순서, 완료 기준, 미확정 항목 |
| [ADR-0001](adr/0001-admin-ui-foundation.md) | 기술·디자인 기반 결정 |
| [ADR-0002](adr/0002-grid-batch-and-hierarchy.md) | 배치 편집, 계층 표현, 기본 메뉴 보호 |
| [ADR-0003](adr/0003-message-pivot-i18n.md) | 메시지 pivot 및 Spring message 연동 |
| [Kluvo 분석 및 연동 설계](plan/04-kluvo-thymeleaf-vue-analysis.md) | 실제 소스 근거, 공통 런타임·초기화·해제·팝업 계약 |
| [ADR-0004](adr/0004-thymeleaf-vue-sfc-runtime.md) | Thymeleaf shell + Vue SFC 통합 결정 |
| [ADR-0005](adr/0005-unified-vue-dialog-responsive.md) | Popup·Dialog의 전체 Vue 통합과 수명주기 |
| [모바일 조작 기준](plan/05-mobile-interaction-specification.md) | 화면별 터치 편집·반응형 dialog·키보드 대응 |
| [ADR-0006](adr/0006-admin-api-contract-and-publishing.md) | 최신 API 계약 적용 및 실제 퍼블리싱 구조 |
| [퍼블리싱 인계](plan/06-publishing-handoff.md) | 산출물·실행·완료 및 미완료 검증 |

## 상태와 근거

사용자 지정 기술·메뉴는 확정 요구사항이다. 9월 15일 문서화 범위는 9월 16일 디자인·퍼블리싱으로 확장됐다. 최신 API 관련 결정은 ADR-0006을 우선한다. 치수·세부 정책은 실기 검수에서 조정할 수 있다. ADR의 ‘설계 채택’은 인수 검증 완료를 의미하지 않는다.

기존 `../../docs/adr/0007-admin-crud-single-endpoint-batch-save.md`, `../../docs/adr/0009-frontend-direction-thymeleaf-dependency-only.md`, `../../docs/i18n-system-design.md` 및 현재 MessageAdminController, MessageCode 소스를 확인했다. 과거 문서와 현재 코드가 다를 때 실제 연동 계약은 코드를 재확인한다. 기존 문서는 수정하지 않았다. 이 폴더의 ADR 번호는 디자인 문서 전용이다.

`C:\projects\kkdugi\libraries`에서 ag-grid.min.js, line-awesome-1.3.0.zip, Pretendard-1.3.9.zip을 확인했다. Vue와 Bulma 파일은 확인되지 않았다. AG Grid 버전·라이선스·배포물 범위와 압축 파일 내부는 후속 작업에서 검증한다. 이번 작업에서는 라이브러리를 다운로드하거나 설치하지 않았다.

추가 분석에서 Kluvo admin의 로컬 Vue 3.4.21 및 vue3-sfc-loader 0.9.5 자산을 발견했다. kkdugi로 복사하거나 채택 버전을 확정하지 않았다. Kluvo의 화면 로더는 CDN ESM, 팝업 로더는 로컬 global을 사용하므로 kkdugi에서는 단일 런타임으로 통합하는 설계를 추가했다. Popup·Dialog는 shell까지 Vue로 통합하고 모바일도 필수 설계 범위로 확장했다. 초기 문서는 11개였으며 현재 ADR-0006·계획 06을 포함해 총 13개다. 실제 서버·브라우저 인수 검증은 남아 있다.
