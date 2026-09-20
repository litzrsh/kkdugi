# Runner 로컬 설정·입출력 구현 계약

이 문서는 [Runner 구현](runner-implementation-plan.md)의 파일 형식이다. Admin API의 실행 권한을 대신하지 않는다. `run`과 보호된 secret 파일 경로는 [R5 계약](runner-r5-contract.md)을 따른다.

실행 중 설치 상태·revision 이력·승인 대조는 [R4 catalog 계약](runner-r4-contract.md)을 따른다. TOML 형식은 유지하며 wire JSON은 별도 DTO로 변환한다.

## 설정

`internal/config`의 `Config`는 다음 필드를 가진다. Go 필드는 대문자로 시작하고 TOML tag는 아래 이름을 사용한다.

| TOML | Go 타입 | 필수·범위 |
| --- | --- | --- |
| data_dir | string | 설정 파일 디렉터리 기준 상대경로 또는 절대경로 |
| capacity | int | 1~200 |
| programs | []Program | 0개 이상, code 중복 금지. 설치 전 runner 등록을 허용 |
| admin | 선택 Admin | 등록 시 필수. [R2 설정·등록 계약](runner-r2-contract.md) 참조 |

`Program`은 Code/Version/Revision string 필드(tag: code/version/revision)와 Manifest 필드(tag: manifest)를 가진다. 길이는 각 20/50/200자 이내이며 비어 있으면 안 된다. Code는 ASCII 영문·숫자·밑줄·하이픈만 허용한다.

`Manifest`의 필드:

| Go 필드 | 타입 | TOML tag |
| --- | --- | --- |
| Executable | string | executable |
| Arguments | []string | arguments |
| WorkingDirectory | string | working_directory |
| InputContract | string | input_contract |
| InputMode | string | input_mode |
| SecretNames | []string | secret_names |
| Files | []FileDigest | files |

`FileDigest`는 Path/SHA256 string 필드(tag: path/sha256)를 가진다. Manifest 경로는 실행 OS의 절대경로여야 한다. InputMode는 JSON_FILE, InputContract는 비어 있지 않은 계약 식별자다. 임의 shell 문자열은 받지 않는다. 모든 문자열에서 NUL을 거절하고 SecretNames는 중복 없는 환경 변수 이름이어야 한다. KKDUGI_ prefix는 runner 예약 이름이다.

TOML 알 수 없는 필드, 파일 1 MiB 초과, 프로그램 코드 중복을 거절한다. 실행 파일·작업 디렉터리·digest 목록의 실제 파일을 확인한다. Files는 digest 64자리 16진수만 허용하며 경로가 중복되면 거절한다. Digest 비교는 실행 직전 다시 수행해야 한다. 배포 파일은 실행 중 교체하지 않는 운영 계약이다.

`verify`는 프로그램을 실행하거나 admin을 호출하지 않는다. 입력을 파싱한 뒤 경로·파일 검증을 하고 data 디렉터리의 임시 probe 파일로 쓰기 가능 여부를 확인한다. Credential·DB를 변경하지 않는다.

## 로컬 입력·출력

Workspace는 안전한 assignmentId(ASCII 영숫자·밑줄·하이픈, 최대 20자)를 디렉터리 이름으로 사용한다. Windows 장치 예약 이름도 거절한다. 같은 ID를 재사용하면 실패하며 기존 파일을 덮어쓰지 않는다. 입력은 UTF-8 JSON 객체, 최대 256 KiB. 원본 숫자를 float64로 변환하지 않는다. Context의 session은 음수가 아닌 signed bigint 범위의 10진 문자열, attempt는 1~2147483647이다.

입력 파일, 컨텍스트 파일, 결과 파일 위치는 구조 설계의 환경 변수로 전달한다. 결과는 없으면 null, 있으면 UTF-8 JSON 객체 최대 256 KiB. 잘못된 JSON, 심볼릭 링크·일반 파일 아닌 결과는 거절한다.

Executor는 context, 절대 실행 경로·고정 argv·cwd·명시적 자식 환경, stdout/stderr writer, timeout과 stop grace를 받는다. context 취소와 timeout을 구분한다. 성공·실패와 별개로 프로세스 관리 범위 종료 확인을 반환한다. UNKNOWN인 경우 성공으로 추정하지 않는다. Windows 정상 종료는 프로그램별 보편적 graceful signal이 없으므로 유예 후 Job Object 전체 종료를 기본 adapter 정책으로 문서화한다.

이 계약은 테스트용 executor와 구성 요소의 계약이며 임의 명령 실행 CLI를 공개하지 않는다.
