# Source Han Sans 로컬 폰트 적용

- 날짜: 2026-09-20
- 대상: kkdugi-admin 로그인, 관리 화면, AG Grid

Pretendard 대신 Adobe Source Han Sans 2.005R을 사용한다. 한국어·일본어
지역별 가변 WOFF2 원본과 SIL OFL 1.1 라이선스를
`src/main/resources/static/vendor/source-han-sans/`에 포함했다.
출처 URL은 같은 디렉터리의 README.md에 기록한다.

공통 `static/css/fonts.css`를 app.css와 login.css가 상대 경로로 불러온다.
한국어·영어 화면에서는 KR을 우선 사용하고, 일본어 `ja`와 기존 `jp_JA`의
HTML 언어 태그에서는 JP를 우선 사용한다. 두 글꼴을 상호 fallback으로 지정한다.
AG Grid와 Bulma도 공통 `--kk-font-family` 설정을 사용한다.
폰트 다운로드를 위한 외부 CDN 요청은 없으며, WOFF2 두 파일의 합계는 약 7.9 MB다.
`font-display: swap`을 사용해 폰트 로딩 중에도 텍스트를 표시한다.

## 검증

- 로컬 HTTP 미리보기에서 KR·JP WOFF2 로드 성공 및 한·일 텍스트 렌더링 확인.
- JavaScript 테스트 55개 통과.
- Maven 테스트 296개 중 295개 통과, 1개 실패.
  `DefaultMenuSeedTest.everySeededMenu_hasJapaneseName`에서 `admin/user`의
  `ja_JP` 메뉴명을 조회한 결과가 빈 목록이었다. 이 검사는 DB 시드 데이터만
  조회하며 폰트 파일이나 CSS를 사용하지 않는다. 이번 폰트 변경에서는
  해당 테스트·마이그레이션·DB 데이터를 수정하지 않았다.
- 환경에 docker-compose 명령은 없지만 기존 PostgreSQL에 연결되어 Maven
  통합 테스트가 실행됐다.
