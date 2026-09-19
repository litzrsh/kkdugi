# 공통코드 조회 (사용자용) - GET /api/v1.0/code

로그인 여부와 무관하게(현재 인가 규칙은 전부 `permitAll`) 사용할 수 있는 읽기 전용 API.
관리자용 조회/저장은 [common-code.md](common-code.md)(`/api/v1.0/admin/code`)이며 모델을 공유하지 않는다.

## 요청

쿼리 파라미터 (모두 선택):

| 이름 | 설명 |
|---|---|
| `path` | 이 경로(`/SYS/USER/STAT` 형식)의 코드의 하위 코드를 조회한다. 우선순위 1 |
| `parentId` | 이 ID의 코드의 하위 코드를 조회한다. `path`가 없을 때만 사용 |
| `page`, `pageSize` | 기본 1, 200. `pageSize` 최대 200 |
| `lang` | `ko_KR`/`en_US`. 지정하면 이후 요청에도 쿠키로 유지된다(기본 `ko_KR`) |

`path`와 `parentId`가 모두 없으면 최상위 코드를 조회한다. 존재하지 않는 `path`는 빈 목록이다.

## 응답 200

```json
{
  "page": 1,
  "pageSize": 200,
  "totalItems": 2,
  "totalPages": 1,
  "contents": [
    { "id": "C2026091912000001", "parentId": "C2026091912000000", "code": "ACTIVE",
      "name": "사용", "remarks": null, "extra1": null, "extra2": null, "extra3": null,
      "extra4": null, "extra5": null, "path": "/SYS/USER_STAT/ACTIVE", "level": 2, "sort": 1 }
  ]
}
```

- `use = 'Y'`인 코드만 내려준다. 정렬은 `sort`(없으면 뒤), `code` 순.
- `name`은 요청 언어의 이름이고, 그 언어 행이 없으면 `code` 값으로 대체된다. `remarks`는 그 언어 행이 없으면 `null`.
