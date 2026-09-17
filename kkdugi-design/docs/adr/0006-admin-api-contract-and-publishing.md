# ADR-0006: Admin API 정의서 적용과 1차 퍼블리싱

- 날짜: 2026-09-16
- 상태: 프론트엔드 소스 구현, 실제 서버·브라우저 인수 검증 대기
- 기준: `C:\projects\kkdugi\docs\api-define-admin.md`
- 사용자 승인 범위: 정의된 API가 생성될 것으로 가정하고 디자인·퍼블리싱 착수.

## 이전 제안과 달라진 결정

이 문서는 API 계약·구현 구조에 대해 ADR-0001~0005와 계획 01~05의 해당 제안을 우선해 구체화한다. 과거 기록은 삭제하지 않는다.

| 항목 | 적용 결정 |
|---|---|
| 조회 | 코드/i18n/authority/user POST, 메뉴 GET |
| 페이징 | page=1부터, pageSize, contents/totalItems/totalPages |
| 배치 저장 | 코드/i18n/menu의 `/persist`에 `{insert,update,delete}` |
| 신규 id | 빈 문자열, 서버 채번, 성공 후 재조회 |
| 메시지 | 코드별 locale 객체; ADR-0003의 flat POST 변환을 사용하지 않음 |
| 공통코드 삭제 | 하위까지 cascade; 기존 하위 존재 삭제 차단 제안을 대체 |
| 권한 | 등록/수정/삭제 API로 개별 저장. 기본정보 배치 제안을 대체 |
| 사용자 | 신규 등록을 포함. 상태 일괄 변경은 `/user/change-status` 한 번 호출 |
| 매핑 | 권한 저장 시 users/menus 모두 보존. 사용자별 권한은 배치 diff |

정의서에 남은 authroity/addmin 및 사용자 삭제 prefix 차이는 일관된 admin/authority와 admin/user로 정규화해 adapter에 모았다. 경로는 서버 주입 문자열로 교체 가능하다. 새 부모 메뉴 path 전달 방식과 번역 삭제 semantics 등 명세가 확정하지 않은 부분은 작업폴더 README에 기록했다. 임의 endpoint를 추가하지 않았다.

## 이번 소스 구조

공통 App.vue가 screenDefs로 5개 화면을 전환한다. Grid.vue는 화면/로케일 전환 시 해제하며 DialogHost.vue는 shell을 포함한 Vue 컴포넌트로 공통화한다. App과 Dialog는 같은 Vue app의 주입 서비스를 공유한다. 별도 OverlayRoot app이나 화면별 SFC 5개는 이번 공통 화면 패턴에 필요하지 않아 생성하지 않았다.

Thymeleaf는 `admin/index.html`과 초기 adminUiConfig 주입을 담당한다. 별도 bootstrap API는 정의되지 않아 제안 endpoint를 호출하지 않는다. 등록 언어·상태·UI 메시지·CSRF는 서버 모델로 공급한다. 권한별 shell 메뉴 필터 계약은 미정이므로 기본 메뉴 5개를 표시하고 서버에서 실제 권한을 검사해야 한다.

Preview는 메모리 fixture만 사용하고 화면에 명시한다. 운영 live adapter는 예시 데이터를 fallback으로 사용하지 않는다. 사용자 변경사항을 브라우저 영구 저장소에 기록하지 않는다.

## 디자인 및 검증 경계

Kluvo 구조를 참고한 새 kkdugi 라이트 디자인, Bulma·Pretendard·line-awesome·AG Grid 적용. 모바일 drawer, 단열 검색, 44px 조작 영역, 전체 화면 편집·매핑 및 viewport 대응을 소스로 구현했다.

계약 테스트·SFC 컴파일·메모리 Vue 렌더 검사는 수행하지만 브라우저 실기·실제 Spring API 인수 검증은 별도다. 소스 구현 상태와 인수 완료 상태를 구별한다. Git 브랜치 전환·stash·커밋 및 백엔드 코드 변경은 하지 않는다.
