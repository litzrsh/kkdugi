# 관리자 배치 편집 화면 분리

2026-09-21 기준 공통코드·메시지·메뉴 화면은 각각 독립된 Vue 페이지로 구현한다.
기존 문서의 `BatchPage.vue` 공유 구조는 이 변경으로 대체한다.

| Pragma 진입점 | 업무 페이지 | 담당 기능 |
| --- | --- | --- |
| `templates/pragma/admin/code.vue` | `static/vue/pages/CodePage.vue` | 공통코드 계층 탐색, 인라인 편집, 번역·추가정보 팝업 |
| `templates/pragma/admin/message.vue` | `static/vue/pages/MessagePage.vue` | 언어별 메시지 인라인 편집, 코드 중복 검사 |
| `templates/pragma/admin/menu.vue` | `static/vue/pages/MenuPage.vue` | 메뉴 트리 검색, 자식 추가, 보호 메뉴 처리, 계층 삭제·복원 |

각 페이지가 검색 조건, 컬럼, 편집 상태, 조회·저장 및 이탈 방지를 소유한다.
`screen` 속성에 따른 페이지 분기와 `BatchPage.vue`는 제거했다.
Grid, 다이얼로그, API 클라이언트, 배치 데이터 유틸리티 및 CSS는 계속 공유한다.
기존 메뉴 URL, API 계약, 권한 및 일괄 저장 방식은 유지한다.

검증은 `PragmaControllerTest`의 실제 서버 렌더링 및 권한 확인,
`pragma-sfc.test.mjs`의 SFC 컴파일, `independent-admin-pages.test.mjs`의
Vue 페이지 실행 테스트로 수행한다. 실행 테스트는 공통코드 하위 탐색·저장,
메시지 언어별 컬럼·중복 검사, 메뉴 검색·자식 추가·삭제 복원 및 화면별 이탈 방지를 확인한다.
