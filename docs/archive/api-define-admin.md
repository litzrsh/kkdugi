> **폐기됨 (2026-09-18)** — 프로젝트 오너 지시로 `docs/api-define-admin.md`
> 원본을 삭제하고 이 자리에 참고용으로 옮겼다. 1절(공통코드관리)·2절(메시지
> 관리)은 이미 구현되었고 실제 동작 기준 문서는
> [`docs/api/common-code.md`](../api/common-code.md)/
> [`docs/api/i18n-message.md`](../api/i18n-message.md)다 — 그 두 문서와 이
> 문서가 다르면 그쪽이 맞다. 3~5절(메뉴/권한/사용자)은 아직 구현되지 않았고,
> 이 문서가 그 스펙의 유일한 기록이라 내용을 그대로 보존한다. 다만 이
> 문서는 더 이상 살아있는 계약이 아니다 — kkdugi-design 쪽 결정
> (`kkdugi-design/docs/README.md`, `kkdugi-design/docs/adr/0009-pragma-menu-screen-architecture.md`
> 참고: "폐기된 `api-define-admin.md`... 신규 구현 근거가 아니다")에 따라,
> 이 스펙을 근거로 그대로 구현을 시작하지 말고 실제 착수 시점에 오너에게
> 재확인한다.
>
> 아래는 삭제 시점의 원문 그대로다(섹션 번호를 다른 문서/코드 주석이
> 참조하고 있어 바꾸지 않았다).

# Admin 시스템 API 정의서

공통사항, 신규 등록인 경우 id는 빈 값으로 Request하며 서버에서 채번 실시

## 1. 공통코드관리

### 1.1. 코드 조회 - /api/v1.0/admin/code

POST /api/v1.0/admin/code

```javascript
Request
{
  "parentId"?: "parentId",
  "path"?: "code path",
  "code"?: "code value",
  "name"?: "code name",
  "use"?: "Y/N",
  "page": 1,
  "pageSize": 200
}
```

```javascript
Response
{
  "page": 1,
  "pageSize": 200,
  "totalItems": 100,
  "totalPages": 10,
  "contents": [
    {
      "id": "C2026091517460002",
      "parentId": "C2026091517460001",
      "code": "code",
      "locale": { "ko_KR": { "name": "..", "remarks": ".." }, ... },
      "use": "Y",
      "extra1": "..",
      "extra2": "..",
      "extra3": "..",
      "extra4": "..",
      "extra5": "..",
      "path": "/SYS...",
      "level": 1,
      "sort": 0
    }, ...
  ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|

### 1.2. 코드 저장 - /api/v1.0/admin/code/persist

POST /api/v1.0/admin/code/persist
**삭제 시, 하위 코드도 모두 삭제**

```javascript
Request
{
  "insert": [ {
    "id": "C2026091517460002",
    "parentId": "C2026091517460001",
    "code": "code",
    "locale": { "ko_KR": { "name": "..", "remarks": ".." }, ... },
    "use": "Y",
    "extra1": "..",
    "extra2": "..",
    "extra3": "..",
    "extra4": "..",
    "extra5": "..",
    "path": "/SYS...",
    "level": 1,
    "sort": 0
  } ],
  "update": [ {
    "id": "C2026091517460002",
    "parentId": "C2026091517460001",
    "code": "code",
    "locale": { "ko_KR": { "name": "..", "remarks": ".." }, ... },
    "use": "Y",
    "extra1": "..",
    "extra2": "..",
    "extra3": "..",
    "extra4": "..",
    "extra5": "..",
    "path": "/SYS...",
    "level": 1,
    "sort": 0
  } ],
  "delete": [ {
    "id": "C2026091517460002",
    "parentId": "C2026091517460001",
    "code": "code",
    "locale": { "ko_KR": { "name": "..", "remarks": ".." }, ... },
    "use": "Y",
    "extra1": "..",
    "extra2": "..",
    "extra3": "..",
    "extra4": "..",
    "extra5": "..",
    "path": "/SYS...",
    "level": 1,
    "sort": 0
  } ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|400|Validation failure|
|409|코드충돌 **같은 경로의 코드는 중복될 수 없음, insert 전용**|

---

## 2. 메시지 관리

### 2.1. 메시지 조회 - /api/v1.0/admin/i18n

POST /api/v1.0/admin/i18n

```javascript
Request
{
  "code"?: "message code",
  "message"?: "message value",
  "page": 1,
  "pageSize": 200
}
```

```javascript
Response
{
  "page": 1,
  "pageSize": 200,
  "totalItems": 100,
  "totalPages": 10,
  "contents": [
    {
      "code": "system.msg.test",
      "locale": { "ko_KR": "value", ... }
    }, ...
  ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|

### 2.2. 메시지 저장 - /api/v1.0/admin/i18n/persist

POST /api/v1.0/admin/i18n/persist

```javascript
Request
{
  "insert": [ {
    "code": "system.msg.test1",
    "locale": { "ko_KR": "value", ... }
  } ],
  "update": [ {
    "code": "system.msg.test2",
    "locale": { "ko_KR": "value", ... }
  } ],
  "delete": [ {
    "code": "system.msg.test3",
    "locale": { "ko_KR": "value", ... }
  } ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|400|Validation failure|
|409|메시지코드 충돌 **메시지코드는 중복될 수 없음, insert 전용**|

---

## 3. 메뉴 관리

### 3.1. 메뉴 조회 - /api/v1.0/admin/menu

GET /api/v1.0/admin/menu

```javascript
Request
{
}
```

```javascript
Response
[
  {
    "id": "M2026091517570001",
    "locale": { "ko_KR": { "label": "..", "remarks": "..." } },
    "icon": "...",
    "program": "adcode",
    "use": "Y",
    "sort": 1,
    "path": "/M2026091517570001/...",
    "level": 0,
    "children": [
      {
        "id": "M2026091517570002",
        "locale": { "ko_KR": { "label": "..", "remarks": "..." } },
        "icon": "...",
        "program": "adcode",
        "use": "Y",
        "sort": 1,
        "path": "/M2026091517570001/...",
        "level": 0,
        "children": [...]
      },
      ...
    ]
  },
  ...
]
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|

### 3.2. 메뉴 저장 - /api/v1.0/admin/menu/persist

POST /api/v1.0/admin/menu/persist
**메뉴가 삭제되면 하위 메뉴도 모두 삭제한다**

```javascript
Request
{
  "insert": [ {
    "id": "M2026091517570001",
    "locale": { "ko_KR": { "label": "..", "remarks": "..." } },
    "icon": "...",
    "program": "adcode",
    "use": "Y",
    "sort": 1,
    "path": "/M2026091517570001/...",
    "level": 0
  } ],
  "update": [ {
    "id": "M2026091517570001",
    "locale": { "ko_KR": { "label": "..", "remarks": "..." } },
    "icon": "...",
    "program": "adcode",
    "use": "Y",
    "sort": 1,
    "path": "/M2026091517570001/...",
    "level": 0
  } ],
  "delete": [ {
    "id": "M2026091517570001",
    "locale": { "ko_KR": { "label": "..", "remarks": "..." } },
    "icon": "...",
    "program": "adcode",
    "use": "Y",
    "sort": 1,
    "path": "/M2026091517570001/...",
    "level": 0
  } ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|400|Validation failure|

---

## 4. 권한 관리

### 4.1. 권한 조회 - /api/v1.0/admin/authority

POST /api/v1.0/admin/authority

```javascript
Request
{
  "role"?: "role",
  "type"?: "type",
  "name"?: "name",
  "page": 1,
  "pageSize": 200
}
```

```javascript
Response
{
  "page": 1,
  "pageSize": 200,
  "totalItems": 100,
  "totalPages": 10,
  "contents": [
    {
      "id": "A2026091508110001",
      "role": "SYS_ADMIN",
      "type": "ROLE",
      "name": "시스템 관리자",
      "remarks": "...",
      "use": "Y"
    }, ...
  ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|

### 4.2. 권한 상세 조회 - /api/v1.0/admin/authority/{id}

GET /api/v1.0/admin/authority/{id}

```javascript
Response
{
  "id": "A2026091508110001",
  "role": "SYS_ADMIN",
  "type": "ROLE",
  "name": "시스템 관리자",
  "remarks": "...",
  "use": "Y",
  "users": [
    "U2026091508020001",
    ...
  ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|404|존재하지 않거나 삭제된 권한|

### 4.3. 권한 등록 - /api/v1.0/admin/authority/regist

POST /api/v1.0/admin/authority/regist

```javascript
Request
{
  "role": "SYS_ADMIN",
  "type": "ROLE",
  "name": "시스템 관리자",
  "remarks": "...",
  "use": "Y",
  "users": [
    "U2026091508020001",
    ...
  ],
  "menus": [
    {
      "id": "M2026080102030001",
      "authorities": { "READ": true, "WRITE": false, ... }
    },
    ...
  ]
}
```

```javascript
Response
{
  "id": "A2026091508110001",
  "role": "SYS_ADMIN",
  "type": "ROLE",
  "name": "시스템 관리자",
  "remarks": "...",
  "use": "Y",
  "users": [
    { "id": "U2026091508020001", "applyStartDate": "...", "applyEndDate": "..." },
    ...
  ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|400|Validation failure|

### 4.4. 권한 저장 - /api/v1.0/admin/authority/{id}

POST /api/v1.0/admin/authority/{id}

```javascript
Request
{
  "id": "A2026091508110001",
  "role": "SYS_ADMIN",
  "type": "ROLE",
  "name": "시스템 관리자",
  "remarks": "...",
  "use": "Y",
  "users": [
    { "id": "U2026091508020001", "applyStartDate": "...", "applyEndDate": "..." },
    ...
  ],
  "menus": [
    {
      "id": "M2026080102030001",
      "authorities": { "READ": true, "WRITE": false, ... }
    },
    ...
  ]
}
```

```javascript
Response
{
  "id": "A2026091508110001",
  "role": "SYS_ADMIN",
  "type": "ROLE",
  "name": "시스템 관리자",
  "remarks": "...",
  "use": "Y",
  "users": [
    { "id": "U2026091508020001", "applyStartDate": "...", "applyEndDate": "..." },
    ...
  ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|400|Validation failure|

### 4.5. 권한 삭제 - /api/v1.0/admin/authroity/{id}/delete

POST /api/v1.0/admin/authroity/{id}/delete
**권한이 삭제될 때 권한 사용자 및 권한 메뉴도 같이 삭제**

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|

### 4.5. 사용자 조회 - /api/v1.0/admin/authority/{id}/user

POST /api/v1.0/admin/authority/{id}/user
**조회되는 사용자는 권한에 맵핑되지 않고, 상태가 정상인 사용자**

```javascript
Request
{
  "query"?: "Query string, search for login id, name, email, case insensitive"
}
```

```javascript
Response
[
  {
    "id": "U2026091509120001",
    "username": "admin",
    "name": "name",
    "image": "url"
  },
  ...
]
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|404|권한이 삭제되거나 없음|

### 4.6. 메뉴 조회 - /api/v1.0/admin/authority/{id}/menu

POST /api/v1.0/admin/authority/{id}/menu

```javascript
Response
[
  {
    "id": "M2026091517570001",
    "locale": { "ko_KR": { "label": "..", "remarks": "..." } },
    "icon": "...",
    "program": "adcode",
    "use": "Y",
    "sort": 1,
    "path": "/M2026091517570001/...",
    "level": 0,
    "authorities": { "READ": true, "WRITE": false, ... },
    "children": [
      {
        "id": "M2026091517570002",
        "locale": { "ko_KR": { "label": "..", "remarks": "..." } },
        "icon": "...",
        "program": "adcode",
        "use": "Y",
        "sort": 1,
        "path": "/M2026091517570001/...",
        "level": 0,
        "authorities": { "READ": true, "WRITE": false, ... },
        "children": [...]
      },
      ...
    ]
  }
]
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|404|권한이 삭제되거나 없음|

---

## 5. 사용자 관리

### 5.1. 사용자 조회 - /api/v1.0/admin/user

POST /api/v1.0/admin/user

```javascript
Request
{
  "username"?: "login id",
  "name"?: "name",
  "status"?: "status",
  "page": 1,
  "pageSize": 200
}
```

```javascript
Response
{
  "page": 1,
  "pageSize": 200,
  "totalItems": 100,
  "totalPages": 10,
  "contents": [
    {
      "id": "U2026091508110001",
      "username": "...",
      "name": "...",
      "remarks": "...",
      "image": "...",
      "email": "...",
      "status": "..."
      "lastLoginAt": "yyyy-MM-dd HH:mm:ss",
      "lastChangePasswordAt": "yyyy-MM-dd HH:mm:ss",
      "passwordStatus": "..."
    },
    ...
  ]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|

### 5.2. 사용자 조회 - /api/v1.0/admin/user/{id}

GET /api/v1.0/admin/user/{id}

```javascript
Response
{
  "id": "U2026091508110001",
  "username": "...",
  "name": "...",
  "remarks": "...",
  "image": "...",
  "email": "...",
  "status": "...",
  "lastLoginAt": "yyyy-MM-dd HH:mm:ss",
  "lastChangePasswordAt": "yyyy-MM-dd HH:mm:ss",
  "passwordStatus": "..."
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|404|존재하지 않는 사용자 또는 삭제된 사용자|

### 5.3. 사용자 등록 - /api/v1.0/admin/user/regist

POST /api/v1.0/admin/user/regist

```javascript
Request
{
  "username": "...",
  "name": "...",
  "remarks": "...",
  "image": "...",
  "email": "...",
  "status": "...",
}
```

```javascript
Response
{
  "id": "U2026091508110001",
  "username": "...",
  "name": "...",
  "remarks": "...",
  "image": "...",
  "email": "...",
  "status": "...",
  "lastLoginAt": "yyyy-MM-dd HH:mm:ss",
  "lastChangePasswordAt": "yyyy-MM-dd HH:mm:ss",
  "passwordStatus": "..."
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|409|username 충돌|
|400|Validation failure|

### 5.4. 사용자 저장 - /api/v1.0/admin/user/{id}

POST /api/v1.0/admin/user/{id}
**username 즉 로그인ID는 등록 후 수정할 수 없음**

```javascript
Request
{
  "id": "U2026091508110001",
  "username": "...",
  "name": "...",
  "remarks": "...",
  "image": "...",
  "email": "...",
  "status": "...",
}
```

```javascript
Response
{
  "id": "U2026091508110001",
  "username": "...",
  "name": "...",
  "remarks": "...",
  "image": "...",
  "email": "...",
  "status": "...",
  "lastLoginAt": "yyyy-MM-dd HH:mm:ss",
  "lastChangePasswordAt": "yyyy-MM-dd HH:mm:ss",
  "passwordStatus": "..."
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|400|Validation failure|
|404|존재하지 않는 사용자 또는 삭제된 사용자|

### 5.5. 권한 목록 조회 - /api/v1.0/admin/user/{id}/authorities

GET /api/v1.0/admin/user/{id}/authorities

```javascript
Response
[
  {
    "id": "A2026091508110001",
    "role": "SYS_ADMIN",
    "type": "ROLE",
    "name": "시스템 관리자",
    "remarks": "...",
    "use": "Y",
    "applyStartDate": "...",
    "applyEndDate": "..."
  },
  ...
]
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|404|존재하지 않는 사용자 또는 삭제된 사용자|

### 5.6. 권한 목록 저장 - /api/v1.0/admin/user/{id}/authorities

POST /api/v1.0/addmin/user/{id}/authorities

```javascript
Request
[
  "insert": [
    {
      "id": "A2026091508110001",
      "role": "SYS_ADMIN",
      "type": "ROLE",
      "name": "시스템 관리자",
      "remarks": "...",
      "use": "Y",
      "applyStartDate": "...",
      "applyEndDate": "..."
    },
    ...
  ],
  "update": [
    {
      "id": "A2026091508110001",
      "role": "SYS_ADMIN",
      "type": "ROLE",
      "name": "시스템 관리자",
      "remarks": "...",
      "use": "Y",
      "applyStartDate": "...",
      "applyEndDate": "..."
    },
    ...
  ],
  "delete": [
    {
      "id": "A2026091508110001",
      "role": "SYS_ADMIN",
      "type": "ROLE",
      "name": "시스템 관리자",
      "remarks": "...",
      "use": "Y",
      "applyStartDate": "...",
      "applyEndDate": "..."
    },
    ...
  ],
]
```

```javascript
Response
[
  {
    "id": "A2026091508110001",
    "role": "SYS_ADMIN",
    "type": "ROLE",
    "name": "시스템 관리자",
    "remarks": "...",
    "use": "Y",
    "applyStartDate": "...",
    "applyEndDate": "..."
  },
  ...
]
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
|404|존재하지 않는 사용자 또는 삭제된 사용자|
|400|Validation failure|

### 5.7. 사용자 비밀번호 초기화 - /api/v1.0/admin/user/reset-password

POST /api/v1.0/admin/user/reset-password

```javascript
Request
{
  "id": ["U2026080102040001", ...]
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|

### 5.8. 사용자 삭제 - /api/v1.0/admin/user/{id}/delete

POST /api/v1.0/user/{id}/delete

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|

### 5.9. 사용자 상태 일괄 변경 - /api/v1.0/admin/user/change-status

POST /api/v1.0/admin/user/change-status

```javascript
Request
{
  "id": ["U2026080222320001",...],
  "status": "..."
}
```

|Response Status|설명|
|---|---|
|200|정상|
|403|접근권한없음|
|401|세션만료|
