# 로컬 로그·trace E2E (#361)

이 경로는 로컬 Docker Compose 전용이다. `X-Trace-Id`와 API 본문의 `traceId`는 업무 correlation 값이고, Grafana Tempo의 32자리 W3C trace ID는 별도 값이다. AI 요청 HTTP trace는 `202/PENDING`에서 끝난다. 이후 polling Worker, outbox 발행, Kafka consumer는 별도 실행이며 `executionId`로 대조한다. Kafka 기록 헤더로 이어진 producer·consumer span만 실제 parent 관계로 읽는다.

## 기동 전 기록

저장소 루트에서 실행한다. ignored `infra/.env`는 기존 로컬 운영 runbook 방식으로 준비하며 값을 출력하지 않는다. 기존 Compose 프로젝트·volume을 먼저 기록하고 새 고유 프로젝트명을 선택한다. 아래 `$compose` 배열을 기동부터 종료까지 그대로 사용한다. Kafka 실험은 **다른 새 프로젝트**에서 `infra/compose.kafka-local.yml`을 observability overlay 앞에 추가한다. Keycloak 및 Qwen overlay는 넣지 않는다.

```powershell
$project = 'finguardops-361-unique01'
$compose = @('compose','-p',$project,'--env-file','infra/.env',
  '-f','infra/compose.yml','-f','infra/compose.local-jwt-e2e.yml',
  '-f','infra/compose.observability-local.yml')
docker compose ls
docker volume ls --filter "label=com.docker.compose.project=$project"
& docker @compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose config failed' }
& docker @compose up -d --build --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { throw 'Stack not healthy; inspect only this project' }
& docker @compose ps
```

Rule 네 버전은 새 DB에서 DRAFT다. 거래 전 [기존 Rule 발행 절차](./prometheus-local-scrape-runbook.md#4-로컬-rule-집합-발행)를 같은 `$compose` 조합에서 한 번 수행해 PUBLISHED/활성 4/4를 확인한다. Kafka 실험에서 발행용 one-shot Backend에는 `FINGUARDOPS_KAFKA_ENABLED=false`를 지정한다. 기존 DB에 중복 발행하지 않는다.

## 하나의 합성 사건 조사

[Kafka AI 리포트 runbook 3절](./local-kafka-ai-report-runbook.md#3-인증된-합성-high-사건과-ai-202pending)의 fixture 내부 시나리오를 비식별 참조 접두사만 새 값으로 바꿔 실행한다. Token은 fixture control socket과 메모리에만 두고 host로 내보내지 않는다. `201`, HIGH/55, `ADDITIONAL_AUTH_REQUIRED`, 사건 `IN_REVIEW`, AI 요청 `202/PENDING`과 `transactionId`, `caseId`, `aiRequestId`, `executionId`만 기록한다. 기존 runbook 4절처럼 최종 리포트 상태와 저장 행을 확인한다. Ollama가 없으면 `FALLBACK_COMPLETED`와 허용된 실패 코드를 기록하며 LLM 성공으로 해석하지 않는다.

Grafana는 `http://127.0.0.1:3000`의 기존 로컬 계정으로 접속한다. Explore의 FinGuardOps Loki에서 `{service_name="backend"} |= "Rule analysis call"`, `{service_name="ai-service"} |= "event=ai_http_completed"`를 찾고 같은 동기 호출의 `trace_id`를 비교한다. AI 요청과 Worker는 `|= "executionId=<기록한 UUID>"`로 검색한 뒤 각각의 `otelTraceId`를 기록한다. FinGuardOps Tempo에서 각 trace ID를 조회해 Backend Rule client와 FastAPI Rule server span의 ID·시간을 확인한다. AI 요청과 Worker가 서로 다른 trace인지 확인한다. 조회 시각·query·결과 건수와 비민감 ID만 증거로 남긴다. Loki index label은 `service_name`과 고정 환경뿐이며 업무 ID는 log body 검색 필드다.

Kafka overlay에서는 [기존 runbook 3~5절](./local-kafka-ai-report-runbook.md)의 요청 직전/직후 counter, group offset·lag, outbox SQL, 최종 상태 절차를 같은 `executionId`로 수행한다. `kafkaStarts`와 `kafkaRecordsStarted`가 각각 1 증가하고 `pollingStarts`가 늘지 않으며 group offset이 진행해야 Kafka 시작 성공이다. Polling 선점, 다른 실행 혼입, Backend 재시작이면 이 실행의 Kafka 시작은 미확정 또는 polling으로 기록한다. outbox `PUBLISHED`만으로 consumer 통과를 판정하지 않는다.

## 장애·보안·자원 확인

Collector 자체 계측은 내부 `8888/metrics`에서 읽는다. 같은 application network의 `local-jwt-fixture`에서 Python `urllib.request.urlopen('http://otel-collector:8888/metrics')`로 조회하고 `otelcol_exporter_queue_size`, `otelcol_receiver_refused_log_records`, `otelcol_receiver_refused_spans`, `otelcol_exporter_send_failed_*`, `otelcol_exporter_enqueue_failed_*`를 전후 비교한다. Collector의 로그 허용식 평가 오류는 해당 telemetry payload를 버린다. Collector가 중단된 동안의 드롭은 복구 후 계측만으로 0이라고 추정하지 않는다. Loki index label은 `/loki/api/v1/series`에서 확인한다. OTLP structured metadata와 log body의 `executionId`는 label이 아니다.

새 합성 요청을 제한해서 Collector 중단과 Loki 또는 Tempo 중단을 각각 확인한다. Backend와 FastAPI에는 Collector 시작 의존성이 없고 exporter는 비동기로 동작한다. 중단 전후 API 업무 결과, 최종 리포트 저장 행, `/actuator/prometheus`의 기존 지표 접근과 서비스 재시작 수를 비교한다. 중단 중 손실된 telemetry를 복구된 것으로 주장하지 않는다. Backend의 exporter queue 256, FastAPI queue 256, Collector memory limiter 192 MiB와 exporter queue 128, Collector 256 MiB·Loki/Tempo 각각 512 MiB 상한은 초기 실험값이다. `docker stats --no-stream`과 `docker inspect`로 실제 사용량·OOM·재시작을 기록한다.

Loki/Tempo에서 Authorization, token, 고객·계좌·기기 식별자, 거래/Kafka payload, Prompt, 모델 출력, Provider 오류 원문이 없는지 **실험에 사용한 비공개 표식만** 검색한다. 표식이나 원문을 공개 Issue·터미널 캡처에 복사하지 않는다. 업무 ID가 Loki label 및 Prometheus label에 없는지 label API와 scrape 결과를 대조한다. 로그의 기존 `traceId`는 업무 correlation, `otelTraceId`/OTLP `trace_id`는 W3C 값이다.

## 종료와 보존

실패 시 먼저 이 프로젝트의 비민감 상태·bounded 로그·조회 결과를 기록한다. 같은 `$compose`와 프로젝트명으로만 일반 `down`을 실행한다. `--volumes`, `docker volume prune`, 기존 프로젝트의 volume mount·삭제를 사용하지 않는다. Docker의 새 프로젝트 volume과 PostgreSQL의 anonymous data mount를 종료 전후에 기록한다. named volume은 `down` 뒤에도 보존되고 anonymous mount는 자동 재연결을 보장하지 않으므로, 재검증에 DB 연속성이 필요하면 종료 전에 판단한다. 기존 보존 volume의 삭제는 [로컬 운영 runbook의 별도 승인 절차](./local-operations-runbook.md)를 따른다.

```powershell
& docker @compose ps
docker stats --no-stream
docker volume ls --filter "label=com.docker.compose.project=$project"
& docker @compose down
if ($LASTEXITCODE -ne 0) { throw 'Project cleanup failed' }
docker ps -a --filter "label=com.docker.compose.project=$project"
docker network ls --filter "label=com.docker.compose.project=$project"
docker volume ls --filter "label=com.docker.compose.project=$project"
```

중단된 `docker compose run --rm` 일회성 실행은 일반 `down` 뒤에도 컨테이너와 네트워크를 점유할 수 있다. 이 경우 이름 패턴으로 일괄 정리하지 않는다. 전체 컨테이너 ID·`project`/`service`/`oneoff` label·mount·네트워크의 다른 연결을 inspect하고 비민감 로그를 먼저 보존한 뒤, 별도 승인된 정확한 ID만 정리한다. `run --rm` 컨테이너는 `stop`과 동시에 자동 제거될 수 있으므로 ID 부재를 재확인하고 이미 사라졌다면 `rm`을 반복하지 않는다. 마지막에 동일 `$compose`로 일반 `down`을 다시 실행하고 프로젝트 소유 컨테이너·네트워크가 0인지 확인한다.
