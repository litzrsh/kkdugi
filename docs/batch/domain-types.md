# 도메인과 PostgreSQL 타입 적용 규칙

- 기준: 저장소 루트의 [domains.xml](../../domains.xml), 2026-09-20 확인.
- 원본은 Oracle 기준의 도메인 정의다. 아래는 배치 설계를 PostgreSQL 17에 적용하기 위한 매핑이며 원본 XML을 변경하지 않는다.
- XML에는 도메인 이름, `logicalDatatype`, 일부 `dataTypeSize`가 있다. Oracle 물리 타입 매핑 파일, BYTE/CHAR 길이 단위, 숫자 정밀도, timestamp 소수점 정밀도는 포함되어 있지 않다. 따라서 아래 Oracle 열은 도메인 의미를 해석한 대응 타입이며 XML에 완전한 DDL이 들어 있다는 뜻은 아니다.
- 도메인은 모델링 분류다. 이번 문서는 PostgreSQL `CREATE DOMAIN` 객체 생성을 요구하지 않는다.

## 1. 원본 도메인 매핑

| 원본 도메인 이름 | XML logicalDatatype | XML 길이 | Oracle 대응 의미 | PostgreSQL 기본 매핑 |
| --- | --- | --- | --- | --- |
| ID | LOGDT024 | 20 | VARCHAR2(20) 계열 식별자 | `varchar(20)` |
| Code | LOGDT024 | 20 | VARCHAR2(20) 계열 코드 | `varchar(20)` |
| Text field (Short) | LOGDT024 | 50 | VARCHAR2(50) 계열 짧은 문자열 | `varchar(50)` |
| Text field | LOGDT024 | 200 | VARCHAR2(200) 계열 문자열 | `varchar(200)` |
| Text field (Long) | LOGDT024 | 4000 | VARCHAR2(4000) 계열 긴 문자열 | `varchar(4000)` |
| URL | LOGDT024 | 2000 | VARCHAR2(2000) 계열 URL | `varchar(2000)` |
| Encrypted field | LOGDT024 | 2048 | VARCHAR2(2048) 계열 보호된 값의 문자열 표현 | `varchar(2048)` |
| Boolean | LOGDT025 | 1 | CHAR(1) 계열 플래그 | `char(1)`, Y/N CHECK |
| Date (string) | LOGDT025 | 8 | CHAR(8) 계열 일자 문자열 | `char(8)`, YYYYMMDD 검증 |
| Date | LOGDT007 | 미지정 | Oracle DATE, 초 단위 일시 | `timestamp(0) without time zone` |
| Timestamp | LOGDT015 | 미지정 | Oracle TIMESTAMP, 소수 초 포함 일시 | `timestamp(6) without time zone` |
| Integer | LOGDT011 | 미지정 | 정수; 원본에 물리 NUMBER 정밀도 없음 | 범위가 정해진 수량은 `integer`, 확장 기준은 아래 참조 |
| Clob | LOGDT028 | 미지정 | CLOB 대용량 문자열 | `text` |
| Blob | LOGDT029 | 미지정 | BLOB 바이너리 | `bytea` |
| Unknown | LOGDT017 | 미지정 | 미정의 도메인 | 사용하지 않음. 의미에 맞는 도메인을 먼저 선택 |

길이가 명시된 문자열은 그 값을 유지한다. 다만 PostgreSQL `varchar(n)`의 n은 문자 수이며 Oracle은 BYTE/CHAR 설정에 따라 다르다. 이 설계에서는 **문자 수**를 기준으로 삼고, byte 제한이 필요한 로그·통신 payload에는 별도의 byte 검증을 둔다. Oracle의 빈 문자열/NULL 처리와 PostgreSQL의 빈 문자열은 동일하다고 가정하지 않는다. [Oracle 타입 문서](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Data-Types.html), [PostgreSQL 문자 타입](https://www.postgresql.org/docs/17/datatype-character.html)

Oracle DATE에는 시·분·초가 있으므로 PostgreSQL `date`로 일괄 변환하면 안 된다. Timestamp의 `(6)`은 이번 PostgreSQL 설계에서 선택한 정밀도다. 순수 업무 일자만 필요하면 Date (string)을 쓰거나 별도 논리 도메인을 제안한다. [Oracle 타입 문서](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Data-Types.html), [PostgreSQL 날짜·시간 타입](https://www.postgresql.org/docs/17/datatype-datetime.html)

## 2. 배치에서 사용하는 명시적 예외·확장

아래 괄호 설명은 원본 XML에 실제로 존재하는 새 도메인 이름이 아니다. 원본 도메인과 달리 적용하는 이유를 컬럼 표에서 추적하기 위한 표기다.

| 컬럼 표의 참조 도메인 | 실제 타입 | 적용 이유 |
| --- | --- | --- |
| ID (기존 사용자 호환) | `varchar(60)` | 기존 사용자 PK와 감사자 ID가 60자이므로 `requested_by`, `actor_id`, `reg_id`, `upd_id`에서 보존. 새 배치 엔티티 PK는 ID(20) 사용 |
| Timestamp (실행 시각 확장) | `timestamptz(6)` | 서로 다른 머신·시간대 간 예정/배정/실행/lease 시각을 동일 instant로 비교 |
| Integer (범위 확장) | `bigint` | 장기 누적 세션·설정 버전, 로그 순번, OS별 종료 코드 범위를 수용 |
| Clob (JSON 확장) | `jsonb` | 구조화된 입력·스냅샷·결과는 일반 문자열보다 JSON 검증·접근이 필요 |

기존 사용자·감사 타입은 [V5 사용자 migration](../../kkdugi-admin/src/main/resources/db/migration/V5__create_user.sql)에서 확인했다. XML에 없는 60자 예외를 모든 ID에 확대하지 않는다. 감사 컬럼의 물리 이름·매핑은 [CommonMapper](../../kkdugi-admin/src/main/resources/mapper/postgres/core/models/CommonMapper.xml)를 따른다.

`timestamptz`에는 원래의 IANA 시간대 이름이 보존되지 않으므로 `timezone_id`를 별도 문자열로 유지한다. API는 offset/Z가 있는 시각을 사용하고 DB 접속 시간대는 UTC로 통일한다. 기존 `BaseModel` 감사 시각은 `timestamp(6) without time zone`으로 유지한다. [PostgreSQL 날짜·시간 타입](https://www.postgresql.org/docs/17/datatype-datetime.html)

Integer의 XML 정의만으로 Oracle 정수 전체 범위가 PostgreSQL integer와 같다고 판단하지 않는다. 이번 배치의 수량·초·시도 번호는 `integer` 범위와 업무 상한을 검증한다. 누적 순번·세대는 `bigint`로 두고 자동 증가 ID 생성과는 구별한다. 기존 Oracle 데이터를 이관하는 작업이라면 실제 범위를 조사해 `numeric(p,0)` 필요 여부를 따로 판단해야 한다. [PostgreSQL 숫자 타입](https://www.postgresql.org/docs/17/datatype-numeric.html)

JSON은 원본 `domains.xml`에 없다. `Clob (JSON 확장)` 표기는 **새 논리 JSON 도메인을 추가할 후보**라는 뜻이며 CLOB를 기계적으로 jsonb로 변환한다는 뜻이 아니다. 일반 로그는 그대로 Clob → `text`, 구조화 데이터만 `jsonb`를 사용한다. [PostgreSQL JSON 타입](https://www.postgresql.org/docs/17/datatype-json.html)

## 3. 컬럼 적용 세부 규칙

- `use_yn`, `slot_held_yn`: Boolean 도메인, `char(1)`과 `CHECK (... IN ('Y', 'N'))`. PostgreSQL native `boolean`으로 바꾸지 않는다.
- `*_id`: 새 배치 엔티티 PK/FK는 원칙적으로 ID(20). `timezone_id`는 시간대 이름, Event의 `target_id`는 복합키 문자열도 담는 대상 참조이므로 Text field(200)다.
- `assignment_key`: 암호 토큰이 아니라 SerialUtils로 생성하는 ID(20). 배정 보고 권한은 runner 인증과 소유권·세대로 검증한다.
- `request_scope`, `request_key`: Text field(200). 합성 요청 key는 200자 이내의 정규 인코딩 또는 digest로 만들고 무제한 문자열을 인덱싱하지 않는다.
- 상태·유형·실패 코드: Code(20). 실제 저장값과 enum 라벨의 분리는 기존 `CodeEnums` 규칙을 따른다.
- 버전 이름은 Text field (Short)(50), manifest revision/digest는 Text field(200). digest 표현은 SHA-256 소문자 hex 64자처럼 프로토콜에서 하나로 정하고 검증한다.
- `secret_hash`: Encrypted field(2048)를 자격증명 검증값 저장에 재사용한다. **hash를 복호화 가능한 암호문이라고 표현하지 않으며** 평문 토큰은 저장하지 않는다. hash 전용 도메인이 필요하면 후속으로 분리한다.
- `last_log_seq`: 이름에 seq가 있지만 단일 숫자가 아니라 stream별 순번 객체이므로 Clob (JSON 확장). 각 값에는 bigint 범위를 적용한다.
- JSON·text는 물리 타입만으로 업무 크기 상한이 생기지 않는다. 입력/결과/chunk 크기는 실행 계약에서 별도로 제한한다.

## 4. Workflow 및 후속 테이블 적용

후속 테이블명도 모두 `kkdugi_batch_*`를 사용한다. 아직 상세 컬럼이 없는 Workflow 개념 표에는 [공통 타입 규칙](workflow-extension.md#workflow-컬럼의-공통-타입)을 추가했다. 실제 컬럼을 정의할 때 위 도메인을 지정하고 FK는 참조 대상과 같은 물리 타입으로 맞춘다.
