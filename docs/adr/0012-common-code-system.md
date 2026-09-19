# ADR-0012: 공통코드 시스템 구현 — 채번 방식, 계층 저장/삭제, 검색 범위 확정

- 상태: Accepted (1번 채번 방식은 addendum으로 교체됨 — 아래 참고)
- 날짜: 2026-09-16

## 컨텍스트

`docs/api-define-admin.md` 1절(공통코드관리)과 ERD의 `KKDUGI_CODE_BASE`/
`KKDUGI_CODE_LANG`/`KKDUGI_SERIAL_BASE`를 기반으로 공통코드 관리 API를
구현했다([ADR-0011](0011-api-define-admin-contract-and-record-models.md)의
패키지 규약·record 규칙·`Page<T>` 재사용을 그대로 따른다). 스펙과 ERD 코멘트가
알고리즘 수준까지 명시하지 않은 지점이 여러 곳 있어, 아래처럼 확정하고
기록한다.

## 결정

### 1. ID 채번 (`kkdugi.core.serial`) — **아래 addendum으로 교체됨**

~~api-define-admin.md 상단 "공통사항"에 "신규 등록인 경우 id는 빈 값으로
Request하며 서버에서 채번 실시"라고 되어 있고, 예시 ID들(`C2026091517460002`,
`M2026091517570001`/`...0002`)의 형태가 `{접두어}{yyyyMMddHHmmss}{2자리
순번}`과 일치한다(같은 요청에서 만들어진 두 메뉴 예시가 같은 초(second)
타임스탬프에 순번만 01/02로 다르다).~~

~~이를 알고리즘으로 확정한다:~~
~~- `kkdugi_serial_base(serial_id PK, seq_base, seq_val, upd_dtm)` 한 행이
  "접두어 + 초 단위 타임스탬프" 하나에 대응한다. `serial_id` =
  `{prefix}{yyyyMMddHHmmss}`, `seq_base` = `{prefix}`(참고용, PK 아님).~~
~~- 채번은 `INSERT ... ON CONFLICT (serial_id) DO UPDATE SET seq_val = seq_val
  + 1 ... RETURNING seq_val` 한 문장으로 원자적으로 처리한다
  (`kkdugi.core.serial.service.SerialIdGenerator.next(prefix)`).~~
~~- 최종 ID = `serial_id + LPAD(seq_val, 2, '0')`. 같은 초 안에 같은 접두어로
  여러 번 호출되면(예: 배치 저장 한 번에 신규 코드 여러 개) 순번이 이어서
  증가한다.~~

이 초안은 "행 하나 = 접두어+초"로 설계했었는데, 오너가 2026-09-16에
`core.serial`을 직접 재작성하면서 "행 하나 = 채번 대상(id) 하나, 버킷은
행 안의 컬럼으로 추적"하는 다른(더 유연한) 방식으로 바꿨다 — 아래
addendum 참고. `core.serial`이 i18n/code 어느 한쪽에 속하지 않는 진짜
공용 인프라라 `core.i18n`/`core.code`와 나란히 둔다는 결정, 그리고 이후
메뉴(`M`)/권한(`A`)/사용자(`U`) 화면도 이를 재사용한다는 결정은 그대로
유효하다.

### 2. 계층 모델과 `code_path`/`code_lvl`

- root 코드는 `code_parent_id = NULL`, `code_lvl = 0`. 자식은 부모의
  `code_lvl + 1`.
- `code_path` = 부모의 `code_path` + `"/" + code`(root는 `"/" + code`) —
  ERD 코멘트의 예시(`/SYS/USER/STAT`)와 일치.
- Postgres의 일반 `UNIQUE(code_parent_id, code_val)` 제약은 NULL끼리
  서로 다른 값으로 취급되어 root 코드끼리는 `code_val` 중복을 막지
  못한다 — `code_val`에 대한 부분 유니크 인덱스(`WHERE code_parent_id IS
  NULL`)를 추가로 둬서 root 코드 중복도 막는다(`V3__create_code.sql`).

### 3. `code`(경로 세그먼트)와 `parentId`는 생성 후 수정 불가

`code` 값이 바뀌면 그 노드의 `code_path`뿐 아니라 모든 하위 노드의
`code_path`(그리고 `code_lvl`은 아니지만 path는)도 다시 계산해야 한다.
`parentId`가 바뀌는 경우(트리 이동)도 마찬가지로 하위 전체의 `code_lvl`/
`code_path` 재계산이 필요하다. 이번 범위에서는 이 캐스케이드 재계산을
구현하지 않고, **`update` 요청에 `code` 또는 `parentId`가 기존 값과
다르게 들어오면 409로 거부**한다 — 경로를 바꾸고 싶으면 삭제 후
재등록한다(삭제는 하위까지 캐스케이드되므로 그 자체로는 안전하다).
`extra1~5`/`use`/`sort`/`locale`은 정상적으로 수정 가능하다.

이 제약은 명세에 없는, 이번 ADR의 의도적 축소다 — 트리 재구성(이동/이름
변경) 요구가 실제로 생기면 후속 ADR로 캐스케이드 재계산을 추가한다.

### 4. 삭제는 하위 전체를 함께 삭제한다

api-define-admin.md 1.2절 "삭제 시, 하위 코드도 모두 삭제"를 그대로
구현한다: 대상의 `code_path`를 기준으로 자기 자신과
`code_path LIKE '{path}/%'`인 모든 하위 코드를 함께 찾아
(`CodeBaseMapper.findSelfAndDescendants`) `kkdugi_code_lang` → `kkdugi_code_base`
순으로 삭제한다(FK 순서).

### 5. 조회는 항상 "현재 parentId의 직접 자식"만 보여준다

`kkdugi-design/docs/adr/0002-grid-batch-and-hierarchy.md`("공통코드는 현재
부모의 직접 자식만 표시하고 breadcrumb·상위 이동·하위 목록 버튼으로
탐색한다")를 그대로 따른다: `parentId`가 오면 그 자식만, 안 오면(=null)
root 코드만 반환한다. `path`/`code`/`name`/`use` 필터는 전체 트리를
가로지르는 전역 검색이 아니라, **그 형제(sibling) 범위 안에서** 추가로
좁히는 조건이다. `name` 조건은 `kkdugi_code_lang.code_nm`에 대한
`EXISTS` 서브쿼리로 처리한다(어떤 언어든 이름이 일치하면 매치 — i18n의
`message` 검색과 동일한 패턴).

### 6. `code` 값 자체는 형식 검증을 하지 않는다 — **아래 2026-09-18 addendum으로 교체됨**

~~`MessageCode`처럼 정규식 패턴을 강제하는 근거(ERD 코멘트, api-define-admin.md)가
공통코드 `code` 값에는 없다 — 비어있지 않은지만 검증한다. 언젠가 형식
규칙이 필요해지면 `MessageCode`와 같은 패턴으로 `core.code.models`에
검증기를 추가한다.~~

## 결과

- 패키지: `core.serial.{mapper,service}`, `core.code.{models,mapper}`,
  `app.admin.code.{models,exceptions,service}`, `api.admin.code`(평평).
- 마이그레이션: `V2__create_serial_base.sql`, `V3__create_code.sql`
  (+ `V4__create_fn_get_serial.sql`, 아래 addendum).
- 엔드포인트: `POST /api/v1.0/admin/code`(조회, 부모 자식 단위 페이징),
  `POST /api/v1.0/admin/code/persist`(insert/update/delete). 응답/오류
  형태는 [ADR-0011](0011-api-define-admin-contract-and-record-models.md)의
  메시지 구현과 동일한 패턴(`Page<CodeContent>`, persist는 빈 200,
  `{ errors: [{ id, code, reason }] }`).

## 미해결 이슈

- `code`/`parentId` 변경(트리 재구성)은 구현하지 않았다 — 위 3번 참고.
- 메뉴/권한/사용자 3개 화면은 아직 미구현. `SerialUtils`/`SerialConfig`와
  `core.models.Page<T>`, 패키지 규약을 그대로 재사용해 이어서 구현한다.

## Addendum (2026-09-16): 채번을 DB function 기반으로 재설계

동시성이 필요하고 여러 애플리케이션 인스턴스가 뜰 수 있다는 이유로, 오너가
채번 로직을 Java 애플리케이션 레벨에서 **Postgres 함수**로 옮겼다. 위 1번의
"초 단위 타임스탬프 + 2자리 순번, `SerialIdGenerator`" 설계는 전량
폐기되고 다음으로 교체됐다.

**설계**
- `SerialConfig`(`core.serial`, 인터페이스): 채번 대상마다 하나씩 구현한다.
  `getId()`(카테고리 식별자, 예: `"KKDUGI_CODE"`), `getDuration()`(버킷
  길이, 기본 1분), `getDateFormat()`(버킷 키 포맷, 기본 `"yyyyMMddHHmm"`),
  `getLimit()`(버킷 하나당 최대 개수, 기본 9999), `getValueFormatter()`/
  `format(date, value)`(최종 ID 문자열 조립). `CodeAdminService`가 익명
  구현(`id="KKDUGI_CODE"`, 포맷 `"C%s%04d"`)을 하나 갖고 있다.
- `fn_get_serial(p_id, p_key, p_size) RETURNS BIGINT`(Postgres 함수,
  `V4__create_fn_get_serial.sql`): `kkdugi_serial_base`에 `p_id`당 한
  행을 두고, `INSERT ... ON CONFLICT (serial_id) DO UPDATE ...` **한
  문장**으로 "`p_key`(버킷)가 저장된 값보다 최근이면 `seq_val`을
  `p_size`로 리셋, 아니면 `seq_val += p_size`"를 원자적으로 처리하고
  갱신된 `seq_val`을 반환한다. 이 UPSERT 한 문장이 Postgres 행 잠금 하에
  실행되므로, 여러 애플리케이션 인스턴스가 동시에 호출해도 경쟁 상태 없이
  정합성이 보장된다 — 애플리케이션 레벨의 "조회 후 갱신"과 근본적으로
  다른 점이다.
- `SerialMapper.upsertAndGetNext(id, key, size)`는 `SELECT
  fn_get_serial(#{id}, #{key}, #{size})`를 호출하는 얇은 래퍼다.
- `SerialService`(`core.serial.service`, `@Service`, `InitializingBean`):
  `nextSerials(config, size)`가 `size`개를 한 번에 예약한다. 한 버킷의
  잔여 한도(`getLimit()`)를 넘으면 다음 버킷(`getDuration()`만큼 전진한
  시각의 `getDateFormat()` 키)으로 넘어가며 나눠 채번한다.
- `SerialUtils`(`core.util`): 정적 서비스 로케이터. `SerialService`가
  `afterPropertiesSet()`에서 자신을 등록해두면, 이후 어디서든(DI 없이)
  `SerialUtils.next(config)`로 호출할 수 있다. `CodeAdminService`는
  생성자로 서비스를 주입받는 대신 이 정적 호출로 전환했다.

**체크 과정에서 발견하고 고친 버그 (오너 요청으로 리뷰함)**
- `SerialService.nextSerials`의 버킷-오버플로우 분할 계산이
  `Math.min(limit,last) - size`(루프 안에서는 항상 `limit - size`)를
  시작점으로 썼다. 실제 시작점은 "이번 호출 전 카운터 값"인 `last - size`
  여야 한다 — 그렇지 않으면 직전 호출이 이미 발급한 번호와 겹치는 범위를
  다시 내어준다. `remaining`을 별도로 추적해 `prev = last - remaining`
  기준으로 고쳤다.
- `size -= queue.size()`가 누적 큐 크기를 뺐다. 버킷을 3개 이상 걸치는
  요청에서 남은 수량이 과도하게 줄어 마지막 구간이 빠질 수 있었다 —
  이번 반복에서 실제로 소진한 개수(`fitCount`)만 빼도록 고쳤다.
- `SerialConfig.getValueFormatter()` 기본값이 `"%s04d"`(`%` 누락)였다 —
  `"%s%04d"`로 고쳤다. `CodeAdminService`는 자체 포맷(`"C%s%04d"`)을 쓰고
  있어 이 버그의 영향을 받지 않았지만, 기본값을 그대로 쓰는 다음 화면
  (메뉴/권한/사용자)이 나오면 바로 걸렸을 것이다.
- `SerialService`에 `@Service`가 빠져 있었다 — Spring이 빈으로 등록하지
  않으므로 `SerialUtils`가 항상 null을 반환하고, `CodeAdminService`의
  신규 코드 등록이 전부 `code_id = null`로 실패했을 것이다. 애너테이션을
  추가했다.

`kkdugi_serial_base` 테이블 스키마(V2, `serial_id`/`seq_base`/`seq_val`/
`upd_dtm`)는 바뀌지 않았다 — 다만 의미가 바뀌었다: `serial_id`는 이제
"접두어+타임스탬프"가 아니라 **채번 대상 `id`(카테고리) 그 자체**이고,
`seq_base`가 "현재 버킷 키"를, `seq_val`이 "그 버킷 안의 카운터"를 담는다.

메뉴/권한/사용자 화면을 구현할 때는 `SerialIdGenerator`(폐기됨)가 아니라
`SerialConfig` 구현 + `SerialUtils.next(config)`를 재사용한다.

## Addendum (2026-09-18): `code` 값 형식 검증 추가, 에러 응답을 단일 메시지로 단순화

### `code` 값 형식 검증 (6번 미해결 이슈 해소)

오너가 `code` 값 작성 규칙을 확정: **영문 대문자 + 숫자**, 특수기호는
`_`만 허용하되 **`_`로 시작하거나 끝날 수 없다**. `MessageCode`와 같은
패턴으로 `core.code.models.CodeValue`(정규식 `^[A-Z0-9]+(_[A-Z0-9]+)*$`)를
추가하고, `CodeAdminService.validate()`의 insert 검증에서 이 검증기로
`code` 필수 여부와 형식을 한 번에 확인한다(빈 문자열은 이 정규식 자체가
거부하므로 별도의 blank 체크가 필요 없어졌다). update/delete는 `code`를
받지 않으므로(이미 불변) 대상이 아니다.

기존 테스트 픽스처가 소문자(`test_root` 등)를 쓰고 있어서 전부 대문자로
바꿨다 — 이 정규식이 처음으로 강제되는 시점이라 어쩔 수 없는 변경이다.

### 에러 응답을 `{ errors: [{id, code, reason}] }`에서 단일 메시지로 단순화

오너 방침: "오류가 발생한 경우 어떤 오류가 발생했는지만 명확하게 짚어내면
됨 — 어디서/무엇 때문에 발생했는지는 서버 사이드 로그에만 남긴다." 즉
클라이언트 응답에는 항목별 `id`/`code`/구체적 사유를 실어 보낼 필요가
없다. 같은 방침이 메시지 관리에도 적용됐다 —
[ADR-0011의 같은 날짜 addendum](0011-api-define-admin-contract-and-record-models.md#addendum-2026-09-18-에러-응답을-단일-메시지로-단순화-위-137번-미해결-이슈-확정)
참고.

- `CodeValidationException`/`CodeConflictException`은 이제 `List<CodeError>`
  대신 메시지 코드 문자열 하나(`String code`)만 들고 있다. `CodeError`/
  `CodeErrorResponse` 클래스는 삭제했다.
- `CodeAdminController`의 두 `@ExceptionHandler`는 이제
  `kkdugi.core.exceptions.ExceptionMessage`(세션/인증 쪽에서 이미 쓰던
  공통 바디 타입 — `{code, message}`)를 반환한다. 다만 `RestfulException`/
  `RestfulExceptionAdvice`(checked exception + 전역 `@RestControllerAdvice`)
  체계로 갈아타지는 않았다 — 그러려면 `persist`/`search`가 checked
  exception을 던지도록 시그니처를 바꿔야 하고 `@Transactional`에
  `rollbackFor`를 추가해야 하는 등 파급 범위가 커서, 지금은 응답 **바디
  형태만** 공통 타입으로 맞추고 예외 클래스 자체와 컨트롤러 로컬
  `@ExceptionHandler` 구조는 그대로 뒀다. 전역 체계로의 완전한 이관은
  별도 결정 사항으로 남긴다.
- `CodeAdminService`는 도메인별 메시지 코드 상수(`ERR_MALFORMED_REQUEST`,
  `ERR_INVALID_FORMAT`, `ERR_LOCALE_REQUIRED`, `ERR_DUPLICATE`,
  `ERR_NOT_FOUND`, `ERR_IMMUTABLE`)를 노출하고, 예외를 던지기 직전
  SLF4J `log.warn(...)`으로 `id`/`code`/`parentId` 등 구체적 맥락을
  남긴다. `messages.properties`/`messages_ko_KR.properties`/
  `messages_en_US.properties`에 각 코드의 기본 문구를 등록했다(DB
  `i18n_message`에 아직 등록 전이어도 `useCodeAsDefaultMessage`가 아니라
  이 fallback 번들이 먼저 잡힌다).
- `validate()`가 항목별 오류를 리스트로 모으던 방식(`List<CodeError>`
  누적)에서 **첫 번째로 발견한 위반에서 즉시 던지는 방식**으로 바뀌었다 —
  한 번에 여러 오류를 구조적으로 나열할 필요가 없어졌기 때문에 자연스럽게
  단순해졌다.
- [`docs/api/common-code.md`](../api/common-code.md)의 400/409 에러
  응답 형식 절을 이 문서에 맞춰 갱신해야 한다.
