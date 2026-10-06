# #347 로컬 Kafka 검증 절차

이 문서는 OWNER 또는 Claude Code가 Docker 가능 환경에서 실행할 절차다. 기본 Compose는 Kafka를 활성화하지 않는다. Kafka 실험에는 `infra/compose.yml`과 `infra/compose.kafka-local.yml`을 함께 사용한다. 공식 Keycloak gate에는 Kafka overlay를 합치지 않는다.

## 준비와 리소스

- Apache JVM Kafka 4.3.1 단일 KRaft broker, `kafka-data` volume, Backend polling 30초가 추가된다. Docker Hub manifest digest는 `sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837`이다. 기존 서비스 외 실험용 여유 메모리 2 GiB와 디스크 2 GiB를 잠정 확보하고 실제 사용량은 측정해 기록한다. 이 수치는 측정 성능이나 최소 사양을 뜻하지 않는다.
- 기존 PostgreSQL·Backend·AI Service와 로컬 인증 fixture가 필요하다. 비밀은 기존 ignored 설정 절차를 사용한다. 공용 API 포트 외 Kafka 포트를 host에 publish하지 않는다.
- 시작 전 `git status --short`, `docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml config --quiet`과 이미지 digest를 확인한다. 저장소의 기존 Compose 환경 변수 준비 절차를 따른다.

## 실험 순서

저장소 루트의 PowerShell에서 실행한다. `config` 단계는 container를 만들지 않는다.

```powershell
docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml config --quiet
docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml up -d --build
docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml ps
docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic finguardops.ai-report-execution-created.v1
docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic finguardops.ai-report-execution-created.v1.dlq
docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group finguardops-ai-report-worker-v1
```

장애 실험에서는 각 단계 전후 DB row 수와 기존 API 응답을 기록한다. Broker 장애는
`docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml stop kafka`와
같은 파일 조합의 `start kafka`로 만든다. Backend 재시작은 같은 조합의
`restart backend`를 사용한다. Consumer만 중단할 때는 아래 설정으로 Backend를
재생성한다. 이때 polling은 30초로 계속 동작한다. 첫 두 명령 뒤 4번 실험을
수행하고, 나머지 명령으로 Listener를 다시 켠다.

```powershell
$env:FINGUARDOPS_KAFKA_CONSUMER_ENABLED='false'
docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml up -d --no-build --force-recreate backend
```

4번 실험 후 복구한다.

```powershell
$env:FINGUARDOPS_KAFKA_CONSUMER_ENABLED='true'
docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml up -d --no-build --force-recreate backend
Remove-Item Env:FINGUARDOPS_KAFKA_CONSUMER_ENABLED
```

1. `docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml up -d --build` 후 Kafka health, Backend health, 두 topic의 partition 1/복제 1을 확인한다. Backend가 Kafka 없이도 기동 가능한지는 broker를 중단한 상태에서 별도로 검증한다.
2. HIGH 또는 CRITICAL 사건의 새 AI 리포트를 요청한다. `202/PENDING`과 DB의 새 execution·최초 request·outbox 한 행을 확인한다. 같은 키 재생, 공유 실행, 캐시 결과는 outbox가 증가하지 않아야 한다. 완료 또는 실패는 기존 Analyst 조회 API로 확인한다.
3. 발행 전 broker 중단, ack 뒤 `PUBLISHED` 표시 전 Backend 중단을 각각 재현한다. 재시작 뒤 같은 `event_id` 재발행을 허용하되 execution·report·attempt 증가가 한 번인지 확인한다. `PUBLISHED` 행은 자동 삭제되지 않아야 한다.
4. Consumer를 중단하고 요청을 쌓은 뒤 재시작한다. Consumer와 polling이 한 execution을 동시에 선점하도록 하며, `starts{source="kafka"}`와 `starts{source="polling"}`의 합이 실제 선점 수와 같은지 확인한다. 다른 실행이 `GENERATING`이라 busy로 확인된 PENDING은 30초 polling으로 시작되는지 검증한다.
5. 같은 메시지를 중복·늦게 보내고 key 불일치, 추가 필드, v2, 과대 payload를 각각 보낸다. poison은 DLQ에 들어가고 원본 offset은 DLQ ack 뒤에만 진행해야 한다. 수정된 v1을 **수동** 재투입할 때에도 이미 종료된 실행은 Provider를 재호출하지 않아야 한다. DLQ에는 자동 Consumer가 없다.
   DLQ 기록은 `docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic finguardops.ai-report-execution-created.v1.dlq --from-beginning --max-messages 1`로 확인한다. 수동 재투입은 검증된 기존 outbox의 `event_id`·`execution_id`를 유지한 payload만 사용하고, 재투입 전후 DB 실행 상태와 attempt 수를 기록한다.
6. 거래의 기존 `201`/멱등 `409`, 사건 상태·최종 판정·Audit 행 수, AI `202/200`, 리포트·attempt 및 비용 NULL 계약을 전후 대조한다. outbox 적체·최고 연령, `BLOCKED`, lag, DLQ, PENDING 최고 연령도 확인한다.

## 공식 인증 gate와 정리

Kafka overlay를 내린 뒤 저장소의 `docs/09-deployment/local-keycloak-auth-e2e-runbook.md`에 따라 `frontend/scripts/run-keycloak-e2e.ps1`의 **Prepare → Service → Run**을 순서대로 실행한다. Prepare는 **검토·승인된 커밋의 clean tree**를 요구한다. 현재 미커밋 구현 상태에서는 실행할 수 없고, OWNER가 변경을 확정한 뒤 Claude Code가 gate를 수행해야 한다. gate가 소유하는 image·receipt·secret은 그 runbook의 Cleanup 절차로만 정리한다.

```powershell
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Prepare
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Service
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Run
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Cleanup
```

Kafka 실험이 만든 container는
`docker compose -f infra/compose.yml -f infra/compose.kafka-local.yml down`으로
정리한다. 이 명령에는 `--volumes`를 붙이지 않는다. `kafka-data` volume은 topic·offset·DLQ 증거 검토 후에만 실험 담당자가 명시적으로 정리한다. 기존 PostgreSQL·Grafana·Keycloak volume을 함께 삭제하지 않는다. 실패 시 outbox 및 DLQ 증거, 비민감 metric과 로그를 먼저 보존한다.
