# 역할별 리소스 재배치 인계

- 날짜: 2026-09-18
- 대상: `C:/projects/kkdugi/kkdugi-admin`
- 결정: [ADR-0010](../adr/0010-role-based-resource-layout.md)

## 변경

| 기존 경로 (resources 기준) | 새 경로 |
|---|---|
| templates/admin/index.html | templates/layout/index.html |
| templates/admin/login.html | templates/auth/login.html |
| static/admin/admin.css | static/css/app.css |
| static/auth/login.css | static/css/login.css |
| static/auth/login-live.mjs, session.mjs | static/js/auth/login.mjs, session.mjs |
| static/admin/bootstrap.mjs | static/js/bootstrap.mjs |
| static/admin/api.mjs, api/http.mjs | static/js/api/index.mjs, http.mjs |
| static/admin/runtime/* | static/js/runtime/* |
| static/admin/domain.mjs | static/js/domain/batch.mjs |
| static/admin/i18n.mjs | static/js/i18n/messages.mjs |
| static/admin/MessageCellEditor.mjs | static/js/grid/MessageCellEditor.mjs |
| static/admin/demo.mjs | static/js/preview/demo.mjs |
| static/admin/App.vue, components/NavigationTree.vue, components/PageHost.vue | static/vue/shell/ |
| static/admin/Grid.vue, DialogHost.vue | static/vue/components/ |
| static/admin/components/BatchPage.vue | static/vue/pages/BatchPage.vue |

`templates/pragma/*.vue`와 `static/vendor`는 역할에 이미 부합하므로 유지한다. 옛 static/admin, static/auth, templates/admin 디렉터리는 이동 확인 후 정리했다. 공개 페이지 및 API URL은 유지한다.

## 함께 수정한 연결

- IndexController/LoginController의 반환 view 이름
- 로그인·셸 HTML의 CSS/module URL과 contextPath 처리
- JavaScript·Vue 상대 import
- Pragma의 `@vue/pages/BatchPage.vue`와 업무 페이지의 `@js/grid/MessageCellEditor.mjs`
- SFC 로더의 허용 경로 및 정적 컴파일 캐시 분류
- 로그인·Pragma Java 테스트 및 Node 테스트 import
- 현재 경로를 설명하는 Java 주석

별도로, 이미 진행 중이던 백엔드의 MyBatis XML 하위 폴더 이동과 application.yml 검색 경로가 불일치해 매퍼를 찾지 못했다. 기존 최상위 `mapper/postgres/*Mapper.xml`을 재귀 경로 `mapper/postgres/**/*Mapper.xml`로 바꾸어 현재 파일 구조를 읽도록 했다. 매퍼·SQL·인증 로직은 수정하지 않았다.

## 검증

- Maven `clean test`: 99 tests, failures/errors/skipped 0. 로그인 view·contextPath 자산 경로·실제 Pragma 권한 렌더링 검증 포함.
- `node --test src/test/js/*.test.mjs`: 12개 통과. 새 경로의 실제 Spring 렌더링 SFC 및 JS 에디터 의존성 컴파일 포함.
- 정적 import·CSS 폰트 URL 18개 존재 확인. 옛 소스 디렉터리 및 target/classes의 static/admin·templates/admin이 제거된 것을 확인.
- Headless Edge: 1440px/390px, 새 CSS/JS/Vue 경로에서 메뉴·Pragma·코드 Dialog·메시지 input/저장·미저장 전환 취소·권한별 편집·404 복구·모바일 고정 GNB 검수 통과. 업무 API는 테스트 응답을 사용했으며 실제 사용자 데이터는 수정하지 않음.
- 테스트용 HTTP 서버와 브라우저 종료. Spring 상주 서버는 시작하지 않음.

과거 계획 11의 구현/기능 범위는 유효하며 물리 파일 경로는 이 문서가 대체한다. 작업 전 소스 내용을 비교한 뒤 재배치했고, 별도 진행 중인 API·DB 모델 변경을 덮어쓰지 않았다.
