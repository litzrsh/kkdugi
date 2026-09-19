# 공통코드 조회 (사용자용) - GET /api/v1.0/code

현재 코드 조회 기준(2026-09-20, enum 및 직계 하위 코드 조회 추가). 관리용 목록·저장은 [common-code.md](common-code.md)의 POST /api/v1.0/admin/code를 사용한다.

## 요청

| 파라미터 | 설명 |
|---|---|
| path | 필수. 코드의 전체 경로와 정확히 일치하는 사용(Y) 코드를 조회한다. 예: /SYS/USER_STAT/ACTIVE. enum=true일 때는 enum의 단순 클래스 이름(예: UserStatus). |
| enum | 선택 boolean, 기본 false. true이면 DB 대신 core/enums의 CodeEnums 구현 enum을 조회한다. |
| children | 선택 boolean, 기본 false. true이면 path에 해당하는 부모의 활성 직계 하위 코드를 조회한다. enum=true와 함께 사용할 수 없다. |
| lang | 선택. 요청 로케일(예: ko_KR/en_US). 서버 LocaleResolver가 해석한다. |

path 누락은 400(code.err.malformed_request)이다. DB 조회(enum=false)에서 존재하지 않는 경로는 빈 배열이다. parentId와 page/pageSize는 이 API에서 사용하지 않는다. 활성 하위 코드 선택 목록은 children=true를 사용한다. 사용 여부와 관계없이 편집할 관리 목록은 관리용 API의 parentId 검색을 사용한다.

## DB 조회 응답 (enum=false)

페이지 객체가 아닌 배열이다.

~~~json
[
  { "id": "C1", "parentId": "C0", "code": "ACTIVE", "name": "사용", "remarks": null,
    "extra1": null, "extra2": null, "extra3": null, "extra4": null, "extra5": null,
    "path": "/SYS/USER_STAT/ACTIVE", "level": 2, "sort": 1 }
]
~~~

name은 요청 언어의 이름이며 번역이 없으면 code 값으로 대체한다. use=N인 행은 제외한다. 정렬은 sort, code 순이다.

## 직계 하위 코드 및 사용 가능한 언어

예: `GET /api/v1.0/code?path=/SYS/LANG&children=true&lang=ko_KR`

- 부모와 자식 모두 use=Y인 직계 하위 코드만 반환한다. 부모 자체와 손자 코드는 포함하지 않는다.
- 존재하지 않거나 비활성인 부모는 빈 배열이다. 정렬은 sort(NULL은 마지막), code, id 순이다.
- 응답 형식은 위 Code 배열과 같다. enum=true와 children=true의 동시 사용, 잘못된 boolean은 400이다.
- /SYS/LANG의 하위 언어 코드는 extra1에 실제 로케일 키(예: ko_KR)를 저장한다. code 값(예: KO_KR)을 로케일 키로 사용하지 않는다.
- 관리 UI는 name을 언어 표시명으로, extra1을 메시지 locale 및 다국어 탭의 키로 사용한다. 빈/잘못된 extra1은 제외하고 같은 로케일은 정렬상 첫 항목을 사용한다.
- 로케일 형식은 영문 2~8자로 시작하고, 선택적으로 _ 또는 -로 구분한 영숫자 1~8자 부분을 허용한다. 실제 저장된 번역 키와 같은 표기를 사용한다.
- 관리 화면 최초 렌더링도 같은 목록을 사용한다. 코드·메시지·메뉴 화면 조회/새로고침/저장 후 재조회 시 목록을 다시 읽는다. 화면의 API 호출은 해당 메뉴의 X-Menu-Id를 보낸다.
- 비활성 언어의 기존 번역은 화면에서 숨기되 저장 페이로드에서 보존한다. 목록이 비면 안내를 표시하고 번역 등록/수정을 차단한다. 기존 언어 코드의 사용 여부·확장값 수정은 가능하다.

## Enum 조회

예: `GET /api/v1.0/code?path=UserStatus&enum=true&lang=ko_KR`

- 현재 지원 클래스: AuthorityType, PasswordStatus, Rbac, UserStatus. 클래스 이름의 대소문자는 구분한다.
- 같은 패키지에 CodeEnums 구현 enum을 추가하면 별도의 컨트롤러 등록 없이 조회할 수 있다.
- 응답은 동일한 Code 배열이며, enum 선언 순서로 반환한다. code는 getCode(), name은 요청 언어의 labelCode 메시지이다.
- id는 `클래스이름.코드값`(예: UserStatus.10), path는 클래스 이름, level은 0, sort는 1부터의 선언 순번이다. parentId·remarks·extra1~5는 null이다.
- 전체 패키지명, 다른 패키지, 존재하지 않는 이름, enum이 아닌 타입은 400(code.err.malformed_request)이다. 잘못된 enum boolean 값도 400이다.
- enum 생략/false이면 기존 DB 조회를 그대로 수행한다. enum=true에서도 인증·메뉴 READ 인가는 동일하다.
- enum 결과는 DB 공통코드 캐시를 사용하지 않고 매번 현재 언어의 MessageSource로 해석한다. 메시지 관리에서 등록한 DB 번역이 properties보다 우선한다.
- 기존 enum의 labelCode 14개를 기본(영어)·ko_KR·en_US properties에 추가했다.

## 캐시

DB 조회에서 CodeService는 조회 방식(정확한 경로/직계 하위), path와 언어별로 조회 결과를 캐시한다. AdminCodeService의 배치 저장이 성공하면 공통코드 캐시 전체를 비운다. CacheConfigurer에서 Spring 캐시를 활성화하며 TransactionAwareCacheManagerProxy로 캐시 변경을 DB 트랜잭션 커밋 이후에 적용한다.

## 인가

인증과 실제 호출 메뉴의 `X-Menu-Id`, READ 권한이 필요하다. 공통 API이므로 program을 특정 관리 화면으로 제한하지 않는다. `__shell__`은 허용하지 않는다. 미인증은 401, 메뉴/권한 오류는 403이다. [요청 컨텍스트](request-context.md) 참조.
