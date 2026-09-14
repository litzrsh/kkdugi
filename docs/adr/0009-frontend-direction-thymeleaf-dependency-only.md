# ADR-0009: 프론트엔드 방향 - Thymeleaf 의존성만 지금 추가, 나머지는 TODO

- 상태: Accepted
- 날짜: 2026-09-15

## 컨텍스트

프로젝트 오너가 향후 프론트엔드 방향을 다음과 같이 제시했다.

1. Thymeleaf (SSR)
2. Vue — 관련 내용은 TODO로 게시
3. CSS는 Custom CSS 전제
4. Font는 Pretendard (추후 제공)
5. 아이콘팩은 line-awesome (추후 제공)
6. 그리드는 ag-grid (추후 제공)

이번 작업(다국어 시스템)은 애초에 "UI는 구상하지 말고 기능에 집중"하기로
범위를 잡았다([i18n-system-design.md](../i18n-system-design.md) 참고). 이후
오너가 "Thymeleaf 의존성 추가하는 것을 제외하고 모두 TODO로 만들어"라고
범위를 다시 명확히 했다.

## 결정

- **지금 결정/적용하는 것**: `spring-boot-starter-thymeleaf` 의존성을
  `pom.xml`에 추가한다. 단, 이번 다국어 시스템 작업에서 실제 Thymeleaf
  템플릿(화면)은 만들지 않는다 — 의존성만 미리 준비해둔다.
- **TODO로 남기는 것** (구체적 설계/구현은 이후 별도로 진행):
  - Vue 연동 방식 (Thymeleaf와의 통합 지점, 빌드 파이프라인 등)
  - CSS 전략 (Custom CSS 전제 여부 포함, 프레임워크 사용 여부 등)
  - 폰트: Pretendard 적용 방식
  - 아이콘팩: line-awesome 적용 방식
  - 그리드 라이브러리: ag-grid 적용 방식, 라이선스(Community/Enterprise) 확인

## 근거

- 오너가 향후 방향성은 공유하되, 지금 당장 화면을 설계/구현하지 않기로
  범위를 재확인했다. Thymeleaf 의존성만 미리 넣어두면 이후 화면 작업을
  시작할 때 별도 셋업 없이 바로 템플릿을 추가할 수 있어, 최소한의 선반영만
  하고 나머지는 실제 화면 작업 시점으로 미룬다(YAGNI).

## 참고: 관리 API 모델과의 연관성

[ADR-0007](0007-admin-crud-single-endpoint-batch-save.md)에서 다국어 관리
API를 "그리드 저장" 전제로 설계(flat 행 + `crudType` + 단일 배치 저장
엔드포인트)했는데, 이는 ag-grid의 일반적인 저장 패턴(행 단위 add/update/remove
트랜잭션)과 자연스럽게 맞는다. ag-grid 적용 자체는 TODO지만, 이미 결정된 API
모델이 ag-grid 연동을 가로막지 않는다는 점만 기록해둔다.

## 결과

- `pom.xml`에 `spring-boot-starter-thymeleaf`를 추가한다.
- `resources/templates/` 디렉터리나 실제 화면은 이번 범위에서 만들지 않는다.
- 위 TODO 항목들은 각각 실제로 착수할 때 별도 브레인스토밍/ADR을 통해
  구체화한다.
