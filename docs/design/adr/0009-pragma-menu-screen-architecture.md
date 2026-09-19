# ADR-0009: 세션 메뉴 기반 Pragma 화면 로딩

- 날짜: 2026-09-18
- 상태: 2026-09-18 공통 셸·세션 메뉴·공통코드/메시지 1차 구현 완료. [구현 인계](../plan/11-pragma-integration-handoff.md) 참조.
- 범위: kkdugi-admin 프런트 구조. 로그인은 plain HTML/CSS/vanilla JS 유지.
- 대체: ADR-0004의 정적 업무 화면/고정 registry, ADR-0006의 API 근거 및 단일 App 구성. ADR-0005의 통합 Vue Dialog와 ADR-0008의 Bearer 로그인은 유지.
- 상세 계획: [계획 10](../plan/10-pragma-structure-migration.md)

## 근거와 우선순위

사용자 지시에 따라 `C:/projects/kkdugi/docs/api/`만 현행 API 계약으로 사용한다. 폐기된 `api-define-admin.md`와 이를 근거로 만든 프런트 adapter는 신규 구현 근거가 아니다. 최신 문서 안의 폐기 문서 링크도 따라가지 않는다. 문서와 코드가 다르면 차이를 기록하고, 문서에 없는 endpoint를 추정하지 않는다.

`IndexController`는 `/admin` HTML 셸을, `PragmaController`는 `/pragma/{menuId}`의 Thymeleaf 렌더링된 Vue SFC 텍스트를 담당한다. 후자는 세션 메뉴의 program으로 `templates/pragma/{program}.vue`를 선택하고 authorities를 넣는다. 브라우저에 program/authority를 포함한 메뉴 목록을 내려주지 않는다.

## 결정

1. Vue app은 셸에 한 번만 마운트한다. 정적 App.vue는 내비게이션·GNB·PageHost·DialogHost·알림만 소유한다. 업무 상태와 API 호출은 개별 Pragma 화면이 소유한다.
2. 내비게이션은 `GET /api/v1.0/admin/session/menu` 응답을 사용한다. 메뉴 ID와 프로그램 코드를 분리한다. URL은 `/admin#menu=<encoded menuId>`로 통일하고 클라이언트에서 menuId→program 매핑을 만들지 않는다.
3. PageHost는 인증 헤더를 붙여 `GET /pragma/{menuId}`를 읽고 vue3-sfc-loader로 컴파일한다. JSON descriptor·별도 bootstrap API·HTML innerHTML 삽입은 추가하지 않는다.
4. 정적 공유 컴포넌트는 `static/admin/`에, Thymeleaf 처리가 필요한 업무 진입 SFC는 `templates/pragma/`에 둔다. 업무 SFC의 상대 import가 `/pragma/` 아래로 잘못 해석되지 않도록 논리 alias를 runtime에서 contextPath 포함 정적 URL로 해석한다.
5. Vue/moduleCache는 공통으로 유지하되 사용자별로 렌더링된 Pragma 소스와 컴파일 결과는 전환 간 영구 캐시하지 않는다. 같은 전환 요청 내 중복만 합친다. 로그아웃·언어 변경·인증 오류에서 진행 요청과 화면을 폐기한다. 서버 응답에 `Cache-Control: no-store` 추가를 제안한다.
6. 권한 키는 문자열 `10` 읽기, `20` 쓰기, `30` 삭제, `40` 실행이다. 서버의 th:if와 화면의 편집 가능 여부에 같은 권한 값을 사용한다. 정적 공유 컴포넌트에서 Thymeleaf 처리를 기대하지 않는다. UI 제어는 API 인가를 대체하지 않는다.
7. API가 문서화된 공통코드·메시지부터 이관한다. 메뉴·권한·사용자 CRUD는 디자인 자산을 보존하되 실 API 연결은 새 계약을 기다린다. 세션 메뉴 조회를 메뉴관리 CRUD로 사용하지 않는다.
8. 모바일 내비게이션·고정 GNB·화면 타이틀 아래 변경사항 바·통합 Vue Dialog·로케일 탭·메시지 input 에디터·고정 textarea 디자인은 유지한다.

## 선택 이유와 영향

기존 단일 App.vue를 그대로 Pragma로 제공하면 모든 메뉴에서 모든 업무 분기와 상태를 함께 로딩하며 서버별 권한 렌더링 경계가 모호해진다. 화면 단위 진입 SFC로 나누고 Grid/폼/배치 유틸리티를 공유하면 변경 범위와 해제 책임이 명확해진다.

셸 전체를 매번 재마운트하면 GNB·모바일 포커스·공통 Dialog가 함께 소멸한다. 셸은 유지하고 활성 화면만 해제한다. 다중 업무 탭/KeepAlive는 이번 범위에 추가하지 않는다.

## 구현 전 해결할 계약

현행 응답만으로 프로그램 없는 leaf와 실행 가능한 메뉴를 구분할 수 없다. 우선 자식이 있는 노드는 펼침, leaf는 열기 시도 후 404 안내한다. 완전한 표현을 위해 program 공개 대신 `openable` boolean 추가를 제안한다. 부모이면서 실행 가능한 메뉴 지원도 이 필드 확정 후 적용한다.

일반 사용자 메뉴의 조상 누락, READ 비트 검증, 세션 메뉴의 한국어 고정, Pragma Locale/MessageSource 연결, API 인증 강제는 계획 10의 선행 조정 항목이다. 설계 채택은 이 기능들이 구현되었다는 의미가 아니다.
