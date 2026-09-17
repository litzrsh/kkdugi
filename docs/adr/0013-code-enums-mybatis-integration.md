# ADR-0013: 코드성 enum(`CodeEnums`)과 MyBatis 자동 변환

- 상태: Accepted
- 날짜: 2026-09-17

## 컨텍스트

오너가 `kkdugi.core.enums.CodeEnums` 인터페이스와 첫 구현체
`UserStatus`(사용자 상태: 대기/정상/휴면/탈퇴/정지)를 작성하던 중
`CodeEnums.fromCode(Class, String)`가 미완성 스텁(`return null`)으로
남아 있었다. 완성해달라는 요청과 함께, "MyBatis에도 연결해서
typeHandler를 일일히 지정하지 않고 바로바로 convert 되었으면 좋겠다"는
추가 요구가 있었다.

## 결정

### 1. `CodeEnums` — 코드값 + i18n 라벨을 함께 갖는 enum의 공통 계약

```java
public interface CodeEnums {
    String getCode();       // DB에 저장되는 짧은 코드 (예: "10")
    String getLabelCode();  // i18n 메시지 코드 (예: "user.status.10")

    default String getLabel() {
        return MessageUtils.getMessage(getLabelCode());
    }

    static <E extends CodeEnums> E fromCode(Class<E> clazz, String code) {
        E[] constants = clazz.getEnumConstants();
        for (E constant : constants) {
            if (constant.getCode().equals(code)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(...); // 못 찾으면 즉시 실패
    }
}
```

- `getLabelCode()`는 기존 [메시지 코드 규칙](../i18n-system-design.md#메시지-코드-규칙)을
  따른다(`user.status.10`처럼 3구간).
- `fromCode`는 못 찾으면 `null`이 아니라 `IllegalArgumentException`을
  던진다 — 잘못된/알 수 없는 코드가 조용히 `null`로 흘러가 NPE로
  터지기보다, 원인이 분명한 지점에서 바로 실패하는 편이 낫다고 판단했다.
  이 프로젝트의 다른 검증 실패(`MessageCode.validate` 등)도 같은 방식이다.
- **버그 수정**: `UserStatus.fromCode(String)`이 내부에서 `fromCode(UserStatus.class,
  code)`를 인터페이스명 없이 호출하고 있었는데, **인터페이스의 static
  메서드는 구현 클래스에 상속되지 않으므로** 컴파일이 안 됐다(`CodeEnums`가
  아직 미완성이라 이 문제가 이제야 드러났다). `CodeEnums.fromCode(...)`로
  인터페이스명을 붙이도록 고쳤다.

### 2. 패키지 위치 — `kkdugi.core.enums` (기능에 종속되지 않는 공용 인프라)

`UserStatus`는 개념적으로는 "사용자" 기능에 속하지만, 상태값 자체는 여러
기능(세션, 권한 화면의 사용자 목록 등)이 공유해서 참조할 수 있는 값이라
`app.admin.user.models` 같은 기능별 패키지가 아니라 `core.models`/
`core.util`/`core.serial`과 나란히 `core.enums`에 둔다. 자세한 배치
규칙은 [공통 규약 8번](../conventions/common-base-model.md#8-코드성-enum은-codeenums를-구현하고-kkdugicoreenums에-둔다)에
정리했다.

### 3. MyBatis 연동 — 컬럼마다 `typeHandler=`를 지정하지 않는다

`kkdugi.core.enums.CodeEnumTypeHandler<E extends Enum<E> & CodeEnums>`를
새로 만들었다 — `BaseTypeHandler<E>`를 상속해 `getCode()` 문자열로
쓰고, `CodeEnums.fromCode(type, code)`로 복원한다. 이 핸들러 하나를
`application.yml`에 다음처럼 **한 번만** 등록한다:

```yaml
mybatis:
  configuration:
    default-enum-type-handler: kkdugi.core.enums.CodeEnumTypeHandler
```

MyBatis는 `default-enum-type-handler`로 등록된 핸들러를, 더 구체적인
핸들러가 지정되지 않은 **모든 enum 타입**에 기본으로 적용한다(enum
타입마다 `Class<E>`를 받는 생성자로 새 인스턴스를 만든다 — MyBatis 내장
`EnumTypeHandler`와 동일한 방식). 그 결과 `CodeEnums`를 구현하는 어떤
enum이든, 결과 매핑(resultMap)이나 파라미터 바인딩에 `typeHandler=`를
따로 적을 필요가 없다.

**대가**: 이 설정은 프로젝트의 *모든* enum에 기본 적용된다. `CodeEnums`를
구현하지 않은 순수 enum을 그대로 DB 컬럼에 매핑하려 하면
`CodeEnumTypeHandler`가 `getCode()`를 호출하려다 실패한다. 따라서 이
프로젝트의 규약으로, **DB 컬럼에 매핑되는 코드성 enum은 반드시
`CodeEnums`를 구현해야 한다**고 못박는다(공통 규약 8번에 명시).

## 근거

- `typeHandler=`를 컬럼마다 반복해서 적는 것은 실수하기 쉽고(하나라도
  빠뜨리면 그 컬럼만 기본 `EnumTypeHandler`로 떨어져 `code` 대신
  `name()`이 저장되는 조용한 버그가 난다), MyBatis가 이미 "기본 enum
  핸들러"라는 전역 등록 지점을 제공하므로 그것을 쓰는 게 더 안전하다.
- `fromCode`가 예외를 던지게 한 것은 이 프로젝트 전반의 "잘못된 입력은
  가능한 한 이른 지점에서 명확하게 실패시킨다" 관례(`MessageCode.validate`,
  각 `*ConflictException`/`*ValidationException`)와 일치시키기 위함이다.

## 결과

- `kkdugi.core.enums.CodeEnums`(완성), `CodeEnumTypeHandler`(신규),
  `UserStatus`(컴파일 버그 수정 — 인터페이스 static 메서드 호출).
- `application.yml`에 `mybatis.configuration.default-enum-type-handler` 추가.
- 테스트: `UserStatusTest`(`fromCode` 왕복/미존재 코드 예외/라벨 폴백),
  `CodeEnumTypeHandlerTest`(Mockito로 `PreparedStatement`/`ResultSet`
  모킹, 코드↔enum 변환 검증) — 둘 다 실행해서 통과 확인함(2026-09-17
  테스트 정책 전환 이후 정책대로).
- [공통 규약](../conventions/common-base-model.md)에 8번 섹션과 체크리스트
  항목을 추가했다.

## 미해결 이슈

- `CodeEnumTypeHandler`가 실제 DB 컬럼(예: `KKDUGI_USER_BASE.status`)에
  매핑되어 라운드트립하는 것은 아직 통합 테스트로 확인하지 못했다 —
  사용자(User) 기능이 아직 구현되지 않아 그 컬럼 자체가 없다. 사용자
  기능을 구현할 때 실제 매퍼로 한 번 확인이 필요하다.
- `UserStatus` 외 다른 코드성 enum(메뉴 타입, 권한 타입 등)은 아직 없다 —
  필요해지면 같은 패턴(`CodeEnums` 구현 + `core.enums`)을 따른다.

## 추가(2026-09-17): `CodeEnumTypeHandler` 패키지 이동

세션/로그인 작업 중 one-off 핸들러 `SessionUserTypeHandler`가 필요해지면서,
TypeHandler류를 전용 패키지 `kkdugi.core.mybatis`로 모으기로 했다.
`CodeEnumTypeHandler`도 `kkdugi.core.enums`에서 `kkdugi.core.mybatis`로
옮겼다 — TypeHandler는 "enum의 정의"가 아니라 "MyBatis 연동 방식"이라
`core.enums`보다 `core.mybatis`가 더 정확한 자리다. `application.yml`의
`default-enum-type-handler` 값과 `CodeEnumTypeHandlerTest`도 함께
옮겼다(패키지만 변경, 동작은 그대로). 이 문서의 본문에 남아 있는
`kkdugi.core.enums.CodeEnumTypeHandler` 표기는 결정 당시 기록이라 그대로
두고, 현재 위치는 [공통 규약 8번](../conventions/common-base-model.md#8-코드성-enum은-codeenums를-구현하고-kkdugicoreenums에-둔다)을
따른다.
