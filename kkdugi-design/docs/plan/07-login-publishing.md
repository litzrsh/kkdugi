# 로그인 화면 구현 · 2026-09-17

## 화면과 구현

- 데스크톱: 세이지 색 소개 패널과 로그인 폼. 모바일: 브랜드와 폼을 중심으로 단일 열 구성.
- 독립 HTML + CSS3 + vanilla JS. Vue, SFC loader, AG Grid 및 관리자 bootstrap을 로드하지 않는다.
- 로컬 Bulma, Pretendard, line-awesome를 사용한다. 장식 그래픽은 CSS로 구성한다.
- 아이디/비밀번호, 비밀번호 표시 전환, Caps Lock 알림, 제출 중 중복 방지, 오류/로그아웃 안내.
- 가입, 비밀번호 찾기, 자동 로그인은 인증 계약이 없으므로 임의로 추가하지 않는다.

## 파일과 미리보기

- `static/login.html`: `/login.html`에서 확인하는 개발 전용 미리보기. 자격 증명을 전송하지 않으며 실제 로그인 성공을 흉내 내지 않는다. JS 비활성 시 제출 버튼도 비활성이다.
- `static/auth/login.css`, `login.js`: 운영/미리보기 공용. JS 없이도 운영의 기본 form POST는 동작한다.
- `static/auth/login-messages.json`: 미리보기 영어 전환용. 운영에서는 호출하지 않는다.
- `templates/admin/login.html`: Thymeleaf 뷰 `admin/login`.
- `messages/login-ui_ko_KR.properties`, `login-ui_en_US.properties`: MessageSource 번들.
- 미리보기 상태: `/login.html?error=true`, `/login.html?logout=true`, `/login.html?lang=en_US`.

## 서버 연결 계약

로그인 API는 관리자 CRUD 명세와 별개이며 아직 확정되지 않았다. 서버에서 `loginAction` 모델에 contextPath가 포함된 실제 로그인 처리 URL을 제공한다. 폼은 이 URL에 `username`, `password`를 일반 POST한다. 기본 정적 action `/login`은 예시이며 서버 라우트를 생성한 것은 아니다.

Spring Security 사용 시 `_csrf.parameterName`, `_csrf.token`을 hidden input으로 렌더링한다. 인증 실패는 `?error`, 로그아웃 완료는 `?logout`으로 일반화된 안내를 표시한다. 실제 리다이렉트 및 인증 성공 처리는 서버 책임이다. 비밀번호를 쿼리·저장소·로그에 기록하지 않는다.

언어 전환은 `?lang=ko_KR/en_US`를 사용한다. 서버 LocaleResolver/Interceptor와 로그인 번들을 연결한다. 운영 배포에서 `static/login.html` 및 `login-messages.json`은 제외 가능하다.

검증: JS 문법 검사, 데스크톱 1440px 및 모바일 390px 브라우저 레이아웃, 비밀번호 표시 전환, 미리보기 제출 안내 확인. 실제 Spring 인증 처리는 미연동이다.

## 필수 비밀번호 변경 / 중복 로그인 · 2026-09-17

### 화면과 흐름

1. 자격 증명 검증 후 최초 비밀번호 또는 만료 상태이면 `admin/login-password`를 렌더링한다. 새 비밀번호와 확인 값을 입력해야 계속할 수 있고, 로그인 취소를 제공한다. 사유를 구분해 안내한다.
2. 다른 기기의 활성 세션이 있으면 `admin/login-session`을 렌더링한다. **이전 세션 종료 후 로그인** 또는 **로그인하지 않기**를 선택한다. 후자는 기존 세션을 유지하고 이번 로그인 시도만 취소한다.
3. 두 조건이 모두 해당하면 퍼블리싱 기준으로 비밀번호 변경 → 중복 로그인 확인 순서를 사용한다. 최종 순서는 서버 인증 정책과 일치시킨다. 비밀번호 변경 완료 자체를 최종 로그인 성공으로 간주하지 않는다.
4. 두 화면도 plain HTML + CSS3 + vanilla JS이며 Vue/DialogHost를 사용하지 않는다. 로그인과 같은 프레임에서 단일 과제를 안내한다.

### 서버 연결 (신규 API 경로는 아직 미정)

기존 관리자 API에는 이 인증 과정의 계약이 없다. 백엔드를 구현하거나 실제 세션을 종료한 작업은 아니다. 일반 form POST로 연결할 모델을 정의했다.

| 모델 | 의미 |
| --- | --- |
| passwordChangeAction | 비밀번호 변경을 검증·저장하고 다음 로그인 단계로 이동하는 서버 URL |
| forceLoginAction | 기존 세션 종료 및 새 로그인 완료 처리 URL |
| cancelLoginAction | 보류 중인 로그인 시도 폐기 후 로그인 화면으로 이동하는 URL |
| challengeId | 서버가 발급한 짧은 수명의 일회성 로그인 시도 식별자 |
| passwordReason | INITIAL 또는 EXPIRED |
| passwordPolicyText | 서버 정책을 설명하는 현재 locale 문구 |
| authError | 실패 여부. 화면은 일반화한 오류 안내를 표시 |

비밀번호 변경 POST는 `newPassword`, `confirmPassword`, `challengeId`를 전송한다. 강제 로그인/취소는 `challengeId`만 전송한다. 모든 POST에 Spring `_csrf` hidden field를 포함한다. URL 모델은 contextPath를 포함해야 한다.

- 자격 증명 검증 전에는 계정의 만료/중복 로그인 정보를 노출하지 않는다.
- challengeId는 인증된 보류 단계, 계정, 브라우저 시도에 서버가 연결하여 검증한다. 클라이언트 쿼리나 hidden 값만으로 단계를 통과시키지 않는다.
- 필수 변경을 건너뛰거나 아직 중복 확인 중인 사용자는 관리자 권한을 얻지 않는다. 서버에서 모든 보호된 요청에 적용한다.
- 비밀번호 길이·복잡도·재사용 제한은 서버 정책으로 검증한다. UI에는 임의 길이 규칙을 만들지 않았으며 확인 값 일치와 필수 입력을 검사한다. 실패 시 새 비밀번호를 HTML에 다시 출력하지 않는다.
- 강제 로그인을 명시적으로 선택하고 서버 검증에 성공한 경우에만 이전 세션을 폐기한다. 취소/시간 초과로 기존 세션을 끊지 않는다. 동시 요청/재전송에도 최종 세션 정책이 유지되도록 서버에서 원자적으로 처리한다.
- 기존 세션의 기기/IP/시간은 실제 API가 없으므로 임의로 표시하지 않는다.
- 상태 만료, challenge 재사용, CSRF 실패는 새 로그인부터 진행하도록 서버가 처리한다.

### 미리보기

로그인 페이지 하단에서 일반 / 최초 / 만료 / 중복 / 변경+중복 시나리오를 선택하고 예시 입력으로 진행할 수 있다. 입력은 전송·저장되지 않는다. 실제 비밀번호 변경이나 세션 종료 없이 완료 안내만 표시한다.

- `/login-password.html`: 최초 비밀번호
- `/login-password.html?reason=expired`: 만료
- `/login-password.html?next=session`: 변경 후 중복 확인
- `/login-session.html`: 중복 로그인 선택
- `lang=en_US`로 영어 확인 가능

운영에서는 미리보기 파일을 제외하고 Thymeleaf 템플릿을 사용한다. JS 없이도 서버 폼 제출이 가능하며 비밀번호 정책/확인 값 검증은 서버에서도 필수다.


추가 검증: JS 문법 검사, 실제 브라우저에서 비밀번호 확인 불일치 차단, 변경→중복 확인 전환 및 미리보기 완료 안내, 모바일 390px 레이아웃을 확인했다. 실제 인증 서버와의 통합 검증은 별도 수행한다.
