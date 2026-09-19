# ADR-0010: 프런트 리소스를 역할별로 배치

- 날짜: 2026-09-18
- 상태: 구현 적용
- 근거: 관리자 전용 프로젝트에서 모든 자산을 admin 아래에 두는 중복을 제거하라는 사용자 요청
- 관련: [Pragma ADR-0009](0009-pragma-menu-screen-architecture.md), [변경 인계](../plan/12-role-based-resource-layout.md)

## 결정

`kkdugi-admin/src/main/resources` 아래의 프런트 리소스를 기술/역할별로 나눈다.

```text
templates/
  layout/index.html         공통 HTML 셸
  auth/login.html           Vue 없는 로그인 HTML
  pragma/admcode.vue        서버가 권한을 반영하는 업무 진입점
  pragma/admmsge.vue
static/
  css/app.css               관리자 공통 디자인
  css/login.css             로그인 디자인
  js/bootstrap.mjs          Vue 앱 시작
  js/auth/                  로그인·토큰 처리
  js/api/                   API adapter 및 HTTP 처리
  js/runtime/               SFC 로더·내비게이션
  js/domain/                배치 데이터·검증 유틸리티
  js/grid/                  Grid 에디터
  js/i18n/                  메시지 키 유틸리티
  js/preview/               기존 데모 adapter 보관
  vue/shell/                App·NavigationTree·PageHost
  vue/components/           Grid·DialogHost
  vue/pages/BatchPage.vue   공통코드/메시지 업무 UI
  vendor/                   외부 라이브러리·폰트·아이콘
```

서버가 처리하는 Pragma 진입 템플릿은 `templates/pragma`에 유지한다. 정적 Vue 업무 페이지는 `vue/pages`, 제품 업무에 종속되지 않는 UI는 `vue/components`, 셸 수명주기 요소는 `vue/shell`에 둔다.

SFC 로더의 별칭은 `@vue/`와 `@js/`다. 각각 contextPath를 반영한 `/vue/`, `/js/`로 해석하며 기존 same-origin 및 허용 경로 검증을 유지한다. 기존 `@admin/` 별칭은 제거한다.

로그인 파일명은 `js/auth/login.mjs`로 정리한다. 공개 화면 URL `/admin`, `/login`, Pragma URL 및 REST API 경로는 폴더명이 아니라 서버 계약이므로 유지한다. Java의 `kkdugi.web.admin` 패키지와 MessageSource 키도 이번 물리 경로 재배치 대상이 아니다.

## 영향

- 컨트롤러 view 이름, Thymeleaf asset URL, 상대 import, Pragma import, 로더의 허용 경로 및 테스트 기대값을 함께 변경한다.
- 예전 경로의 파일을 중복 보관하지 않는다. 배포 산출물에 옛 리소스가 남지 않도록 clean 빌드한다.
- 디자인 프로젝트의 이전 퍼블리싱은 과거 디자인 참고 자산으로 유지한다. 실제 실행 프로젝트 경로는 이 ADR과 계획 12를 우선한다.
