# R9 실제 admin 연동 수용 시나리오

상태: 준비 완료, 실행 보류. Owner 결정에 따라 admin 배치 API 구현 후 진행한다. TLS fake admin·단위 테스트·실제 systemd 실행 결과는 실제 admin/PostgreSQL 검증을 대체하지 않는다.

## 준비 조건

- `kkdugi-admin`에 Runner 등록·세션·설치 승인·heartbeat·claim/start/started/logs/completion/reconcile API, Job·수동 실행·스케줄·취소·재시도·운영 복구와 Flyway migration을 구현한다.
- 실제 HTTPS 인증서와 전용 테스트 DB를 사용하고 admin/runner OS·버전·git revision·설정·서버 limits·시계 동기화 상태를 기록한다.
- Linux 및 Windows 목표 운영 서비스 계정에서 각각 등록한다. 승인된 동시 실행 수, Job 중복 정책, 설치 capacity를 명시한다.
- 배포된 `kkdugi-runner-probe`를 논리 프로그램으로 등록한다. Manifest는 probe 절대경로, 빈 arguments, 전용 working_directory, input_mode=JSON_FILE, input_contract=probe-v1이다. Binary digest를 files에 넣고 설치 revision을 승인한다.

Probe 입력 예:

```json
{"message":"acceptance","delayMillis":500,"exitCode":0,"outputLines":2,"value":9007199254740993}
```

Probe는 message를 stdout에 outputLines번 출력하고 지연 후 result JSON에 message/value/outputLines를 저장하고 exitCode로 종료한다. delayMillis는 0~60000, exitCode 0~255, outputLines 0~20000, message 최대 1024 bytes다. 최대 입력은 256 KiB이며 임의 shell 명령이나 파일 경로 입력은 받지 않는다. 큰 정수 value의 원문 숫자를 보존한다.

## 실행·판정표

| 시나리오 | 실행 방법 | 합격 증거 |
| --- | --- | --- |
| 등록·승인 | 일회성 토큰으로 등록, 설치 보고 후 승인 전/후 실행 요청 | 승인 전 spawn 없음, 승인 후 지정 revision 실행 |
| 수동 실행 | 위 입력으로 Job Run 생성 | 실제 admin Run/Attempt 성공, 결과 정수 보존, STDOUT 2줄·종료 코드 0 |
| 예약 실행 | 짧은 테스트 cron·timezone 설정 | admin이 예정 시각마다 Run 생성, 동일 예정 시각 중복 없음 |
| 병렬 제한 | 지연 probe를 여러 Run으로 요청 | Job overlap/parallelLimit·설치 capacity·runner capacity 모두 준수 |
| 취소·timeout | delayMillis=60000 실행 후 취소 또는 짧은 Job timeout | 프로세스 트리 종료 확인 후 CANCELED/TIMED_OUT, 실제 종료 시각·사유 일치 |
| 재시도 | exitCode=7, Job 재시도 정책 지정 | admin만 다음 Attempt 생성, runner 자체 재실행 없음 |
| 응답 유실 | 테스트 프록시로 claim/start/completion 응답을 각각 유실 | 멱등 기록과 journal 대조, 배정당 중복 spawn 없음 |
| Admin 중단 | 실행 중 admin 중지 후 프로그램 종료, admin 재시작 | runner의 timeout·완료 저장 유지, reconcile 뒤 동일 완료 접수 |
| Runner 중단 | START_INTENT 전/후, 실행 종료·완료 ACK 전후에 서비스 강제 종료 | NEVER_STARTED/UNKNOWN/FINISHED 구분, UNKNOWN 자동 재실행 없음 |
| 로그 한도 | 1024-byte message를 20000줄 출력 | pipe 정체 없음, 한도 이후 TRUNCATED, 업무 결과와 로그 상태 분리 |
| 로그 재전송 | 완료 ACK 후 로그 통신만 끊었다가 서비스 재시작 | 마지막 sequence/내용 불변, 연속 ACK 이후 파일 삭제 |
| 디스크 부족 | 테스트 볼륨으로 spool 제한·여유 부족 재현 | 신규 claim/start 중지, 이미 관측한 결과 보존, 원인 제거 후 복구 |
| 서비스 업데이트 | 작업 중 정상 stop/drain 후 checksum 검증·binary 교체 | 이전 결과/credential/DB 보존, 구 작업 재실행 없음 |
| 세션·키 폐기 | 중복 runner 기동 및 access 폐기 | 단일 인스턴스·세대 fencing, 새 시작 중지, 인증 오류를 종료 증거로 사용하지 않음 |

각 케이스에 Run/Attempt ID, admin API 결과, PostgreSQL 상태·멱등 기록, runner journal·로그 ACK, 실제 프로세스 증거를 함께 기록한다. 민감 토큰·secret 원문은 결과 보고서에 넣지 않는다. 통과 후에만 목표 OS를 운영 지원 범위로 확정한다.

Workflow 엔진은 별도 후속 기능이다. 나중에 owner 승인을 포함한 Workflow 테스트를 추가하되, runner 완료 ACK가 다음 Job 시작 권한으로 바뀌지 않는지 확인한다.
