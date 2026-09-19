# kkdugi

여러 AI 에이전트가 협업하는 워크플로를 목표로 하는 프로젝트입니다. 현재는 기반 관리 시스템인 **kkdugi-admin**을 구현하고 있으며, AI 에이전트 실행·런타임은 별도 개발 예정입니다.

## 현재 구현

| 기능 | 내용 | API 문서 |
|---|---|---|
| 공통코드 | 계층형 코드, 다국어 이름, 사용자용 코드 조회 | [관리](docs/api/common-code.md) · [조회](docs/api/code.md) |
| 다국어 메시지 | 메시지 조회·저장, DB 기반 Spring MessageSource | [메시지](docs/api/i18n-message.md) |
| 메뉴 | 계층형 메뉴 관리, 다국어 메뉴명, 로그인 사용자별 메뉴 트리 | [메뉴 관리](docs/api/menu.md) · [세션 메뉴](docs/api/session.md) |
| 권한 | 권한 CRUD, 메뉴별 RBAC 및 사용자 매핑 | [권한](docs/api/authority.md) |
| 사용자 | 등록·수정·삭제, 상태 및 권한 관리, 비밀번호 초기화 | [사용자](docs/api/user.md) |
| 인증·세션 | JWT와 DB 세션, 중복 로그인 확인, 사용자 상태 검사, 초기·만료 비밀번호 변경/연장 | [인증](docs/api/auth.md) |
| 관리자 화면 | 로그인, 공통코드·메시지·메뉴·권한·사용자 관리 화면 | [디자인·프런트엔드](docs/design/README.md) |

관리 API는 `/api/v1.0/admin` 아래에 있으며, 사용자용 조회는 `/api/v1.0/code`, `/api/v1.0/menu`를 사용합니다. API별 인증·메뉴 컨텍스트·인가 규칙은 [API 문서](docs/api/README.md)와 [요청 컨텍스트](docs/api/request-context.md)를 기준으로 합니다.

## 기술 구성

- Java 17, Spring Boot 4.0.8, Maven Wrapper
- Spring Security, JWT, PostgreSQL 기반 세션
- MyBatis, Flyway, PostgreSQL 17
- Thymeleaf, Vue 3 SFC 및 브라우저 런타임 로더; 로그인은 vanilla JavaScript
- Redis 7: 개발용 Compose에 포함되어 있으며 애플리케이션 연동은 아직 없음

## 로컬 실행

JDK 17과 Docker Compose를 준비합니다. Maven은 저장소의 Wrapper를 사용하며, JavaScript 테스트에는 Node.js가 필요합니다.

저장소 루트에서 PostgreSQL과 Redis를 실행합니다.

```powershell
docker compose up -d
```

Windows PowerShell에서 애플리케이션을 실행합니다.

```powershell
cd kkdugi-admin
./mvnw.cmd -B -ntp spring-boot:run
```

macOS/Linux에서는 같은 디렉터리에서 `./mvnw -B -ntp spring-boot:run`을 사용합니다. 시작할 때 Flyway가 스키마와 초기 데이터를 적용합니다.

- 로그인 화면: <http://localhost:8080/login>
- 초기 개발 계정: `admin` / `admin1234` (새 DB에 기본 관리자 마이그레이션을 적용한 경우)
- PostgreSQL: `127.0.0.1:5432`, DB/사용자 `kkdugi_dev`
- Redis: `127.0.0.1:6379`

연결 설정은 [application.yml](kkdugi-admin/src/main/resources/application.yml), 컨테이너 설정은 [docker-compose.yml](docker-compose.yml)에 있습니다. 기본 계정·DB 비밀번호·JWT 비밀키는 개발용이므로 운영 환경에서는 교체해야 합니다.

컨테이너를 중지하려면 저장소 루트에서 `docker compose down`을 실행합니다. DB와 Redis 데이터는 명명된 볼륨에 유지됩니다.

## 빌드와 테스트

PostgreSQL을 실행한 상태에서 `kkdugi-admin/` 디렉터리에서 실행합니다.

```powershell
# 컴파일
./mvnw.cmd -B -ntp compile

# Java 테스트
./mvnw.cmd -B -ntp test

# 프런트엔드 계약·동작 테스트
node --test src/test/js/*.test.mjs

# 실행 가능한 JAR 생성 (Java 테스트 포함)
./mvnw.cmd -B -ntp package
```

macOS/Linux에서는 `./mvnw.cmd` 대신 `./mvnw`를 사용합니다. JavaScript 테스트는 Maven과 별도로 실행하며, 디렉터리 대신 위 파일 glob을 전달합니다.

## 저장소 구조

```text
kkdugi-admin/                     단일 Maven 애플리케이션
  src/main/java/kkdugi/
    api/                         REST 컨트롤러 (관리 API는 api/admin)
    app/                         사용자용 기능과 admin 도메인 서비스
    core/                        공통 모델·보안·다국어·ID 생성 등 기반 기능
    web/admin/                   로그인·셸·Pragma 화면 진입점
  src/main/resources/
    db/migration/                Flyway 마이그레이션
    mapper/postgres/             Java 패키지에 대응하는 MyBatis XML
    messages/                    다국어 폴백 메시지
    templates/                   Thymeleaf 및 Pragma Vue 화면
    static/                      JavaScript·CSS·폰트 등 정적 리소스
  src/test/                      Java 및 JavaScript 테스트
docs/                            API 계약·설계·개발 규칙·ADR
erd/                             데이터 모델 자료
graft/                           코드 탐색용 컨텍스트 그래프
docker-compose.yml               로컬 PostgreSQL·Redis
```

백엔드는 `api → app → core` 의존 방향을 따릅니다. 사용자용 `app.<feature>`와 관리용 `app.admin.<feature>`는 서로 참조하지 않습니다. 모델·페이징·패키지 규칙은 [공통 모델 규칙](docs/conventions/common-base-model.md)을 참고합니다.

## 문서와 개발 범위

- [문서 안내](docs/README.md)
- [현재 API 계약](docs/api/README.md)
- [백엔드 설계 결정(ADR)](docs/adr/README.md)
- [디자인·프런트엔드 구현 문서](docs/design/README.md)
- [메시지](docs/i18n-system-design.md) · [공통코드](docs/common-code-system-design.md) · [권한](docs/authority-system-design.md) · [사용자](docs/user-system-design.md) 시스템 설계

`docs/archive/`와 과거 작업 계획은 이력 자료입니다. 현재 동작과 API 계약은 `docs/api/`를 기준으로 확인합니다.

AI 에이전트 런타임, 조직·조직도 기능, 메일 발송은 아직 구현 범위에 포함되지 않았습니다. 사용자 등록·비밀번호 초기화 시 임시 비밀번호는 메일 연동 전까지 서버 로그로 출력됩니다. 휴면 해제 이메일도 미구현이며 자세한 상태 처리 정책은 인증 문서에 정리되어 있습니다.
