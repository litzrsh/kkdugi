# R8·R9: Runner 서비스·배포·연동 검증

R8은 Linux systemd와 Windows SCM 어댑터, 설치·중지·제거·업데이트, 버전별 배포 묶음과 검증을 제공한다. R9의 실제 admin 연동에는 admin 배치 API·DB·스케줄러 구현이 필요하다. 현재 저장소에는 해당 API가 없으며 mock 결과를 실제 admin 검증으로 표시하지 않는다.

Owner 결정: 이번에는 runner 마무리·연동 테스트 준비까지 수행하고 **실제 R9는 admin 구현 후** 진행한다.

## 제공하는 기능과 검증 범위

| 항목 | 상태 |
| --- | --- |
| Linux systemd 설치·실행·업데이트·중지·제거 | AlmaLinux 10 WSL2, 실제 전용 서비스 계정으로 검증 |
| Windows SCM 어댑터 | 구현·Windows 단위 테스트 완료. Stop/Interrogate/drain/실패 종료 코드 검증 |
| Windows 설치·업데이트·제거·소유권 이전 스크립트 | 구현·PowerShell 문법 검사 완료. 현재 계정에 관리자 권한이 없어 실제 SCM 등록·가상 계정 실행은 미검증 |
| 배포 | Windows/Linux amd64 ZIP, runner/probe binary·checksum·설정·운영 안내 |
| R9 | 검증 프로그램·시나리오 준비 완료, 실제 admin 연동은 보류 |

Windows SCM의 Running과 Linux systemd의 active는 프로세스가 살아 있다는 뜻이다. Admin 연결·설치 승인·복구 완료 여부는 heartbeat의 mode와 admin 상태로 확인한다. 시작 직후 정상 중지의 context.Canceled는 성공 종료로 처리하며 저장소 오류 등 다른 오류를 숨기지 않는다.

서비스 계정은 binary/config와 data/credential의 권한을 구분한다. Credential과 SQLite/spool/work는 runner 계정 전용이다. 업데이트는 서비스 중지 확인 후 binary만 교체하며 state/credential 삭제나 자동 DB downgrade를 하지 않는다.

## 배포 묶음 생성

저장소 루트에서 Go가 PATH에 있는 PowerShell로 실행한다.

```powershell
./kkdugi-runner/packaging/build.ps1 -Version 0.1.0-preview.2
```

`kkdugi-runner/bin/releases/<version>/`에 OS별 폴더와 ZIP을 만든다. 같은 버전의 기존 산출물을 덮어쓰지 않는다. ZIP의 `SHA256SUMS`는 runner와 probe binary를 검증한다. checksum은 전송 오류 확인용이며 배포자의 서명을 대신하지 않는다. Linux ZIP을 풀면 실행 권한을 부여해야 한다. 설치 스크립트는 `install -m 0755`로 runner 권한을 설정한다.

## Linux 운영 순서

예시 서비스 이름은 `kkdugi-worker`, 설치 위치는 `/opt/kkdugi-runner/kkdugi-worker`, 설정은 `/etc/kkdugi-runner/kkdugi-worker/runner.toml`, data는 `/var/lib/kkdugi-runner/kkdugi-worker`다. 설치 스크립트는 같은 이름의 전용 system user/group을 생성하므로 기존 계정·경로를 재사용하지 않는다.

1. TOML에 위 data_dir와 절대경로 admin/CA/프로그램 경로를 지정한다. Credential은 기본값인 `<data_dir>/credentials`를 사용한다. 처음에는 `programs=[]`로 설치해도 된다.
2. root로 `bash packaging/manage.sh install kkdugi-worker /배포/kkdugi-runner /준비/runner.toml <runner SHA256>`을 실행한다. 스크립트는 서비스 계정으로 `verify`하며 아직 서비스를 시작하거나 enable하지 않는다.
3. CA·업무 프로그램을 배포한다. 필요하면 설정의 프로그램 항목을 추가하고 같은 계정으로 다시 verify한다. Binary/config/CA는 서비스 계정이 읽을 수 있어야 하며 root가 관리한다.
4. `runuser -u kkdugi-worker -- /opt/kkdugi-runner/kkdugi-worker/runner register --config /etc/kkdugi-runner/kkdugi-worker/runner.toml`을 실행하고 일회성 등록 토큰을 stdin으로 전달한다. Secret 파일도 이 계정 소유, 디렉터리 0700/파일 0600으로 준비한다.
5. `systemctl enable --now kkdugi-worker.service`로 시작한다. `systemctl status`와 `journalctl -u kkdugi-worker.service` 및 admin heartbeat를 확인한다.

unit은 SIGTERM으로 runner의 기본 30초 drain을 요청하며 최대 90초 후 systemd가 남은 cgroup을 종료한다(`KillMode=mixed`). Restart=on-failure, 10초 간격·5분 내 3회 상한이다. `ProtectHome=true`, `ProtectSystem=full`, `NoNewPrivileges=true`를 적용한다. `/home`의 업무 프로그램이나 시스템 디렉터리 쓰기에 의존하지 않도록 배포한다. 업무별 추가 쓰기 경로·자원 한도는 실제 운영 환경에서 확정한다.

업데이트는 `bash packaging/manage.sh update kkdugi-worker /새배포/kkdugi-runner <SHA256>`이다. checksum·실행 설정 확인 후 stop → MainPID=0 확인 → previous binary 보관 → binary 교체 → start 순서다. 새 버전이 DB migration을 수행했다면 previous binary로 임의 rollback하지 않는다. 자동 시작을 중지하고 해당 schema와 호환되는 버전 또는 검증된 백업 복구 절차를 사용한다.

제거는 `bash packaging/manage.sh remove kkdugi-worker`다. unit만 중지·disable·제거하고 account/binary/config/data/credential은 남긴다. 미해결 배정이나 미ACK 로그를 확인하기 전에 data를 삭제하지 않는다.

## Windows 운영 순서

관리자 PowerShell과 테스트 머신이 필요하다. 이 절차는 현재 호스트에서 실제 SCM 등록 검증을 마치지 않았으므로 운영 배포 전 별도 검증한다.

1. ZIP을 풀고 binary checksum을 확인한다. TOML의 data_dir는 서비스 전용 `C:\ProgramData\KkdugiRunner\worker` 같은 새 디렉터리, credential은 그 아래에 둔다. config/CA/업무 프로그램은 서비스 계정이 읽을 수 있는 절대경로를 사용한다.
2. `packaging/manage.ps1 -Action Install -Name kkdugi-worker -Binary <exe> -Config <toml> -SHA256 <hash>`를 실행한다. 기본 binary 위치는 Program Files 아래이며 `NT SERVICE\kkdugi-worker` 가상 계정으로 수동 시작 서비스를 만든다. SYSTEM 계정으로 업무를 실행하지 않는다.
3. 서비스가 중지된 상태에서 관리자가 같은 TOML로 `register`를 수행한다. 등록 토큰은 stdin으로 전달한다. 필요한 secret 파일을 data 아래에 준비한다.
4. `packaging/protect-data.ps1 -Name kkdugi-worker -DataDirectory <data_dir>`로 전용 data의 소유자와 ACL을 서비스 계정으로 이전한다. 스크립트는 reparse point와 범위 이탈을 거절하고 자식부터 적용한다. SYSTEM과 서비스 계정만 허용한다. Credential/secret을 별도 디렉터리에 두었다면 해당 전용 디렉터리도 같은 정책으로 이전해야 한다.
5. `Start-Service kkdugi-worker` 후 SCM·admin heartbeat·실제 업무 실행을 확인한다. 검증 후 `Set-Service kkdugi-worker -StartupType Automatic`으로 바꾼다.

업데이트는 manage.ps1의 `-Action Update -Name ... -Binary ... -SHA256 ...`, 제거는 `-Action Remove -Name ...`다. 사용자 지정 InstallDirectory를 사용했다면 같은 값을 전달한다. 기존 SCM binary 경로가 이 설치와 일치하는지 검사하며 stop 확인 전에 binary를 바꾸지 않는다. 업데이트 후 프로세스 기동만으로 admin 준비 완료를 판단하지 않는다.

SCM Stop/Shutdown/PreShutdown은 같은 drain으로 연결되고 대기 중 checkpoint를 보고한다. 운영 Windows의 종료 제한·가상 계정 권한·Job Object 정리는 별도 실제 서비스 테스트로 확정한다.

## 검증 기록과 R9 인계

- Windows Go 전체 테스트·정적 검사, Windows/Linux 빌드. Windows SCM handler는 제어 채널 테스트로 검증했다.
- Linux 실제 systemd 서비스는 일시적인 전용 계정을 만들고 TLS fake admin에 연결하여 업무 성공, checksum 오류 시 기존 서비스 유지, 정상 업데이트·세션 변경, 중지 시 drain, 제거 후 DB/credential 보존을 확인했다. 테스트가 만든 unit·계정·전용 경로는 종료 후 정리했다.
- 정상 중지와 bootstrap 취소가 겹쳐 Linux 서비스가 실패로 기록되던 문제를 발견·수정했다.
- Admin JS 55개 통과. Java는 296개 중 기존 일본어 메뉴 시드 테스트 1개 실패, 295개 통과. Admin 배치 API를 구현하거나 실제 R9를 수행한 결과가 아니다.
- R9는 [실제 admin 수용 시나리오](runner-admin-acceptance.md)를 사용한다. Probe는 테스트 workload일 뿐 admin 모형이나 실제 스케줄러가 아니다.

## Ollama 작업 단위

`kkdugi-runner/internal/service/name.go`를 생성한다. package service, 표준 errors/regexp만 사용. `ValidateName(name string) error`는 ASCII 영숫자로 시작하고 나머지 ASCII 영숫자·하이픈·밑줄로 구성된 1~64자만 허용한다. 잘못된 입력은 내용을 포함하지 않는 `invalid service name` 오류를 반환한다. 스크립트 실행·파일 접근·OS 서비스 등록은 하지 않는다.

Coder가 첫 글자를 영문자로만 제한한 부분을 영숫자로 수정한 뒤 경계 테스트를 적용했다. 서비스 제어·설치·업데이트·실제 systemd 검증은 주 에이전트가 작성했다.
