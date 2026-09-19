# 사용자관리 화면 구현

날짜: 2026-09-20 · 상태: 구현 및 검증 완료

## 구현 범위

- 로그인 ID·상태·이름 검색, 초기화, 20/50/100/200개 페이징.
- 이름·원형 프로필, 이메일, 사용자 상태, 권한 버튼, 비밀번호 상태, 최근 로그인, 작업 컬럼.
- 등록/수정: 로그인 ID, 이름, 이메일, 상태, 프로필 이미지 URL/미리보기, 설명. 기존 로그인 ID는 수정 불가. 최근 로그인·비밀번호 변경 시각은 읽기 전용.
- 행별 비밀번호 초기화 및 영구 삭제 확인 팝업.
- 선택한 사용자 상태 일괄 변경. 재조회 후 선택은 초기화한다.
- 사용자별 권한 목록, 후보 검색, 적용 시작/종료일 편집 및 변경분 배치 저장.
- READ/WRTE/DELT에 따른 조회 전용, 추가/기간 변경, 연결 해제 UI 구분.
- 한국어/영어 메시지, 모바일 팝업, 고정 높이 textarea, 프로필 로드 실패 대체 표시.

## 구현 위치

- kkdugi-admin/src/main/resources/templates/pragma/admin/user.vue
- kkdugi-admin/src/main/resources/static/vue/pages/UserPage.vue
- kkdugi-admin/src/main/resources/static/js/domain/user.mjs
- 공통 API 클라이언트의 user 하위 메서드 및 DialogHost의 독립 잠금 옵션
- AdminUserController/Service/Mapper의 authority-candidates 조회

## 검증

- Maven 전체 테스트: 273개 통과, 실패·오류·생략 0.
- JavaScript 전체 테스트: 48개 통과. 실제 Spring 렌더링 Pragma의 Vue 컴파일 포함.
- 후보 API: 활성/미연결 필터, 대소문자·리터럴 검색, 없는 사용자 404, 사용자 메뉴 READ 및 SYS_ADMIN 인가.
- 브라우저 HTTP fixture: 실제 정적 자산과 Spring 렌더링 결과로 CRUD, 검색/페이지 크기, 409 오류 후 입력 보존·재시도, 원형 프로필, 비밀번호 초기화, 상태 변경, 권한 기간/배치, 읽기·쓰기·삭제 권한별 UI, 모바일 폭과 요청 메뉴 헤더를 검증했다.
- 데스크톱 목록·등록·권한 팝업, 모바일 등록 팝업 스크린샷을 확인했다.
- 브라우저 검증은 인메모리 API fixture를 사용했다. DB 계약·인가는 Spring 통합 테스트로 확인했고 실제 사용자의 권한을 브라우저에서 변경하지 않았다.

## 현재 백엔드 제약

임시 비밀번호 자동 발송과 사용자 상태에 따른 로그인 제한은 미구현이다. 화면은 이 동작을 안내하며 임의의 비밀번호 조회/전달 기능을 추가하지 않는다.
자기 자신 삭제 거부 및 마지막 SYS_ADMIN 보호를 두지 않는 정책은 현재 백엔드를 따른다.

## 사용자 상태 코드 연동 (2026-09-20)

- 사용자 목록 조회/새로고침 시 `GET /api/v1.0/code?path=UserStatus&enum=true&lang={현재 언어}`를 병행 호출한다. 사용자관리 메뉴의 X-Menu-Id와 요청 취소 signal을 유지한다.
- 응답의 code/name을 검색 상태, 그리드 상태명, 등록·수정 및 상태 일괄 변경 select에 공통 적용한다.
- 허용 상태 검증도 API가 반환한 코드 목록을 사용한다. 정상 상태 기본값 20은 현재 등록 API 계약을 유지한다.
- bootstrap의 statuses와 프런트의 고정 상태 목록에 의존하지 않는다. 조회 실패는 재시도할 수 있고, 목록을 받기 전에 상태 편집을 열지 않는다.
