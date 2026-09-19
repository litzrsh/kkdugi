# ADR-0016: 사용자용(app.<기능>)과 관리자용(app.admin.<기능>) 기능 분리

- 상태: 채택 (2026-09-19)
- 관련: [ADR-0011](0011-api-define-admin-contract-and-record-models.md), [ADR-0014](0014-revert-to-base-model-inheritance.md), 설계 스펙 `docs/superpowers/specs/2026-09-19-app-api-structure-refactor-design.md`

## 배경

기능별로 별개 라이브러리 프로젝트로 떼어낼 계획이 있다. 기존에는 DB 행 모델·mapper가 `core.<기능>`에, 관리자 서비스가 `app.admin.<기능>`에 있어서 `app.<기능>` 계층이 없었고, 관리자 서비스가 read/write 구분 없이 core mapper를 직접 썼다.

## 결정

1. 사용자에게 보이는 데이터와 관리자 기능이 필요로 하는 데이터는 다르므로 모델·mapper·서비스를 분리해 유지한다: `app.<기능>`(사용자용)과 `app.admin.<기능>`(관리자용).
2. 의존 방향은 `api → app → core`. `app.admin.*`와 `app.*`는 서로 import하지 않는다.
3. `app.admin.<기능>`은 `Admin*` 접두사를 붙인 mapper/서비스/컨트롤러/모델을 가진다. read+write 쿼리는 `Admin<기능>Mapper` 하나에 통합한다.
4. API: 사용자용 `/api/v1.0/{menu,code}`, 관리자용 `/api/v1.0/admin/*`. 세션 메뉴는 `/api/v1.0/session/menu`에서 `/api/v1.0/menu`로 이전(옛 경로 별칭 없음).
5. i18n은 Spring `MessageSource` 인프라(`KkdugiMessageSource`, `I18nMessageMapper`)를 core에 두고 관리자 쓰기만 `app.admin.i18n`으로 분리한다.

## 결과

- `app.<기능>`을 admin 없이 라이브러리로 분리할 수 있다.
- 같은 테이블을 읽는 쿼리가 사용자용/관리자용 mapper에 각각 존재한다(의도된 중복).
