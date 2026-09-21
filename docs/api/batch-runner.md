# 배치 Runner API (S1)

- 구현: 2026-09-20 (S1). 계약 원본: [docs/batch/admin-api.md](../batch/admin-api.md), [docs/batch/runner-api.md](../batch/runner-api.md). 설계: [ADR-0019](../adr/0019-batch-library-boundary.md).
- 이 문서는 **구현된 범위만** 다룬다. Program·설치·Job·Run·배정(`claim`/`start`/`completion` 등)·`GET /assignments`는 후속 조각(S2~S5)이라 아직 없다. 따라서 실제 runner의 `run` 루프(세션 개설 후 `GET /assignments?state=UNRESOLVED` 복구와 설치 보고)는 S3 이후에 검증한다.

## 1. 관리자 API — `/api/v1.0/admin/batch/runners`

사용자 JWT와 `X-Menu-Id`(프로그램 `admin/batch/runner` 메뉴)를 쓴다([request-context.md](request-context.md)). 조회 READ, 생성·수정·폐기·토큰 발급 WRTE, 삭제 DELT. 등록 토큰 발급과 폐기는 추가로 `SYS_ADMIN` 역할이 필요하다. 오류는 `{code, message}`.

| Method / 경로 | 설명 | 성공 |
|---|---|---|
| `GET /` | 목록. query `code`, `name`, `status`, `page`, `pageSize`(기본 1/200). 정렬은 생성 시각·ID 내림차순 | 200 `Page` |
| `GET /{id}` | 상세 | 200 |
| `POST /` | 생성 `{code, name, capacity}`. `REGISTERING` 상태로 만든다 | 201 + `Location` |
| `PUT /{id}` | 전체 교체 `{code, name, capacity, status?}`. `If-Match: "{version}"` 필수 | 200 |
| `DELETE /{id}` | `REGISTERING`/`REVOKED`에서만. credential은 함께 삭제되고 event는 남는다 | 204 |
| `POST /{id}/enrollment` | 등록 토큰 발급(10분, 1회용, `no-store`). `REGISTERING`/`REVOKED`에서만 | 201 `{credentialId, enrollmentToken, expiresAt}` |
| `POST /{id}/revoke` | `{reason}`. 모든 자격증명 폐기, `REVOKED` | 200 |

- 쓰기 명령(등록 토큰 발급 제외)은 `Idempotency-Key`(1~200자 ASCII)와 `X-Request-Created-At`(UTC `Z`, 소수 6자리 이하)가 필수다. 같은 key·같은 요청은 최초 응답(상태·본문·Location)을 재현하고(72시간), 같은 key에 다른 요청은 409 `batch.idempotency.conflict`, 기록이 없는 key의 생성 시각이 서버 시각과 5분 넘게 어긋나면 410 `batch.request.expired`다.
- `code`는 `[A-Za-z0-9][A-Za-z0-9._-]{0,19}`, `capacity`는 1~200. `code`는 수정할 수 없다. `status`는 선택이며 지정하면 `ACTIVE`/`PAUSED`만, 현재 상태도 `ACTIVE`/`PAUSED`여야 한다(아니면 409 `batch.state.conflict`).
- 응답 필드: `id, code, name, capacity, status, hostname, os, agentVersion, lastSeenAt, online, session, version`. `session`·`version`은 십진 문자열, `online`은 `lastSeenAt`이 30초 이내인지다. 알 수 없는 요청 필드는 400.
- `version`은 등록·폐기·재발급(`REVOKED`→`REGISTERING`)·PUT에서 올라가고 heartbeat·세션 개설로는 올라가지 않는다.
- 요청 본문은 UTF-8 기준 1 MiB 이하여야 한다. 초과는 413 `batch.payload.too_large`이며 Content-Length가 없거나 거짓이어도 실제로 읽은 byte 수로 판정한다. 처리 순서는 인증(401) → 본문 한도(413) → 메뉴 권한(403) → 요청 검증(400)이다.
- 정수 필드는 JSON 정수, 문자열 필드는 JSON 문자열만 받는다. `1.9`, `"1"`, `1`을 다른 타입으로 조용히 바꾸지 않고 400이다.

| 상태 | code |
|---|---|
| 400 | `batch.request.invalid` |
| 404 | `batch.runner.not_found` |
| 409 | `batch.runner.duplicate_code`, `batch.state.conflict`, `batch.idempotency.conflict` |
| 410 | `batch.request.expired` |
| 413 | `batch.payload.too_large` |
| 412 / 428 | `batch.version.conflict` / `batch.version.required` |

## 2. Runner API — `/api/v1.0/batch-agent`

runner 전용 보안 체인이 처리한다(사용자 JWT는 401, runner 토큰은 사용자 API에서 401). `Authorization: Bearer {credentialId}.{secret}`, 모든 요청에 `X-Protocol-Version: 1`(아니면 409 `batch.protocol.unsupported`). 모든 응답은 `Cache-Control: no-store`. 시각은 UTC `Z`, bigint는 십진 문자열.

| Method / 경로 | 자격증명 | 설명 |
|---|---|---|
| `POST /registrations` | ENROLLMENT | `{runnerCode, agentVersion, hostname, os(LINUX/WINDOWS), architecture(AMD64/ARM64)}` → 201 `{runnerId, credentialId, accessToken, tokenExpiresAt, session}`. 토큰 원자 소비, runner `ACTIVE` |
| `POST /sessions` | ACCESS | `{bootId(UUID), expectedSession, agentVersion}` → 200 `{runnerId, session, serverTime, heartbeatSeconds, pollSeconds, leaseSeconds, capacity, limits}`. 같은 `bootId`는 같은 세대를 반환하고, 새 `bootId`는 `expectedSession`이 현재 세대와 같을 때만 세대를 올린다 |
| `POST /heartbeat` | ACCESS | 헤더 `X-Runner-Session`. `{observedAt, mode, freeSlots, assignments}` → 200 `{serverTime, acceptingAssignments, assignments}`. `last_seen`만 갱신한다 |

- 자격증명 종류가 맞지 않으면 403 `batch.runner.forbidden`, 없거나 폐기·만료되면 401 `batch.credential.invalid`. 낡은 세션은 409 `batch.session.stale`.
- 요청 본문 1 MiB 초과는 413 `batch.payload.too_large`. 처리 순서는 인증(401/403) → 본문 한도(413) → 프로토콜 버전(409) → 요청 검증(400)이다. 정수·문자열 필드의 JSON 타입이 다르면(예: `expectedSession`을 숫자로 보냄) 400이다.
- **S1 한계**: 배정 테이블이 없으므로 heartbeat에 보고된 assignment id는 모두 `RECONCILE`로 응답한다. S3에서 실제 조회로 대체한다.
- 등록 응답을 잃으면 토큰을 재조회할 수 없다. 관리자가 새 등록 토큰을 발급한다(미사용 이전 토큰은 자동 폐기).
