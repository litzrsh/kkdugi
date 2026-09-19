# ADR-0004: 공통 Thymeleaf shell과 단일 Vue SFC 런타임

> 2026-09-16: API 계약과 실제 퍼블리싱 구조는 [ADR-0006](0006-admin-api-contract-and-publishing.md) 및 계획 06의 현재 상태를 우선합니다. 아래 내용은 최초 설계 기록입니다.

- 날짜: 2026-09-15
- 상태: 설계 채택, 런타임 호환성 검증 후 구현
- 확장: ADR-0001의 페이지 단위 Vue 통합 방식과 ADR-0003의 메시지 주입 경계
- 근거: [Kluvo 소스 분석 및 상세 계약](../plan/04-kluvo-thymeleaf-vue-analysis.md)
- 후속: [ADR-0005](0005-unified-vue-dialog-responsive.md)가 Popup·Dialog를 Vue shell까지 통합하며 모바일 동작을 구체화한다.

## 배경

Kluvo web는 Thymeleaf fragment에 초기 데이터를 주입하고, admin은 정적 Vue SFC를 제공한다. 브라우저가 SFC를 컴파일·마운트하므로 Vue 번들러 없이 Spring 정적 자산 구조에 화면을 추가할 수 있다. kkdugi에는 같은 분리 원칙을 적용하되 로더·해제·다국어 계약을 공통화해야 한다.

## 결정

1. Thymeleaf 공통 shell + 정적 `.vue` 화면 + 로컬 vue3-sfc-loader를 설계 기본안으로 채택한다. 정적 SFC에 Thymeleaf 문법을 넣지 않는다. SFC loader 추가는 지원 의존성으로 기록하고 버전 확정은 호환성 검증 후 진행한다.
2. 화면과 팝업은 단일 Vue 런타임·공유 moduleCache·공유 로딩 Promise를 사용한다. 초기 구현 후보는 검증된 local global 배포물이며 runtime.mjs가 접근을 독점한다. 화면별 window.Vue/CDN import 혼용은 금지한다.
3. 정적 shell bootstrap이 JSON descriptor를 받아 PageHost.mount를 호출한다. Kluvo의 HTML 삽입→inline script 재실행은 기본 흐름으로 복제하지 않는다. Thymeleaf의 역할은 서버 shell·초기 설정 직렬화에 남는다.
4. 서버 화면 registry는 고정 소문자 5개 코드를 명시한다. 초기 데이터 handler는 일반 Spring bean으로 관리하고 중복 ID는 오류 처리한다. Kluvo uppercase 이름으로 기존 메뉴 코드를 바꾸지 않는다.
5. initData는 props, API·i18n·Dialog·Grid·변경 보호는 inject('kkdugi')로 제공한다. 사용자별 초기 값과 메시지는 컴파일 캐시와 분리한다.
6. PageHost는 명시적 dispose를 소유하고 화면 전환·재로드 전에 app.unmount를 수행한다. 중복 종료, 늦은 응답, 미저장 값, 팝업 owner 종료를 계약으로 관리한다.
7. 첫 범위는 단일 활성 화면이다. 다중 탭은 별도 요구 이후 확장한다. Bulma·Pretendard·line-awesome·라이트 토큰과 기본 메뉴 불변 정책을 유지한다.
8. UI 사전은 Spring MessageSource를 통해 서버가 해석한다. Kluvo의 메시지 키·fallback 체계를 복사하지 않고 kkdugi의 세 구간 소문자 규칙과 DB/properties 정책을 적용한다.

## 대안

| 방식 | 판단 |
|---|---|
| Thymeleaf HTML 안에 Vue 옵션 코드 | 의존성은 단순하지만 화면·팝업 SFC 재사용성이 낮아 기본안에서 제외 |
| Kluvo fragment/script 실행 그대로 이식 | 기존 흐름과 가깝지만 host와 mount 완료/해제 연결을 다시 설계해야 하므로 JSON 명시 호출 선택 |
| 빌드 단계 SFC 사전 컴파일 | 운영 CSP·성능 이슈의 대안; 필요 시 PageHost 계약을 유지하고 loader만 교체 |

## 영향·검증 문턱

기존 화면별 HTML 계획은 shell 1개와 SFC 5개로 구체화한다. JSON bootstrap endpoint, 공통 런타임, lifecycle 계약은 신규 후속 작업이다. 현재 kkdugi에 존재한다고 간주하지 않는다. 사용자 요청은 계속 문서화 범위다.

로컬 자산·CSP·script setup·`.mjs` import·Grid/팝업·Spring Boot 4 템플릿 통합 검증을 통과해야 본 퍼블리싱에 착수한다. 실패 시 원인에 맞춰 사전 컴파일 등 대안을 결정하고 이 ADR을 갱신한다.
