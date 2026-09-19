# 공통코드 조회 (사용자용) - GET /api/v1.0/code

현재 코드 조회 리팩토링 기준(2026-09-19). 관리용 목록·저장은 [common-code.md](common-code.md)의 POST /api/v1.0/admin/code를 사용한다.

## 요청

| 파라미터 | 설명 |
|---|---|
| path | 필수. 코드의 전체 경로와 정확히 일치하는 사용(Y) 코드를 조회한다. 예: /SYS/USER_STAT/ACTIVE |
| lang | 선택. ko_KR/en_US. 서버 LocaleResolver가 해석한다. |

path 누락은 400(code.err.malformed_request), 존재하지 않는 경로는 빈 배열이다. parentId와 page/pageSize는 이 API에서 사용하지 않는다. 부모 경로의 하위 목록이 필요한 관리 화면은 관리용 API의 parentId 검색을 사용한다.

## 응답

페이지 객체가 아닌 배열이다.

~~~json
[
  { "id": "C1", "parentId": "C0", "code": "ACTIVE", "name": "사용", "remarks": null,
    "extra1": null, "extra2": null, "extra3": null, "extra4": null, "extra5": null,
    "path": "/SYS/USER_STAT/ACTIVE", "level": 2, "sort": 1 }
]
~~~

name은 요청 언어의 이름이며 번역이 없으면 code 값으로 대체한다. use=N인 행은 제외한다. 정렬은 sort, code 순이다.

## 캐시

CodeService는 path와 언어별로 조회 결과를 캐시한다. AdminCodeService의 배치 저장이 성공하면 공통코드 캐시 전체를 비운다. CacheConfigurer에서 Spring 캐시를 활성화하며 TransactionAwareCacheManagerProxy로 캐시 변경을 DB 트랜잭션 커밋 이후에 적용한다.

## 인가

인증과 실제 호출 메뉴의 `X-Menu-Id`, READ 권한이 필요하다. 공통 API이므로 program을 특정 관리 화면으로 제한하지 않는다. `__shell__`은 허용하지 않는다. 미인증은 401, 메뉴/권한 오류는 403이다. [요청 컨텍스트](request-context.md) 참조.
