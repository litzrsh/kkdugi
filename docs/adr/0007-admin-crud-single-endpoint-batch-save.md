# ADR-0007: 관리 CRUD API - 단일 엔드포인트 배치 저장, 행 단위 flat 모델, 전체 트랜잭션

- 상태: Accepted
- 날짜: 2026-09-15

## 컨텍스트

다국어 메시지 관리 화면은 그리드(엑셀형 표) 편집을 전제로 하며, 한 번의 저장
동작에 INSERT/UPDATE/DELETE가 섞여 들어온다. 이를 API로 어떻게 노출할지 정해야
한다. 두 가지 하위 결정이 필요했다.

**(1) Row 단위**: PK가 `(MSG_CD, LANG_CD)` 복합키라서 두 모형이 가능했다.
- 플랫 행: `(msgCd, langCd)` 1조합 = 1행
- 그룹 행: `msgCd` 단위 + 언어별 값을 컬럼으로 pivot한 행

**(2) 부분 실패 처리**: 한 배치 저장 요청 안에서 일부 행만 실패했을 때
- 전체 트랜잭션(all-or-nothing)
- 행별 독립 처리(성공한 행은 반영, 실패한 행만 에러로 표시)

## 결정

- **엔드포인트**: INSERT/UPDATE/DELETE를 하나의 `POST /api/admin/i18n/messages`
  엔드포인트로 통합한다. 조회(`GET`)는 별도로 둔다.
- **Row 단위**: 플랫 행 모델을 사용한다. 요청의 각 행은 `crudType`
  (`INSERT`/`UPDATE`/`DELETE`) 필드로 자신에게 적용할 연산을 명시한다.
- **부분 실패 처리**: 전체 트랜잭션으로 처리한다. 배치 내 한 행이라도 실패하면
  전체를 롤백하고, 실패한 행의 `rowIndex`/`msgCd`/`langCd`/사유를 응답에 담아
  클라이언트가 어떤 행이 문제인지 특정할 수 있게 한다.

## 근거

- 그리드 UI에서 "저장" 버튼 클릭 한 번에 여러 변경을 한 번의 API 호출로
  보내는 것은 흔한 패턴이며, CRUD를 엔드포인트 3개로 나누는 것보다 그리드
  구현(변경분 diff 계산 후 단일 전송)과 자연스럽게 맞는다.
- 플랫 행 모델은 테이블 PK와 1:1로 대응해 매퍼/검증 로직이 단순하다. 그룹
  행(pivot) 모델은 한 행 안에 언어별로 서로 다른 연산(어떤 언어는 추가, 어떤
  언어는 삭제)이 섞일 수 있어 모델과 검증 로직이 복잡해진다.
- 전체 트랜잭션은 그리드가 "일부만 저장됨" 같은 애매한 중간 상태를 갖지
  않도록 보장한다. 관리자 메시지 수정은 트래픽이 적고 배치 크기가 작아
  트랜잭션 범위가 커지는 데 따르는 성능 부담이 미미하다.

## 결과

- `app.admin.i18n.CrudType` enum(`INSERT`/`UPDATE`/`DELETE`)과
  `MessageRowCommand`/`MessageRowResult`가 앱 계층 모델로, `api.admin.i18n`의
  `MessageSaveRequest`/`MessageRowRequest`/`MessageSaveResponse`/
  `MessageSaveErrorResponse`가 API 계층 모델로 분리된다. 컨트롤러가 두 모델
  사이 변환을 담당한다.
- `MessageAdminService.saveAll(List<MessageRowCommand>)`가 단일
  `@Transactional` 메서드로 전체 배치를 처리한다.
- 캐시 갱신은 이 트랜잭션의 커밋 이후에만 수행한다
  ([ADR-0003](0003-i18n-cache-strategy-in-memory-evict-on-save.md) 참고, 이번
  결정으로 커밋 후 갱신이 필요함이 명확해져 ADR-0003에 보강 사항을 추가함).
- 동시 편집 충돌 방지(낙관적 잠금)는 이번 결정 범위에 포함하지 않는다.
  `UPD_DTM` 마지막 저장이 우선한다(last-write-wins). 문제가 실제로 발생하면
  후속 ADR로 재검토한다.
