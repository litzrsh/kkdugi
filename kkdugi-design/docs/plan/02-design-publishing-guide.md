# 디자인 및 후속 퍼블리싱 기준

> 2026-09-16: API 계약과 실제 퍼블리싱 구조는 [ADR-0006](../adr/0006-admin-api-contract-and-publishing.md) 및 계획 06의 현재 상태를 우선합니다. 아래 내용은 최초 설계 기록입니다.

- 상태: 디자인 제안. 실제 화면·CSS·템플릿은 이번에 작성하지 않음.
- 모바일은 필수 설계 범위다. 아래 데스크톱 기본값의 모바일 예외는 [모바일 조작 기준](05-mobile-interaction-specification.md)을 우선한다.

## 1. 디자인 방향과 토큰

장시간 목록을 읽고 편집하는 업무 도구다. 넓은 흰색 면을 회백색 배경으로 분리하고 그림자는 팝업·떠 있는 영역에 제한한다. ‘눈이 아프지 않음’은 보장 가능한 의학적 결과가 아니라 낮은 시각적 자극과 읽기 쉬운 대비를 목표로 해석한다.

| 토큰 | 제안값 | 적용 |
|---|---|---|
| canvas | #F5F7F8 | 전체 배경 |
| surface | #FFFFFF | 검색·그리드·팝업 |
| surface-muted | #EEF2F4 | 헤더·읽기 전용 영역 |
| text-primary | #243238 | 본문 |
| text-secondary | #52636D | 보조 설명 |
| border | #DCE3E7 | 장식 구분선 |
| control-border | #7B8D97 | 입력 컨트롤 식별 경계 |
| primary | #176B63 | 주요 버튼·선택 강조 |
| primary-soft | #E7F2EF | 현재 메뉴·선택행 배경 |
| danger | #B42332 | 삭제·오류 |
| warning | #805B12 | 주의·미저장 |
| focus | #2563A6 | 2px 포커스 링 + 2px 간격 |

위 값은 시작값이다. 실제 조합에서 일반 텍스트 4.5:1, 큰 텍스트·필수 비텍스트 요소 3:1을 수용 기준으로 측정한다. 연한 border를 입력 식별의 유일한 수단으로 쓰지 않는다. 상태는 색 + 아이콘/문구로 구분한다.

- Pretendard 400/500/600, 본문 14px/1.5, 설명 13px/1.5, 제목 24px/1.35, 섹션 16px/1.5. 12px 이하는 지양한다. 폰트 로딩 실패 시 system-ui/sans-serif fallback.
- 간격: 4/8/12/16/24/32px. 버튼·필드 높이 36px, 행 44px, 헤더 40px. 8px 반경, 팝업 12px. 그림자 제안: 0 8px 24px rgba(36,50,56,.10).
- line-awesome 아이콘 18~20px. 아이콘 전용 버튼에는 접근 가능한 이름과 tooltip 제공. 삭제·저장 등 중요한 작업은 텍스트를 병기한다.
- 애니메이션은 120~180ms 정도의 상태 전환에 한정하고 reduced-motion을 존중한다. 그리드 행 이동의 불필요한 애니메이션은 줄인다.

## 2. 레이아웃

- 기준 뷰포트 1440×900, 확인 크기 1280×720·1920×1080·768px·390px.
- sidebar 232px, topbar 64px, 본문 padding 24px. 콘텐츠 영역은 가용 폭 사용. 현재 메뉴는 색·배경·굵기로 표시한다.
- 검색 영역은 3~4열까지, 폭이 줄면 2열/1열로 줄바꿈한다. 필드 레이블은 위에 유지한다.
- 1024px 미만은 sidebar를 메뉴 버튼으로 여는 drawer로 전환한다. 작은 화면에서도 데이터 열을 제거하지 않고 그리드 내부 가로 스크롤을 제공한다.
- grid는 화면에서 남는 높이를 사용하되 최소 높이 320px 제안. 페이지 전체와 그리드의 중첩 세로 스크롤을 최소화한다. 고정 하단 저장 바가 마지막 행·페이지 버튼을 가리지 않게 한다.
- 메시지 코드·선택 영역 고정, 언어 열 240px 이상 제안. 일반 열은 최소 폭을 보장하고 긴 내용은 전체 보기 제공. 툴팁만으로 정보를 전달하지 않는다.
- 폼 팝업 폭 560px, 다국어/매핑 팝업 880px, max-width calc(100vw - 32px), max-height calc(100dvh - 32px). 본문만 스크롤하고 제목·동작은 유지한다.

## 3. 공통 컴포넌트

AppShell, PageHeading, SearchPanel, GridToolbar, StatusBadge, BatchSaveBar, DialogHost, DialogShell, MessageBody, EditBody, MappingBody, InlineError, Toast, EmptyState를 공통화할 계획이다. alert/confirm/편집/매핑은 같은 Vue DialogHost·DialogShell을 사용한다. 별도 Popup 구현은 만들지 않는다. 이름은 설계상 역할명이며 현재 구현물은 없다.

검색·저장은 primary, 추가·취소는 neutral, 삭제 확인은 danger. 한 영역에서 주요 동작 하나를 강조한다. disabled 버튼은 이유를 주변 설명으로 제공한다. Toast는 결과 보조 안내이며 중요한 오류는 화면에도 남긴다.

## 4. Thymeleaf + Vue 경계

- Kluvo 분석으로 구체화한 배치 구조: `templates/admin/index.html`, `templates/admin/fragments/`, `static/admin/pages/{메뉴코드}.vue`, `static/admin/js/*.mjs`, `static/admin/components/dialogs/`, `static/admin/css/`, `static/vendor/`. 상세 계약은 [연동 설계](04-kluvo-thymeleaf-vue-analysis.md) 참조. 정적 SFC에 Thymeleaf 문법을 넣지 않는다.
- Thymeleaf: shell, 초기 locale, 메시지 사전, 사용자에게 허용된 동작, 서버가 제공할 경우 CSRF 정보.
- Vue: 페이지별 검색 조건, 팝업 폼, 선택·변경 집합·오류. Grid API는 반응형으로 깊게 감싸지 않는다.
- AG Grid: 셀 렌더링·편집·선택·스크롤. 안정된 행 ID를 사용하고 Vue와 Grid의 데이터 소유권을 문서화한다. 라이프사이클 종료 시 grid와 이벤트를 해제한다.
- Vanilla JS: API 호출 어댑터·포맷·메시지 접근 등 공통 유틸리티. Vue 컴포넌트의 DOM을 직접 재작성하지 않는다.
- Bulma를 먼저, 프로젝트 토큰·컴포넌트·페이지 스타일을 뒤에 적용한다. AG Grid 테마 API/기존 테마 CSS 중 제공 버전에 맞는 방식 하나를 선택한다.
- 메시지·사용자 값은 text로 렌더링한다. HTML 삽입으로 팝업·셀을 만들지 않는다. JSON 직렬화는 Thymeleaf가 지원하는 안전한 방식으로 적용한다.
- 화면과 팝업은 단일 Vue/SFC loader를 공유하고 PageHost가 mount/dispose를 관리한다. 공통 서비스는 inject('kkdugi')로 제공한다. 초기 SFC는 공통 외부 CSS만 사용하고 `<style>` 자동 주입은 사용하지 않는다.

## 5. 다국어 체크리스트

- Spring message를 문구의 단일 원본으로 사용한다. key 예: `admin.btn.save`, `admin.lbl.code_path`, `admin.err.required`.
- 등록 언어 목록·정렬순서·기본 언어의 제공 주체는 서버 계약으로 확보한다. DB의 ko_KR과 HTML lang의 ko-KR 표기 변환을 명시한다.
- 영어 1.5~2배 길이, 긴 오류, 여러 줄 메시지에서 버튼·팝업이 잘리지 않게 한다. 레이블을 이미지로 만들지 않는다.
- 그리드 헤더·페이지 문구·편집기·aria label도 번역한다. 로케일 변경에는 미저장 보호를 적용한다.
- 날짜·숫자는 locale에 맞게 표시하되 서버 저장 값·식별 코드와 분리한다. 시간대 정책은 서버와 확정한다.
- 번역 누락은 서버 fallback을 따르고 QA에서 누락 키를 수집한다. 개발 중 한국어 하드코딩을 그대로 배포하지 않는다.

## 6. 검수 기준

키보드만으로 검색→셀 편집→팝업→저장→오류 수정 가능, 200% 확대에서 핵심 작업 가능, 상태의 색상 비의존, 팝업 초점 복귀, 스크린리더용 레이블, 텍스트 대비, 가로 스크롤, 긴 다국어 문구, 폰트 실패 fallback을 후속 브라우저 검수에서 확인한다.
