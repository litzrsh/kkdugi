# Claude S1 실제 구현 계획 검토

- 검토일: 2026-09-20
- 대상: [S1 구현 계획](../superpowers/plans/2026-09-20-batch-s1-runner-registration.md), 검토 시점 파일 274,819 bytes / 수정 시각 22:25:08. 아래 줄 번호는 해당 버전 기준이다.
- 관련: [S1 설계](../superpowers/specs/2026-09-20-batch-s1-runner-registration-design.md), [이전 검토](admin-plan-review.md).
- 결론: 이전 지적의 멱등 기반 S1 이동, register와 run 검증 분리, 메뉴 program 연결 및 경합 테스트는 반영됐다. 아래 네 항목은 제공된 구현 코드를 적용하기 전에 수정해야 한다.
- 이 문서는 코드가 포함된 계획에 대한 검토다. 실제 배치 구현 전체를 빌드하거나 통합 테스트한 결과가 아니다. 원본 계획과 Claude 구현 파일은 수정하지 않았다.

## 1. [P2] JSON 1 MiB 제한을 알리지만 실제 요청에 적용하지 않는다

**위치:** Task 1 `BatchProperties` 468행, Task 8 `BatchAgentSecurityConfig` 4855~4866행 및 요청 처리 구성.

`jsonBytes=1_048_576`은 세션 응답에 포함되지만 HTTP 본문 byte 수를 제한하는 filter/wrapper/converter 작업이 없다. `tooLarge` 호출도 heartbeat 배열의 항목 수 검사뿐이다. [Runner 계약](runner-api.md#1-공통-형식)과 [관리자 계약](admin-api.md#1-공통-계약)은 UTF-8 본문 1 MiB 초과를 413으로 거절하도록 요구한다.

작은 정상 JSON 앞에 1 MiB 이상의 공백을 넣으면 DTO 필드 길이 검사를 모두 통과한다. 반대로 큰 문자열은 먼저 역직렬화된 후 400이 될 수 있어 413 계약과 메모리 사용 제한을 보장하지 못한다.

**수정:** 배치 관리자/agent 경로에 본문 읽기 한도를 적용하고 초과 시 `{code:"batch.payload.too_large",message:...}` / 413을 반환한다. Content-Length 검사만으로 끝내지 않고 길이가 없는 streaming 요청도 제한한다. 인증 오류·본문 초과의 처리 순서를 명시한다.

**추가 테스트:** 한도 이하/정확한 경계/1 byte 초과, 다중 byte UTF-8, 선행 공백, Content-Length 없는 요청. 거절 시 상태·event·멱등 기록이 생성되지 않아야 한다.

## 2. [P2] Jackson 자동 타입 변환 때문에 계약상 잘못된 입력이 수락된다

**위치:** Task 4 `BatchRunnerRequest` 2084~2093행, Task 7 `BatchSessionRequest`, Task 8 4963~4979행.

`BatchStrictRequest`는 미지 필드만 거절한다. `Integer capacity/freeSlots`와 `String expectedSession`에 대한 scalar coercion을 제한하지 않으므로 서비스 검증 전에 잘못된 JSON 타입이 정상 값으로 변환된다.

저장소 테스트 classpath의 Jackson 3.1.5와 Java 17에서 동일 DTO 타입으로 확인한 결과:

```text
{"capacity":1.9}       -> capacity=1, 수락
{"capacity":"1"}     -> capacity=1, 수락
{"expectedSession":1}  -> expectedSession="1", 수락
```

이는 수량은 JSON 정수, session은 십진 문자열이어야 한다는 계약과 다르다. capacity 1.9를 1로 조용히 바꾸는 것은 잘못된 설정을 정상 저장하는 결과다.

**수정:** 배치 요청에 한정한 엄격한 deserializer/coercion 정책 또는 원본 JSON token 타입 검증을 추가한다. 기존 다른 admin API의 전역 설정을 무심코 바꾸지 않는다. API 테스트에서 위 입력을 400으로 검증하고 원문 JSON부터 DTO까지 포함해 테스트한다.

## 3. [P2] heartbeat 항목에 phase가 없으면 400 대신 500이 된다

**위치:** Task 7 `BatchAgentService.validate` 4318~4321행.

`PHASES`는 `Set.of(...)`로 생성했는데 `item.getPhase()`의 null 검사를 하지 않고 `PHASES.contains(item.getPhase())`를 호출한다. 유효한 id와 누락/null phase를 보낸 경우 Java 17에서 `NullPointerException`이 발생한다. `BatchApiSupport`의 malformed handler 대상에도 해당하지 않으므로 의도한 `batch.request.invalid` / 400이 되지 않는다.

```json
{"observedAt":"2026-09-20T02:00:00Z","mode":"ACCEPTING","freeSlots":0,"assignments":[{"id":"BA1"}]}
```

**수정:** contains 전에 phase null을 명시적으로 거절한다. 누락·null·미지원 문자열을 각각 API 400으로 검증한다. 배열 항목 자체의 null 검사와 구분한다.

## 4. [P2] 행 잠금 대기 중 만료된 토큰도 유효하다고 판단할 수 있다

**위치:** Global Constraints의 DB `now()` 규칙, Task 3 `consumeEnrollment` 1805~1809행, Task 7 `openSession` 4251~4256행.

PostgreSQL `now()`는 트랜잭션 시작 시각이다. `@Transactional` 서비스가 만료 직전에 시작해 runner 행 잠금에서 기다리면, 잠금을 얻을 때 이미 만료됐더라도 `expires_dtm > now()`가 계속 참일 수 있다. 잠금 아래에서 credential을 재조회하는 것만으로 현재 시각 기준 만료 검사가 되지는 않는다.

**수정:** 감사 시각에는 트랜잭션 시각을 유지할 수 있지만, 잠금 획득 후 토큰 유효성 재검사에는 DB의 실제 현재 시각을 사용한다. 예를 들어 잠금을 먼저 얻은 뒤 수행하는 별도 검사에서 `clock_timestamp()`를 사용한다. 토큰 소비 UPDATE가 별도 잠금에서 기다릴 가능성도 검토한다.

**추가 테스트:** 짧은 TTL credential을 준비하고 다른 트랜잭션이 runner 행을 만료 시점까지 잠근다. 등록/세션 개설은 잠금 해제 후 401이어야 하며 토큰 소비·새 ACCESS 발급·session 증가가 없어야 한다. 현재 500ms sleep 경합 테스트는 폐기 경쟁만 검사하고 이 만료 경계를 검증하지 않는다.

이 항목은 SQL 의미에 따른 정적 검토다. 이번 셸에는 docker/docker-compose 실행 경로가 없어 DB 재현은 수행하지 못했다.

## 검증 범위 및 보충

- Java 17 + 저장소에 설치된 Jackson 3.1.5로 scalar coercion과 `Set.of(...).contains(null)`를 실행 확인했다. 재현용 파일은 git 제외 경로 `target/BatchPlanReviewProbe.java`에만 두었다.
- `BigDecimal`을 `convertValue(..., Object.class)`로 변환하는 실험에서는 정밀도 손실이 재현되지 않았다. 이를 멱등 hash 결함으로 기록하지 않는다. 후속 Program/Run JSON은 원본 JSON 파싱부터 정밀도를 보존하는 테스트가 필요하다.
- 실제 runner 등록 예시의 `credential_dir`는 현재 runner 설정 필드와 일치한다.
- Task 11의 "기존 테스트가 실패하면 보안 체인 영향"이라는 단정은 수정하는 것이 좋다. 이전 runner 검증에는 기존 일본어 메뉴 시드 실패가 있었으므로 현재 baseline과 변경 후 실패를 구분해야 한다.
- 실제 R9는 기존 결정대로 admin 후속 단계 구현 후 수행한다.
