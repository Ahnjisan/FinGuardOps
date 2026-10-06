# #349 로컬 Kafka AI 리포트 검증

이 문서는 **별도 Kafka 실험**의 재현 절차다. 기본 Compose, Kafka overlay, 로컬 JWT fixture overlay만 한 프로젝트에 결합한다. Keycloak overlay는 합치지 않는다. JWT fixture는 사용자 로그인 서버가 아니므로 이 결과를 공식 Keycloak Browser Gate의 통과로 보고하지 않는다.

## 1. 비밀·프로젝트·설정 준비

- 저장소 루트 PowerShell과 Docker Desktop/Compose v2를 사용한다. 별도 broker의 여유 메모리·디스크를 각각 잠정 2 GiB 확보하고 실제 사용량을 기록한다. 이 수치는 측정된 최소 사양이 아니다.
- `infra/.env.example`을 참고해 ignored `infra/.env`에 로컬 전용 `POSTGRES_PASSWORD`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`의 실제 값을 준비한다. 예시 placeholder를 비밀로 사용하지 않는다. 실제 암호는 명령 인자·출력·문서에 넣지 않는다. Keycloak의 `infra/keycloak/.local/` 비밀은 사용하지 않는다.
- JWT fixture는 private key와 token을 tmpfs/control socket에만 둔다. 아래 Python은 fixture 컨테이너 안에서 `machine mint`와 같은 socket 경계로 token을 받아 메모리에서 HTTP 요청에 사용하며 host로 출력하지 않는다. fixture 재생성 후 이전 token은 폐기한다.
- 새 프로젝트 이름을 사용한다. 기존 프로젝트의 host 9090/3000 포트 점유 여부를 확인하고, 정리할 때도 이 프로젝트만 대상으로 한다. Kafka, Backend 8080/8081, PostgreSQL 포트는 host에 publish되지 않는다.

```powershell
# 먼저 저장소 루트로 이동해 실행한다.
$project = 'finguardops-kafka-349-local01' # 실행마다 고유하게 변경
$compose = @('compose', '-p', $project, '--env-file', 'infra/.env',
  '-f', 'infra/compose.yml', '-f', 'infra/compose.kafka-local.yml',
  '-f', 'infra/compose.local-jwt-e2e.yml')
git status --short
& docker @compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose config invalid' }
& docker @compose up -d --wait --wait-timeout 180 kafka
if ($LASTEXITCODE -ne 0) { throw 'Kafka did not become ready' }
& docker @compose up -d --build --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw 'Compose stack did not become ready' }
& docker @compose ps
```

`config --quiet`는 병합 문법과 필수 Compose 변수만 확인한다. 병합된 Backend 환경에서 issuer `https://local-jwt.fixture.finguardops.invalid`, JWK `http://127.0.0.1:8002/oauth2/jwks`, `FINGUARDOPS_KAFKA_ENABLED=true`, bootstrap `kafka:9092`, consumer enabled를 대조한다. `local-jwt-fixture`는 `network_mode: service:backend`이고 ready여야 한다. Keycloak과 JWT overlay를 한 Backend에 합치지 않는다.

## 2. Rule 발행과 topic/group

V5의 기본 Rule 네 버전은 DRAFT seed다. 거래 접수 전에 [로컬 Rule 발행 계약](./prometheus-local-scrape-runbook.md#4-로컬-rule-집합-발행)의 one-shot을 **같은 세 파일 조합**으로 수행한다. 다음은 새 프로젝트에서만 실행한다. 실행 직전 미래 UTC `effectiveFrom`을 계산하며 성공 로그와 DB의 PUBLISHED/활성 4/4를 모두 확인한다. 이미 발행된 프로젝트에서는 재실행하지 않는다. 발행 실패를 0점 탐지로 간주하지 않는다.

```powershell
$effectiveFrom = (Get-Date).ToUniversalTime().AddMinutes(5).ToString('yyyy-MM-ddTHH:mm:ssZ')
& docker @compose run --rm --no-deps -T -e SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication -e FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false backend --spring.main.web-application-type=none --logging.level.org.hibernate.orm.connections.pooling=WARN --finguardops.rule-v1-default-publication.enabled=true --finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1 "--finguardops.rule-v1-default-publication.effective-from=$effectiveFrom"
if ($LASTEXITCODE -ne 0) { throw 'Rule publication failed' }
$deadline = (Get-Date).AddSeconds(420)
do {
  $published = & docker @compose exec -T postgresql psql -U finguardops -d finguardops -tAc "select count(*) from rule_version where status='PUBLISHED'"
  if ($LASTEXITCODE -ne 0) { throw 'Rule publication query failed' }
  $active = & docker @compose exec -T postgresql psql -U finguardops -d finguardops -tAc "select count(*) from rule_version where status='PUBLISHED' and effective_from <= current_timestamp"
  if ($LASTEXITCODE -ne 0) { throw 'Rule activation query failed' }
  if ($published.Trim() -eq '4' -and $active.Trim() -eq '4') { break }
  Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
if ($published.Trim() -ne '4' -or $active.Trim() -ne '4') { throw 'Rule 4/4 activation timed out' }
```

Backend의 `NewTopic` 설정이 원본과 DLQ를 각 partition 1/replica 1로 만든다. group offset은 첫 소비 전에는 없을 수 있다.

```powershell
& docker @compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic finguardops.ai-report-execution-created.v1
& docker @compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic finguardops.ai-report-execution-created.v1.dlq
& docker @compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group finguardops-ai-report-worker-v1
```

## 3. 인증된 합성 HIGH 사건과 AI `202/PENDING`

다음 예시는 새 UUID와 비식별 참조값만 사용한다. `PASSWORD_CHANGED`와 `TRANSFER_LIMIT_CHANGED`를 접수한 다음 12,000,000 KRW 거래로 R001(15)+R003(40), 55/HIGH, `ADDITIONAL_AUTH_REQUIRED`를 확인한다. 채택 탐지 버전을 읽고 `FDS_ANALYST` 권한으로 사건을 `IN_REVIEW`로 바꾼 후 **새 멱등 키**로 AI 요청을 한 번 접수한다. 실행 전 Rule 활성화가 필요하다. Token이나 원문 payload를 출력하지 않는다.

요청 직전에 Kafka Worker 시작 경로의 counter와 consumer group offset을 기준값으로 기록한다. 아래 함수는 fixture와 공유하는 Backend namespace에서 Prometheus endpoint를 읽고, 아직 생성되지 않은 counter series는 0으로 취급한다. 이 실험은 새 Compose 프로젝트에서 다른 AI 요청이나 Backend 재시작 없이 한 실행씩 수행한다.

```powershell
$counterProbe = @'
import json, re, urllib.request
body=urllib.request.urlopen('http://127.0.0.1:8081/actuator/prometheus',timeout=10).read().decode()
series={'kafkaStarts':('finguardops_ai_report_starts_total','source','kafka'),
        'pollingStarts':('finguardops_ai_report_starts_total','source','polling'),
        'kafkaRecordsStarted':('finguardops_kafka_records_total','result','started')}
result={}
for key,(name,label,value) in series.items():
    pattern=re.compile(r'^'+re.escape(name)+r'\{'+label+'="'+value+r'"\}\s+([^\s]+)')
    matches=[float(m.group(1)) for line in body.splitlines() if (m:=pattern.match(line))]
    if len(matches)>1: raise RuntimeError('ambiguous metric series: '+key)
    result[key]=matches[0] if matches else 0.0
print(json.dumps(result,separators=(',',':')))
'@
function Get-KafkaCounters {
  $json = $counterProbe | & docker @compose exec -T local-jwt-fixture python -
  if ($LASTEXITCODE -ne 0) { throw 'Kafka counter scrape failed' }
  return $json | ConvertFrom-Json
}
$countersBefore = Get-KafkaCounters
$countersBefore | Format-List
$groupBefore = & docker @compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group finguardops-ai-report-worker-v1 2>&1
$groupBeforeExit = $LASTEXITCODE
$groupBefore # 첫 commit 전에는 group/offset이 없을 수 있다. 종료 코드도 함께 기록한다.
"groupBeforeExit=$groupBeforeExit"
```

```powershell
$scenario = @'
import datetime as dt, json, sys, urllib.error, urllib.request, uuid
sys.path.insert(0, '/opt/local-jwt-fixture')
from fixture import socket_request
base = 'http://127.0.0.1:8080'
def mint(identity):
    return socket_request({'command':'mint','identity':identity,'variant':'normal'},5)['token']
def call(method,path,token,body=None,key=None,expected=200):
    headers={'Authorization':'Bearer '+token}
    if body is not None: headers['Content-Type']='application/json'
    if key is not None: headers['Idempotency-Key']=key
    data=None if body is None else json.dumps(body,separators=(',',':')).encode()
    req=urllib.request.Request(base+path,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=15) as res: status,raw=res.status,res.read(262145)
    except urllib.error.HTTPError as err: status,raw=err.code,err.read(262145)
    if status!=expected: raise RuntimeError('unexpected HTTP status: '+path+' '+str(status))
    return json.loads(raw)
transaction_token=mint('service-transaction-ingestor')
behavior_token=mint('service-behavior-ingestor')
analyst_token=mint('user-analyst')
customer,sender,recipient='kafka-349-customer','kafka-349-sender','kafka-349-recipient'
now=dt.datetime.now(dt.timezone.utc)
stamp=lambda value:value.isoformat().replace('+00:00','Z')
for kind,seconds,account in [('PASSWORD_CHANGED',-3,None),('TRANSFER_LIMIT_CHANGED',-2,sender)]:
    event={'eventId':str(uuid.uuid4()),'eventType':kind,
           'occurredAt':stamp(now+dt.timedelta(seconds=seconds)),'externalCustomerRef':customer}
    if account is not None: event['accountRef']=account
    call('POST','/api/v1/behavior-events',behavior_token,event,expected=201)
transaction_id=str(uuid.uuid4())
transaction={'transactionId':transaction_id,'transactionType':'ACCOUNT_TRANSFER',
             'amount':'12000000','currencyCode':'KRW','occurredAt':stamp(now),
             'externalCustomerRef':customer,'senderAccountRef':sender,
             'recipientAccountRef':recipient,'channel':'MOBILE_BANKING'}
created=call('POST','/api/v1/transactions',transaction_token,transaction,
             key='kafka-349-'+uuid.uuid4().hex,expected=201)
if created['riskLevel']!='HIGH' or created['riskResponseOutcome']!='ADDITIONAL_AUTH_REQUIRED':
    raise RuntimeError('Rule result mismatch')
case_id=created['caseId']
adopted=call('GET','/api/v1/transactions/'+transaction_id+'/adopted-detection-result',analyst_token)
version=adopted['adoptedResult']['detectionResultVersion']
case=call('GET','/api/v1/cases/'+case_id,analyst_token)
call('PATCH','/api/v1/cases/'+case_id+'/status',analyst_token,
     {'targetStatus':'IN_REVIEW','assigneeRef':str(uuid.uuid4()),
      'reasonCode':'CASE_REVIEW_STARTED','expectedVersion':case['concurrencyVersion']})
accepted=call('POST','/api/v1/cases/'+case_id+'/ai-reports',analyst_token,
              {'detectionResultVersion':version,'regenerationReason':None},
              key='kafka-349-report-'+uuid.uuid4().hex,expected=202)
if accepted['reportStatus']!='PENDING' or accepted['executionShared'] or accepted['cacheHit']:
    raise RuntimeError('new execution was not PENDING')
print(json.dumps({'transactionId':transaction_id,'caseId':case_id,
                  'detectionResultVersion':version,'aiRequestId':accepted['aiRequestId'],
                  'executionId':accepted['executionId'],'acceptance':'202/PENDING'},
                 separators=(',',':')))
'@
$scenarioResult = $scenario | & docker @compose exec -T local-jwt-fixture python -
if ($LASTEXITCODE -ne 0) { throw 'synthetic scenario failed' }
$ids = $scenarioResult | ConvertFrom-Json
$ids | Format-List # 비식별 ID만 출력
```

## 4. Outbox·소비·최종 조회·지표

발행 직후 outbox는 `PENDING`/`CLAIMED`일 수 있고 broker ack 후 `PUBLISHED`가 남는다. 같은 `executionId`의 outbox **한 행**, 실행 한 건, 최초 요청 한 건을 확인한다. SQL에는 출력된 UUID만 넣고 payload·Prompt·비밀을 조회하지 않는다. 아래 psql 명령은 `infra/.env`의 기본 DB/user 이름 `finguardops`를 사용한 경우다. 이름을 바꿨다면 두 인자도 일치시킨다.

```powershell
$executionId = [guid]::Parse($ids.executionId).ToString()
$requestId = [guid]::Parse($ids.aiRequestId).ToString()
$sql = "SELECT o.event_id,o.status,o.attempt_count,e.status FROM ai_report_outbox o JOIN ai_report_execution e ON e.execution_id=o.execution_id WHERE o.execution_id='$executionId';"
& docker @compose exec -T postgresql psql -U finguardops -d finguardops -tAc $sql
if ($LASTEXITCODE -ne 0) { throw 'Outbox query failed' }
$requestCountSql = "SELECT count(*) FROM ai_report_request q JOIN ai_report_execution e ON e.id=q.execution_id WHERE e.execution_id='$executionId' AND q.ai_request_id='$requestId';"
$requestCount = & docker @compose exec -T postgresql psql -U finguardops -d finguardops -tAc $requestCountSql
if ($LASTEXITCODE -ne 0 -or $requestCount.Trim() -ne '1') { throw 'Initial AI request count is not one' }
& docker @compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group finguardops-ai-report-worker-v1
& docker @compose exec -T local-jwt-fixture python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8081/actuator/prometheus',timeout=10).read().decode())" |
  Select-String 'finguardops_(kafka_|ai_report_(starts|pending))'
```

`finguardops_kafka_outbox_records{status}`, 최고 연령, 발행 성공/실패, `finguardops_kafka_records{result}`, `finguardops_kafka_consumer_lag{topic,group}`, DLQ, `finguardops_ai_report_pending_oldest_seconds`도 기록한다. 첫 offset 전이나 broker 조회 실패의 lag `-1`은 0건이 아니다. 필요하면 같은 프로젝트의 `exec -T kafka ... kafka-console-consumer.sh --topic finguardops.ai-report-execution-created.v1.dlq --from-beginning --max-messages 1`로 DLQ를 확인하되 payload 원문은 결과물에 복사하지 않는다.

Analyst token을 fixture 내부에서 다시 발급해 `GET /api/v1/cases/{caseId}/ai-reports/current`를 bounded polling하고 `COMPLETED`, `FALLBACK_COMPLETED`, `FAILED` 중 최종 상태와 `reportSource`, `failureCode`, `fallbackTriggerCode`를 기록한다. `PLATFORM_ADMIN` token으로 `GET /api/v1/ai-report-requests/{aiRequestId}`를 호출해 같은 `executionId`, 저장된 `attempts`, 토큰·비용 NULL 계약을 대조한다. 두 GET 모두 위의 `mint`/`call`처럼 fixture 내부에서 수행하고 token을 host로 반환하지 않는다. Worker 중단 전에 저장하지 못한 호출은 attempt 0건이어도 실제 호출 0건이라고 단정할 수 없다.

```powershell
$caseId = [guid]::Parse($ids.caseId).ToString()
$requestId = [guid]::Parse($ids.aiRequestId).ToString()
$readResult = @'
import json, os, sys, time, urllib.request
sys.path.insert(0,'/opt/local-jwt-fixture')
from fixture import socket_request
def read(path,identity):
    token=socket_request({'command':'mint','identity':identity,'variant':'normal'},5)['token']
    req=urllib.request.Request('http://127.0.0.1:8080'+path,
                               headers={'Authorization':'Bearer '+token})
    with urllib.request.urlopen(req,timeout=10) as res:
        if res.status!=200: raise RuntimeError('unexpected GET status')
        return json.load(res)
case_id,request_id=os.environ['CASE_ID'],os.environ['AI_REQUEST_ID']
deadline=time.monotonic()+300
while True:
    current=read('/api/v1/cases/'+case_id+'/ai-reports/current','user-analyst')
    latest=current['latestRequest']
    if latest is not None and latest['reportStatus'] in ('COMPLETED','FALLBACK_COMPLETED','FAILED'):
        break
    if time.monotonic()>=deadline: raise RuntimeError('AI completion timeout')
    time.sleep(5)
detail=read('/api/v1/ai-report-requests/'+request_id,'user-platform-admin')
if detail['caseId']!=case_id or detail['aiRequestId']!=request_id:
    raise RuntimeError('operator detail identity mismatch')
print(json.dumps({'caseId':case_id,'aiRequestId':request_id,
                  'executionId':detail['executionId'],'reportStatus':detail['reportStatus'],
                  'reportSource':detail['reportSource'],'failureCode':detail['failureCode'],
                  'fallbackTriggerCode':detail['fallbackTriggerCode'],
                  'recordedAttempts':len(detail['attempts']),
                  'estimatedCost':detail['estimatedCost']},separators=(',',':')))
'@
$readResult | & docker @compose exec -T -e "CASE_ID=$caseId" -e "AI_REQUEST_ID=$requestId" local-jwt-fixture python -
if ($LASTEXITCODE -ne 0) { throw 'AI final read failed' }
$countersAfter = Get-KafkaCounters
$groupAfter = & docker @compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group finguardops-ai-report-worker-v1
if ($LASTEXITCODE -ne 0) { throw 'Consumer group offset query failed' }
$groupAfter
$delta = [pscustomobject]@{
  kafkaStarts = $countersAfter.kafkaStarts - $countersBefore.kafkaStarts
  pollingStarts = $countersAfter.pollingStarts - $countersBefore.pollingStarts
  kafkaRecordsStarted = $countersAfter.kafkaRecordsStarted - $countersBefore.kafkaRecordsStarted
}
$delta | Format-List
if ($delta.kafkaStarts -eq 0 -and $delta.pollingStarts -eq 1) {
  Write-Output 'Polling started the isolated execution; Kafka consumer pass excluded.'
} elseif ($delta.kafkaStarts -eq 1 -and $delta.pollingStarts -eq 0 -and $delta.kafkaRecordsStarted -eq 1) {
  Write-Output 'Kafka counter condition met; verify group offset progression below.'
} else {
  Write-Output 'Kafka start inconclusive; investigate mixed executions or missing counter increments.'
}
```

Kafka 시작 성공은 **격리된 이 한 실행에서** `kafkaStarts`와 `kafkaRecordsStarted`가 각각 1 증가하고 `pollingStarts`는 증가하지 않으며, 같은 topic/partition의 group `CURRENT-OFFSET`이 접수 전보다 진행한 경우에만 기록한다. 첫 요청 전 group offset이 없다면 접수 후 숫자 offset이 생겨 진행한 것을 확인한다. `PUBLISHED`, 최종 리포트, lag 0만으로는 Kafka 시작 성공이 아니다. Polling이 먼저 실행을 선점했다면 polling 완료로 기록하고 Kafka 소비 통과에서 제외한다. 다른 AI 실행이 섞였거나 Backend가 재시작되어 counter가 초기화됐거나 counter와 offset이 맞지 않으면 이 전역 counter만으로 해당 `executionId`의 시작 경로를 단정하지 않고 결과를 미확정으로 둔다.

추가 장애 실험은 **각각 새 실행**으로 한다. Broker 중단/복구, ack 뒤 DB 표시 전 중단, consumer 비활성화와 30초 polling 경합, 중복·늦은 이벤트, poison DLQ를 구분한다. Consumer만 끌 때는 먼저 `external-risk-mock`과 `local-jwt-fixture`를 중단하고, `$env:FINGUARDOPS_KAFKA_CONSUMER_ENABLED='false'`로 Backend를 `up -d --no-build --force-recreate backend`한 다음 두 sidecar를 같은 namespace에 다시 만든다. 복구할 때도 같은 순서와 `true` 설정을 사용하고 환경 변수를 제거한다([JWT fixture lifecycle](./local-jwt-auth-e2e-runbook.md#5-topology와-lifecycle)). Sidecar 재생성 시 JWT 서명 키가 바뀌므로 token을 다시 발급한다. Broker는 같은 `$compose`의 `stop kafka`/`start kafka`만 사용한다. `PUBLISHED`는 자동 삭제하지 않고 DLQ 자동 재투입 consumer도 없다. 거래 `201`/멱등 `409`, 사건 상태·최종 판정·업무 Audit, AI `202/200`, outbox·attempt를 전후 대조한다.

```powershell
& docker @compose stop external-risk-mock local-jwt-fixture
& docker @compose rm -f external-risk-mock local-jwt-fixture
$env:FINGUARDOPS_KAFKA_CONSUMER_ENABLED = 'false'
& docker @compose up -d --no-build --force-recreate backend
& docker @compose up -d --no-build external-risk-mock local-jwt-fixture
# 별도 새 AI 실행을 접수하고 30초 polling 및 lag을 관찰한다.
& docker @compose stop external-risk-mock local-jwt-fixture
& docker @compose rm -f external-risk-mock local-jwt-fixture
$env:FINGUARDOPS_KAFKA_CONSUMER_ENABLED = 'true'
& docker @compose up -d --no-build --force-recreate backend
& docker @compose up -d --no-build external-risk-mock local-jwt-fixture
Remove-Item Env:FINGUARDOPS_KAFKA_CONSUMER_ENABLED
& docker @compose ps
```

## 5. 세 경로의 증거 경계와 정리

| 경로 | 인증·Provider | 기록할 증거 | 증명하지 않는 범위 |
| --- | --- | --- | --- |
| [공식 Keycloak Gate](./local-keycloak-auth-e2e-runbook.md) | 실제 Keycloak USER 로그인·모의 Ollama | 승인된 clean commit의 runner `Prepare → Service → Run → Cleanup`, 브라우저·Backend 결과와 ID | Kafka 소비, 실제 Qwen 품질·지연 |
| 이 Kafka 실험 | 로컬 JWT fixture·해당 실행의 Provider | Compose 세 파일, topic/group, `caseId`·`executionId`, outbox·소비·polling·DLQ·metric delta | Keycloak USER 로그인, 별도 Qwen 호출 성공 |
| [실제 Qwen 평가](../08-ai-team-workflow/local-ai-report-evaluation.md) | 호스트 Ollama `qwen3.5:4b`·합성 입력 | 실제 digest·quantization, 컨테이너 접근성, timeout/attempt/출력 한도, 지연·토큰·비용 NULL | Browser Gate 또는 Kafka 소비 |

실제 Qwen 별도 실행에서는 host의 `Invoke-RestMethod http://127.0.0.1:11434/api/tags`로 tag, digest, `details.quantization_level`을 읽는다. 확인한 digest와 quantization을 ignored 로컬 설정의 `FINGUARDOPS_AI_OLLAMA_MODEL_DIGEST`, `FINGUARDOPS_AI_OLLAMA_QUANTIZATION`에 고정한다. 모델 호출 전에 AI Service **컨테이너에서** `http://host.docker.internal:11434/api/tags`로 접속해 같은 tag·digest·quantization을 확인한다. 호스트 Ollama가 `127.0.0.1`에만 listen하면 컨테이너 접근이 실패할 수 있다. 이를 출력 검증 실패로 분류하지 않는다. 설정 제한은 호출당 45초, 최초 포함 최대 2회, 출력 384 token이다. 채택 RULE 근거·허용 checklist 검증을 통과한 결과만 수용한다. 로컬 전력·장비 비용이 미측정이면 비용은 `null`이며 0원/절감액으로 보고하지 않는다.

```powershell
$hostModel = (Invoke-RestMethod http://127.0.0.1:11434/api/tags).models |
  Where-Object name -eq 'qwen3.5:4b'
if (@($hostModel).Count -ne 1) { throw 'host Qwen tag missing or ambiguous' }
if (-not $hostModel.digest -or -not $hostModel.details.quantization_level) { throw 'host Qwen model identity incomplete' }
$hostModel | Select-Object name,digest,@{Name='quantization';Expression={$_.details.quantization_level}}
# 이 두 관측값을 ignored infra/.env에 고정한 뒤 별도 Qwen 프로젝트에서 실행한다.
$containerModelJson = & docker compose -p finguardops-qwen-349-check --env-file infra/.env -f infra/compose.yml run --rm --no-deps --entrypoint python ai-service -c "import json,urllib.request; data=json.load(urllib.request.urlopen('http://host.docker.internal:11434/api/tags',timeout=10)); matches=[m for m in data['models'] if m['name']=='qwen3.5:4b']; assert len(matches)==1; print(json.dumps({'name':matches[0]['name'],'digest':matches[0]['digest'],'quantization':matches[0]['details']['quantization_level']}))"
if ($LASTEXITCODE -ne 0) { throw 'Qwen container reachability check failed; keep owned resources for diagnosis' }
$containerModel = $containerModelJson | ConvertFrom-Json
if ($containerModel.name -cne $hostModel.name -or
    $containerModel.digest -cne $hostModel.digest -or
    $containerModel.quantization -cne $hostModel.details.quantization_level) {
  throw 'host/container Qwen identity mismatch; generation validation not started'
}
$containerModel | Format-List # 비밀이 아닌 모델 식별값만 출력
& docker compose -p finguardops-qwen-349-check --env-file infra/.env -f infra/compose.yml down
if ($LASTEXITCODE -ne 0) { throw 'Qwen check project cleanup failed' }
```

각 경로마다 commit, Compose 조합·프로젝트, Provider, `transactionId`, `caseId`, `aiRequestId`, `executionId`, 상태·측정값·미검증 항목을 **별도 행**에 기록한다. 서로 다른 실행에서 얻은 다른 `caseId`를 동일 사건의 단일 통합 통과로 주장하지 않는다. 이 Issue에는 세 경로의 동일 ID 유지 자동화가 없다.

Kafka 실험의 증거 검토 후 **같은** `$compose`로 소유 자원을 정리한다.

```powershell
& docker @compose down # --volumes 금지
```

`kafka-data`의 topic·offset·DLQ와 PostgreSQL의 outbox·attempt를 확인하기 전에 volume을 삭제하지 않는다. 다른 Compose 프로젝트 및 공식 Keycloak runner 소유 image·receipt·secret은 건드리지 않는다. 공식 Gate의 정리는 그 runner의 `-Mode Cleanup`만 사용한다. 실패 시 비민감 상태·metric·bounded 로그를 먼저 보존하고 JWT·암호·payload 원문은 수집하지 않는다.
