# ADR-0005: Vue 기반 Popup·Dialog 통합과 모바일 대응

> 2026-09-16: API 계약과 실제 퍼블리싱 구조는 [ADR-0006](0006-admin-api-contract-and-publishing.md) 및 계획 06의 현재 상태를 우선합니다. 아래 내용은 최초 설계 기록입니다.

- 날짜: 2026-09-15
- 상태: 설계 채택, 구현 미착수
- 사용자 요구: Popup과 Dialog를 모두 Vue 기준으로 통합하고 모바일을 고려해 디자인한다.
- 확장: [ADR-0004](0004-thymeleaf-vue-sfc-runtime.md), [모바일 상세 기준](../plan/05-mobile-interaction-specification.md)

## 배경과 분석

Kluvo web의 `src/main/resources/static/scripts/libs/kluvo-popup.js`는 alert/confirm/custom HTML을 문자열로 만들고 DOM 삽입·이벤트·종료를 직접 관리한다. admin의 `src/main/resources/static/scripts/libs/kluvo-dialog.mjs`는 Vue SFC 본문과 직접 생성한 native dialog shell을 사용하며 alert/confirm wrapper도 제공한다. 역할이 중복되므로 하나로 통합할 수 있다. 단, 기존 Dialog도 shell 전체가 Vue인 것은 아니다.

## 결정

1. kkdugi의 공개 API 명칭은 `dialog` 하나다. Popup은 사용자 관점의 표현일 뿐 별도 구현·클래스·런타임을 만들지 않는다. alert, confirm, 업무 편집은 동일 host와 shell을 공유한다.
2. shell·제목·본문·버튼·오류·로딩 상태까지 Vue 컴포넌트로 만든다. `DialogHost.vue`가 `DialogShell.vue`와 등록된 본문 SFC를 렌더링한다. native `<dialog>`는 Vue 템플릿 안에서 사용하며 showModal/close·초점 제어는 Vue 생명주기에 연결한다.
3. shell이 시작할 때 OverlayRoot Vue app 하나를 만들고, 화면과 같은 runtime·moduleCache·서비스를 주입한다. 개별 dialog마다 createApp 하지 않는다. 호출된 본문은 해당 Vue tree 안에서 생성·해제된다. 화면 app과 overlay app의 provide는 자동 공유되지 않으므로 공통 service factory가 명시적으로 주입한다.
4. 자유 HTML 문자열, `open(template)`·DOM onopen callback은 제공하지 않는다. 등록된 componentId와 props만 받는다. 안내 문구도 MessageBody.vue로 표시한다.
5. 부모 화면 ownerId, 미저장 취소 보호, 저장 실패 입력 보존, 중복 submit 방지는 하나의 서비스가 관리한다. 화면 종료 시 해당 owner의 열린 항목과 대기 항목도 정리한다.
6. 모바일은 같은 컴포넌트·상태·결과 계약을 사용하고 presentation만 변경한다. 짧은 알림/확인은 중앙 dialog, 편집·매핑은 768px 미만에서 전체 화면 dialog로 표시한다. 별도 모바일 popup 구현은 만들지 않는다.

## 공개 API 계약 (설계용)

```text
dialog.alert({titleKey, messageKey, args, ownerId})
  → Promise<void>
dialog.confirm({titleKey, messageKey, args, ownerId, danger})
  → Promise<boolean>                 // 취소·owner 종료는 false
dialog.open({componentId, titleKey, props, ownerId,
             onSubmit, beforeCancel, presentation: 'auto'})
  → Promise<{status:'submitted', value:T}
          | {status:'cancelled', reason:'user'|'owner-disposed'}>
```

기존 계획의 `open()` 취소 시 null 규칙은 위 구별 가능한 결과 객체로 대체한다. 업무 결과 false/null과 취소를 혼동하지 않기 위함이다. 편의 API alert/confirm의 결과는 유지한다. 로딩·mount 실패는 `DialogLoadError`로 reject하고 공통 오류 안내와 호출자 재시도를 제공한다. 정상 취소와 기술 실패를 같은 결과로 숨기지 않는다.

본문은 `inject('dialogContext')`로 submit/cancel/오류 상태에 접근한다. 본문 검증 함수를 host에 등록해 공통 제출 버튼과 폼 Enter 제출이 동일 경로를 타게 한다. textarea Enter는 줄바꿈이다. onSubmit은 host만 실행하고 성공한 경우에만 닫는다. 초안 편집은 ‘변경 적용’, API 저장은 ‘저장’, 위험 실행은 ‘영구 삭제’처럼 실제 행동으로 라벨을 정한다.

## 상태·수명주기

- 상태: queued → loading → open → submitting → settled. 제출 실패는 open으로 돌아가며 값·오류를 유지한다. 호출 Promise는 한 번만 settle한다.
- 독립된 dialog 호출은 FIFO로 대기시킨다. 활성 편집창 안에서 호출하는 확인창은 부모를 정지시키고 최상위 자식 하나만 허용한다. 허용 자식은 확인/알림이며 업무 편집창을 중첩하지 않는다.
- 미저장 버림 확인은 같은 shell 안의 확인 단계로 전환한다. 대기열 뒤로 들어가 부모가 영원히 기다리는 상황을 방지한다. ‘계속 편집’은 폼·초점·스크롤을 복원한다.
- submitting 동안 취소·Esc·배경 닫기와 정상 owner 전환을 막는다. 강제 owner 종료 시 UI는 정리하되 서버 요청이 취소됐다고 간주하지 않는다. 재진입 시 저장 결과를 조회한다.
- 지연 로딩 중 owner가 없어지면 mount하지 않고 owner-disposed로 종료한다. 실패한 컴포넌트 로딩 Promise는 cache에서 제거한다.
- focus trap·배경 비활성·닫힌 뒤 호출 지점 초점 복귀를 유지한다. 호출 버튼이 사라졌으면 화면 제목이나 다음 적절한 지점으로 복귀한다. 긴 모바일 편집 폼 진입 시 입력에 자동 초점을 주어 키보드를 강제로 띄우지 않는다.
- 배경 터치·아래로 스와이프는 편집창 닫기 동작으로 사용하지 않는다. 취소/Esc는 dirty 가드를 통한다. 안내창 닫기는 확인 버튼과 Esc로 가능하다.

## 모바일·디자인 결정

Kluvo의 Tailwind 클래스·색상·크기를 복사하지 않는다. Bulma 기반 kkdugi 라이트 토큰을 사용한다. 전체 화면 편집에서도 제목·닫기·오류·하단 동작의 위치와 표현은 공통 shell이 소유한다. 모바일 상세 치수·그리드 대체 편집·키보드·회전 검수는 계획 05를 따른다.

## 영향 및 검증

기존 계획의 Dialog 클래스 직접 DOM 생성·개별 app.unmount 제안은 DialogHost의 Vue 본문 해제로 구체화한다. 앱 전체 종료 때만 OverlayRoot.unmount를 실행한다. PageHost는 owner 정리를 요청하고 페이지 app을 해제한다.

후속 검증: alert/confirm/open의 단일 렌더러 사용, 결과/오류 구분, 취소 보호, 독립 호출 큐·자식 확인, 저장 중 이탈, owner 종료 경쟁, 포커스 복귀, 모바일 전체 화면, 스크롤 잠금 해제, 가상 키보드 노출. 이번에는 문서만 작성했다.
