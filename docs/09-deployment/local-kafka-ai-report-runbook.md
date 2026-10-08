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

V5의 기본 Rule 네 버전은 DRAFT seed다. 거래 접수 전에 [로컬 Rule 발행 계약](./prometheus-local-scrape-runbook.md#4-로컬-rule-집합-발행)의 one-shot을 **같은 세 파일 조합**으로 수행한다. 발행 전용 Backend에는 `FINGUARDOPS_KAFKA_ENABLED=false`를 지정해 consumer와 outbox dispatcher를 시작하지 않는다. 이 override는 one-shot에만 적용하며 이미 기동한 Backend의 Kafka 경로는 유지한다. 다음은 새 프로젝트에서만 실행한다. 실행 직전 미래 UTC `effectiveFrom`을 계산하며 one-shot 종료 코드 0, 성공 로그와 DB의 PUBLISHED/활성 4/4를 모두 확인한다. 이미 발행된 프로젝트에서는 재실행하지 않는다. 발행 실패를 0점 탐지로 간주하지 않는다.

```powershell
$effectiveFrom = (Get-Date).ToUniversalTime().AddMinutes(5).ToString('yyyy-MM-ddTHH:mm:ssZ')
& docker @compose run --rm --no-deps -T -e SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication -e FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false -e FINGUARDOPS_KAFKA_ENABLED=false backend --spring.main.web-application-type=none --logging.level.org.hibernate.orm.connections.pooling=WARN --finguardops.rule-v1-default-publication.enabled=true --finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1 "--finguardops.rule-v1-default-publication.effective-from=$effectiveFrom"
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
      'reasonCode':'CASE_REVIEW_STARTED','expectedVersion':case['case']['concurrencyVersion']})
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

`finguardops_kafka_outbox_records`의 `status`별 값, 최고 연령, 발행 성공/실패, `finguardops_kafka_records_total`의 `result`별 값, `finguardops_kafka_consumer_lag`, DLQ, `finguardops_ai_report_pending_oldest_seconds`도 기록한다. 첫 offset 전이나 broker 조회 실패의 lag `-1`은 0건이 아니다. DLQ는 아래의 topic offset·지표와 비민감 오류 코드로 확인하며 메시지 원문을 콘솔에 출력하지 않는다.

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
case_id,request_id,transaction_id=(os.environ['CASE_ID'],os.environ['AI_REQUEST_ID'],
                                os.environ['TRANSACTION_ID'])
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

### 4.1 상태별 읽기 전용 진단

같은 Compose 프로젝트와 실행 중인 전용 PostgreSQL을 먼저 확인하고, 아래 SELECT만 실행한다. 전체 집계는 `status`별 건수, 가장 오래된 행의 생성 후 초, 발행 시도 횟수 범위를 보여준다. `OutboxRepository.oldestPendingSeconds()`의 Meter는 `PENDING`·`CLAIMED`·`BLOCKED`를 합친 최고 연령이므로 아래 상태별 최고 연령과 범위가 다르다. 행이 없는 상태는 집계 결과에 나타나지 않는다. 반복 조회의 두 시점과 간격을 기록하지 않았다면 증가율을 산출하지 않는다.

```powershell
$outboxByStatusSql = "SELECT status,count(*) AS records,COALESCE(EXTRACT(EPOCH FROM now()-min(created_at))::bigint,0) AS oldest_seconds,min(attempt_count) AS min_attempts,max(attempt_count) AS max_attempts FROM ai_report_outbox GROUP BY status ORDER BY status;"
& docker @compose exec -T postgresql psql -U finguardops -d finguardops -v ON_ERROR_STOP=1 -c $outboxByStatusSql
if ($LASTEXITCODE -ne 0) { throw 'Read-only outbox status query failed' }
$outboxDetailSql = "SELECT o.event_id,o.execution_id,o.status AS outbox_status,o.attempt_count,o.next_attempt_at,o.lease_until,o.published_at,o.last_failure_code,e.status AS execution_status,q.ai_request_id,q.status AS request_status,c.case_status,c.final_disposition,r.report_status FROM ai_report_outbox o JOIN ai_report_execution e ON e.execution_id=o.execution_id JOIN fraud_case c ON c.id=e.fraud_case_id LEFT JOIN ai_report_request q ON q.execution_id=e.id LEFT JOIN ai_report r ON r.execution_id=e.id WHERE o.execution_id='$executionId' ORDER BY q.ai_request_id;"
& docker @compose exec -T postgresql psql -U finguardops -d finguardops -v ON_ERROR_STOP=1 -c $outboxDetailSql
if ($LASTEXITCODE -ne 0) { throw 'Read-only outbox detail query failed' }
& docker @compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group finguardops-ai-report-worker-v1
if ($LASTEXITCODE -ne 0) { Write-Output 'Consumer group offset unavailable; do not interpret as lag zero' }
& docker @compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic finguardops.ai-report-execution-created.v1.dlq --time -1
if ($LASTEXITCODE -ne 0) { Write-Output 'DLQ end offset unavailable; DLQ count unconfirmed' }
```

`$executionId`는 위 4절에서 `[guid]::Parse`로 정규화한 값이다. 요청 공유로 `q` 행이 여럿이면 같은 outbox·실행이 반복 출력될 수 있으므로 행 수를 outbox 건수로 세지 않는다. 위 SQL은 payload, 리포트 본문, Prompt, 요청자와 고객 식별자를 선택하지 않는다. DLQ end offset은 topic의 위치이며 해당 실행의 poison 건수나 성공적인 재투입 건수가 아니다. 최초 offset·topic 재생성 여부를 모르면 단일 end offset만으로 DLQ 유입 증가도 확정하지 않는다.

같은 시점의 `/actuator/prometheus`에서 `finguardops_kafka_outbox_records`, `finguardops_kafka_outbox_oldest_seconds`, `finguardops_kafka_outbox_published_total`, `finguardops_ai_report_pending_oldest_seconds`, `finguardops_ai_report_starts_total`, `finguardops_kafka_records_total`, `finguardops_kafka_reprocess_attempts_total`, `finguardops_kafka_dlq_total`, `finguardops_kafka_consumer_lag`를 확인한다. outbox 건수는 `status`별, 발행 결과와 소비 결과는 `result`별, 실행 시작은 `source`별로 구분한다. 예를 들어 PromQL에서 발행 결과 두 값을 함께 조회할 때는 `finguardops_kafka_outbox_published_total{result=~"success|failure"}`, 시작 경로는 `finguardops_ai_report_starts_total{source=~"polling|kafka"}`를 사용한다. Counter의 증분은 동일 Backend 프로세스의 전후 샘플에서만 계산하며, 아직 발생하지 않은 결과의 시계열 부재를 0으로 단정하지 않는다. `lag=-1`은 첫 group offset 부재 또는 broker 조회 실패 등으로 **미확인**이며, 0이나 정상 소비를 뜻하지 않는다. DLQ 발행 실패는 `finguardops_kafka_dlq_total` 증가가 없을 수 있으므로 실패 로그의 비민감 오류 분류, 재시도 지표, group offset과 DLQ end offset을 함께 조사한다. 현재 로컬 Grafana 패널·Prometheus alert에는 outbox 전용 패널·경고가 없으므로 임계값 판정 대신 SQL·지표·offset을 기록한다.

| 관측 조합 | 판정과 다음 확인 |
| --- | --- |
| `PENDING`, `attempt_count=0`, `next_attempt_at` 도래 전·후 | 미발행 또는 아직 선점 전이다. 실행·요청 상태를 별도로 확인한다. 오래된 `PENDING`만으로 AI 실행 실패를 선언하지 않는다. |
| `PENDING`이며 `attempt_count>0`·`last_failure_code` 존재 | 발행 재시도 대기다. `next_attempt_at`, 발행 실패 counter, broker 상태를 대조한다. |
| `CLAIMED` | 발행 시도 또는 lease 대기 중이다. `lease_until`과 시도 횟수를 확인한다. broker ack 여부는 DB 상태만으로 알 수 없다. |
| `BLOCKED` | 발행 시도 한도 10회에 도달했다. 실행·요청이 이미 완료됐는지 확인하고 event ID·실패 코드·offset을 보존한다. 자동 재발행 절차는 없다. |
| `PENDING`/`CLAIMED`/`BLOCKED`인데 broker ack 뒤 DB `PUBLISHED` 표시 전 중단 가능 | 같은 event ID가 다시 발행될 수 있는 미확정 구간이다. DB 상태나 발행 실패 counter만으로 broker 미수신을 단정하지 않는다. topic·group offset과 소비 결과를 대조한다. |
| `PUBLISHED`인데 실행이 polling으로 먼저 완료 | broker 발행 표시는 성공했지만 Kafka가 실행을 시작했다는 뜻은 아니다. 격리된 실행의 `polling`·`kafka` 시작 counter 증분과 group offset을 4절 기준으로 대조한다. |
| `PUBLISHED`, 실행·요청은 `PENDING`/`GENERATING` 또는 `FAILED` | 발행 상태와 AI 업무 상태는 별개다. `PUBLISHED`만으로 소비·리포트 완료를 선언하지 않는다. |
| consumer lag `-1` | offset 또는 broker 조회 미확인이다. group 명령과 broker 상태를 확인하며 lag 0으로 대체하지 않는다. |
| poison 이벤트 또는 DLQ 발행 실패 의심 | 잘못된 이벤트는 재시도 없이, 그 밖의 소비 오류는 제한된 재시도 후 DLQ 발행을 시도한다. DLQ 성공 counter·end offset·group offset·비민감 오류를 대조한다. DLQ ack 실패면 성공/복구를 선언하지 않는다. |

수동 복구 여부를 결정하기 **전** 비공개 실행 기록에 checkout commit, Compose 프로젝트와 설정, 측정 시각, `event_id`·`execution_id`·연결 `ai_request_id`, outbox 상태·시도 횟수·다음 시도/lease/발행 시각·실패 코드, 실행·요청·사건 상태와 리포트 유무, 원본 topic/partition의 group current/log-end offset·lag, DLQ end offset과 위 counter의 전후 값을 남긴다. 거래·사건 업무 상태와 Audit도 기존 4절의 범위에서 대조한다. token·암호·고객 식별자·payload·리포트 본문·Prompt·Provider 응답을 출력하거나 공개 Issue에 붙이지 않는다. 이 절은 재발행, DLQ 재투입, 삭제와 보존 기간을 결정하지 않는다. 실행 중인 전용 DB가 없다면 실제 outbox 건수·증가율은 **미측정**으로 표시하고 기존 volume을 mount하지 않는다.

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
| [실제 Qwen 평가](../08-ai-team-workflow/local-ai-report-evaluation.md) | 별도 로컬 JWT fixture·호스트 Ollama `qwen3.5:4b` | 실제 digest·quantization, 컨테이너 접근성, 인증된 생성·저장, timeout/attempt/출력 한도, 지연·토큰·비용 NULL | Browser Gate 또는 Kafka 소비 |

실제 Qwen 실행은 아래 6절의 **별도** Compose 프로젝트에서 검증한다. 기본 `application` network만으로는 호스트 경로가 없고 ignored `infra/.env`가 컨테이너 loopback URL을 가리킬 수 있다. 선택형 Qwen overlay는 AI Service에만 `qwen-host` 경로와 `host.docker.internal` URL을 적용한다. Docker Desktop의 호스트 전달 동작은 환경마다 다르므로 호스트 Ollama의 loopback listen만으로 성공·실패를 단정하지 않고 컨테이너 `/api/tags` 종료 코드와 모델 식별값을 먼저 확인한다.

각 경로마다 commit, Compose 조합·프로젝트, Provider, `transactionId`, `caseId`, `aiRequestId`, `executionId`, 상태·측정값·미검증 항목을 **별도 행**에 기록한다. 서로 다른 실행에서 얻은 다른 `caseId`를 동일 사건의 단일 통합 통과로 주장하지 않는다. 이 Issue에는 세 경로의 동일 ID 유지 자동화가 없다.

Kafka 실험의 증거 검토 후 **같은** `$compose`로 소유 자원을 정리한다.

```powershell
& docker @compose down # --volumes 금지
```

`kafka-data`의 topic·offset·DLQ와 PostgreSQL의 outbox·attempt를 확인하기 전에 volume을 삭제하지 않는다. 다른 Compose 프로젝트 및 공식 Keycloak runner 소유 image·receipt·secret은 건드리지 않는다. 공식 Gate의 정리는 그 runner의 `-Mode Cleanup`만 사용한다. 실패 시 비민감 상태·metric·bounded 로그를 먼저 보존하고 JWT·암호·payload 원문은 수집하지 않는다.

## 6. 별도 실제 Qwen 인증 실행

조합은 `compose.yml` + `compose.qwen-local.yml` + `compose.local-jwt-e2e.yml`이다. Kafka와 Keycloak overlay를 넣지 않는다. `qwen-host`에는 AI Service만 참여한다. ignored `infra/.env`는 수정하지 않고, 현재 호스트 모델 식별값을 이 PowerShell 프로세스 환경에만 둔다. 다음 명령은 저장소 루트에서 같은 PowerShell 세션으로 실행한다.

```powershell
$hostModel = @((Invoke-RestMethod http://127.0.0.1:11434/api/tags -TimeoutSec 10).models |
  Where-Object name -eq 'qwen3.5:4b')
if ($hostModel.Count -ne 1 -or -not $hostModel[0].digest -or
    -not $hostModel[0].details.quantization_level) { throw 'host Qwen identity missing or ambiguous' }
$oldDigest = [Environment]::GetEnvironmentVariable('FINGUARDOPS_AI_OLLAMA_MODEL_DIGEST','Process')
$oldQuantization = [Environment]::GetEnvironmentVariable('FINGUARDOPS_AI_OLLAMA_QUANTIZATION','Process')
$env:FINGUARDOPS_AI_OLLAMA_MODEL_DIGEST = $hostModel[0].digest
$env:FINGUARDOPS_AI_OLLAMA_QUANTIZATION = $hostModel[0].details.quantization_level
$qwenProject = 'finguardops-qwen-349-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
$qwenCompose = @('compose','-p',$qwenProject,'--env-file','infra/.env',
  '-f','infra/compose.yml','-f','infra/compose.qwen-local.yml',
  '-f','infra/compose.local-jwt-e2e.yml')
& docker @qwenCompose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Qwen Compose config failed' }
$missingImage = $false
foreach ($imageTag in @('finguardops-ai-service:local','finguardops-backend:local')) {
  & docker image inspect $imageTag --format '{{.Id}}' 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { $missingImage = $true }
}
if ($missingImage) {
  & docker @qwenCompose build ai-service backend
  if ($LASTEXITCODE -ne 0) { throw 'Qwen local image build failed' }
}
$containerModelJson = & docker @qwenCompose run --rm --no-deps -T --entrypoint python ai-service -c "import json,urllib.request; data=json.load(urllib.request.urlopen('http://host.docker.internal:11434/api/tags',timeout=10)); matches=[m for m in data['models'] if m['name']=='qwen3.5:4b']; assert len(matches)==1; print(json.dumps({'name':matches[0]['name'],'digest':matches[0]['digest'],'quantization':matches[0]['details']['quantization_level']}))"
$probeExit = $LASTEXITCODE # down보다 먼저 기록한다
if ($probeExit -ne 0) { throw 'Qwen container /api/tags failed; record the error and run owned cleanup below' }
$containerModel = $containerModelJson | ConvertFrom-Json
if ($containerModel.name -cne $hostModel[0].name -or
    $containerModel.digest -cne $hostModel[0].digest -or
    $containerModel.quantization -cne $hostModel[0].details.quantization_level) {
  throw 'host/container Qwen identity mismatch; do not generate'
}
$containerModel | Format-List # 공개 모델 식별값만 출력
& docker @qwenCompose up -d --no-build --wait backend local-jwt-fixture external-risk-mock
if ($LASTEXITCODE -ne 0) { throw 'Qwen project health failed; run owned cleanup below' }
& docker @qwenCompose ps
```

`Network is unreachable`은 host까지 경로가 없는 상태, `Connection refused`는 경로가 생긴 뒤 대상 listener가 요청을 받지 않는 상태로 구분한다. timeout·모델 불일치도 각각 기록한다. 접근 검사가 실패하면 생성 요청을 보내지 않는다. 호스트 Ollama listen 또는 방화벽을 바꿔야 하는 환경에서는 제한된 접근 원천과 원상복구를 먼저 확정한다. 이번 검증 환경에서는 Docker Desktop 경로가 기존 호스트 loopback listener까지 전달돼 호스트 설정 변경이 필요하지 않았다.

새 DB의 기본 Rule 네 버전은 DRAFT이다. 다음 one-shot을 **한 번만** 실행하고 성공 종료와 PUBLISHED/활성 4/4를 각각 확인한다. 이미 발행된 프로젝트에는 재실행하지 않는다.

```powershell
$effectiveFrom = (Get-Date).ToUniversalTime().AddMinutes(5).ToString('yyyy-MM-ddTHH:mm:ssZ')
& docker @qwenCompose run --rm --no-deps -T -e SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication -e FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false -e FINGUARDOPS_KAFKA_ENABLED=false backend --spring.main.web-application-type=none --logging.level.org.hibernate.orm.connections.pooling=WARN --finguardops.rule-v1-default-publication.enabled=true --finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1 "--finguardops.rule-v1-default-publication.effective-from=$effectiveFrom"
$ruleExit = $LASTEXITCODE
if ($ruleExit -ne 0) { throw 'Qwen project Rule publication failed' }
$deadline = (Get-Date).AddSeconds(420)
do {
  $ruleCounts = & docker @qwenCompose exec -T postgresql psql -U finguardops -d finguardops -tAc "SELECT count(*) FILTER (WHERE status='PUBLISHED'),count(*) FILTER (WHERE status='PUBLISHED' AND effective_from<=current_timestamp) FROM rule_version;"
  if ($LASTEXITCODE -ne 0) { throw 'Qwen Rule query failed' }
  if ($ruleCounts.Trim() -eq '4|4') { break }
  Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
if ($ruleCounts.Trim() -ne '4|4') { throw 'Qwen Rule activation timed out' }
```

3절의 **`$scenario = @'`부터 닫는 `'@`까지만** 같은 세션에 복사한 뒤, 아래처럼 이 실행만의 합성 참조 접두사로 바꾸어 한 번 실행한다. 3절의 Kafka counter/group 명령과 마지막 실행 명령은 복사하지 않는다. 기대 결과는 두 행동 이벤트와 12,000,000 KRW 거래의 HIGH/`ADDITIONAL_AUTH_REQUIRED`, Analyst `IN_REVIEW`, AI `202/PENDING`이다. JWT는 fixture 내부에서만 발급·사용한다.

```powershell
$scenario = $scenario.Replace('kafka-349','qwen-349')
$scenarioResult = $scenario | & docker @qwenCompose exec -T local-jwt-fixture python -
if ($LASTEXITCODE -ne 0) { throw 'Qwen synthetic scenario failed; do not submit another transaction blindly' }
$ids = $scenarioResult | ConvertFrom-Json
$ids | Format-List # 비식별 ID만 출력
```

Kafka consumer가 없는 이 프로젝트에서는 Worker가 polling 경로로 처리한다. outbox가 `PENDING`이어도 이를 Kafka 발행·소비 증거로 해석하지 않는다. 아래 조회는 Analyst의 현재 리포트와 PLATFORM_ADMIN의 저장된 attempt를 같은 `aiRequestId`로 읽고 비민감 상태·계수만 출력한다.

```powershell
$caseId = [guid]::Parse($ids.caseId).ToString()
$requestId = [guid]::Parse($ids.aiRequestId).ToString()
$readQwen = @'
import json,os,sys,time,urllib.request
sys.path.insert(0,'/opt/local-jwt-fixture')
from fixture import socket_request
def read(path,identity):
    token=socket_request({'command':'mint','identity':identity,'variant':'normal'},5)['token']
    request=urllib.request.Request('http://127.0.0.1:8080'+path,
                                   headers={'Authorization':'Bearer '+token})
    with urllib.request.urlopen(request,timeout=15) as response:
        return response.status,json.load(response)
case_id,request_id,transaction_id=(os.environ['CASE_ID'],os.environ['AI_REQUEST_ID'],
                                   os.environ['TRANSACTION_ID'])
deadline=time.monotonic()+300
while True:
    current_status,current=read('/api/v1/cases/'+case_id+'/ai-reports/current','user-analyst')
    latest=current['latestRequest']
    if latest and latest['reportStatus'] in ('COMPLETED','FALLBACK_COMPLETED','FAILED'): break
    if time.monotonic()>=deadline: raise RuntimeError('Qwen completion timeout')
    time.sleep(5)
admin_status,detail=read('/api/v1/ai-report-requests/'+request_id,'user-platform-admin')
if detail['caseId']!=case_id or detail['aiRequestId']!=request_id:
    raise RuntimeError('Qwen result identity mismatch')
report=current['currentReport']
reason_codes_match=None
if report is not None:
    _,adopted=read('/api/v1/transactions/'+transaction_id+'/adopted-detection-result',
                   'user-analyst')
    reason_codes_match=(sorted(x['reasonCode'] for x in adopted['adoptedResult']['ruleEvidence'])
                        == sorted(x['reasonCode'] for x in report['keyReasons']))
    if not reason_codes_match: raise RuntimeError('Qwen report reason codes differ from adopted RULE evidence')
print(json.dumps({'currentHttp':current_status,'adminHttp':admin_status,
                  'caseId':case_id,'aiRequestId':request_id,'executionId':detail['executionId'],
                  'reportStatus':detail['reportStatus'],'reportSource':detail['reportSource'],
                  'failureCode':detail['failureCode'],
                  'fallbackTriggerCode':detail['fallbackTriggerCode'],
                  'reportPresent':report is not None,'reasonCodesMatch':reason_codes_match,
                  'attempts':[
                    {k:attempt[k] for k in ('attemptNumber','provider','model','outcome',
                         'inputTokens','outputTokens','latencyMs','estimatedCost')}
                    for attempt in detail['attempts']],
                  'inputTokens':detail['inputTokens'],'outputTokens':detail['outputTokens'],
                  'estimatedCost':detail['estimatedCost'],'costCurrency':detail['costCurrency']},
                 separators=(',',':')))
'@
$readQwen | & docker @qwenCompose exec -T -e "CASE_ID=$caseId" -e "AI_REQUEST_ID=$requestId" -e "TRANSACTION_ID=$($ids.transactionId)" local-jwt-fixture python -
if ($LASTEXITCODE -ne 0) { throw 'Qwen final read failed' }
```

저장 건수와 원본 업무 상태도 확인한다. UUID만 SQL에 넣고 report 본문·Prompt·JWT는 출력하지 않는다. PUBLISHED가 아닌 outbox 한 행은 Kafka 비활성 프로젝트의 관측값으로 따로 기록한다.

```powershell
$executionId = [guid]::Parse($ids.executionId).ToString()
$countsSql = "SELECT 'execution',count(*) FROM ai_report_execution WHERE execution_id='$executionId' UNION ALL SELECT 'request',count(*) FROM ai_report_request q JOIN ai_report_execution e ON e.id=q.execution_id WHERE e.execution_id='$executionId' UNION ALL SELECT 'report',count(*) FROM ai_report r JOIN ai_report_execution e ON e.id=r.execution_id WHERE e.execution_id='$executionId' UNION ALL SELECT 'attempt',count(*) FROM provider_call_attempt a JOIN ai_report_execution e ON e.id=a.execution_id WHERE e.execution_id='$executionId' UNION ALL SELECT 'outbox',count(*) FROM ai_report_outbox WHERE execution_id='$executionId';"
& docker @qwenCompose exec -T postgresql psql -U finguardops -d finguardops -tAc $countsSql
if ($LASTEXITCODE -ne 0) { throw 'Qwen persistence query failed' }
$stateSql = "SELECT 'transaction',processing_status,risk_level,risk_response_outcome FROM financial_transaction WHERE transaction_id='$($ids.transactionId)'; SELECT 'case',case_status,coalesce(final_disposition,'NULL'),concurrency_version::text FROM fraud_case WHERE case_id='$caseId'; SELECT 'audit',count(*)::text FROM audit_log WHERE case_id='$caseId'; SELECT 'outbox',status,attempt_count::text FROM ai_report_outbox WHERE execution_id='$executionId';"
& docker @qwenCompose exec -T postgresql psql -U finguardops -d finguardops -tAc $stateSql
if ($LASTEXITCODE -ne 0) { throw 'Qwen business-state query failed' }
```

`COMPLETED/LLM`은 수용된 리포트의 검증 통과를 뜻하고, `FALLBACK_COMPLETED`라면 `fallbackTriggerCode`와 각 attempt outcome을 그대로 기록한다. 호출당 45초, 최초 포함 최대 2회, 출력 384 token 설정을 넘기지 않는다. timeout attempt의 토큰이 미측정이면 운영 상세의 합산 토큰·지연이 `null`일 수 있다. 로컬 전력·장비 비용이 미측정이면 `estimatedCost=null`이며 0원 또는 절감액으로 주장하지 않는다. 이 Qwen 사건의 ID를 앞선 Kafka·Keycloak 사건의 단일 통합 증거로 묶지 않는다.

성공·실패 모두 비민감 증거를 기록한 뒤 **이 프로젝트만** 정리한다. `--volumes`는 사용하지 않는다. 컨테이너 접근에 성공해 호스트 listen·방화벽을 바꾸지 않았다면 복원할 호스트 설정은 없다. 과정에서 별도 변경을 했다면 그 변경을 먼저 원상복구하고 listen·방화벽 상태를 재확인한다. PowerShell 프로세스 환경의 digest·quantization도 이전 값으로 복원한다. 첫 명령이 실패해도 이어서 환경 복원을 수행한다.

```powershell
& docker @qwenCompose down
$downExit = $LASTEXITCODE
if ($null -eq $oldDigest) { Remove-Item Env:FINGUARDOPS_AI_OLLAMA_MODEL_DIGEST -ErrorAction SilentlyContinue }
else { $env:FINGUARDOPS_AI_OLLAMA_MODEL_DIGEST = $oldDigest }
if ($null -eq $oldQuantization) { Remove-Item Env:FINGUARDOPS_AI_OLLAMA_QUANTIZATION -ErrorAction SilentlyContinue }
else { $env:FINGUARDOPS_AI_OLLAMA_QUANTIZATION = $oldQuantization }
if ($downExit -ne 0) { throw 'Qwen project cleanup failed' }
& docker ps -a --filter "label=com.docker.compose.project=$qwenProject" --format '{{.Names}}'
& docker network ls --filter "label=com.docker.compose.project=$qwenProject" --format '{{.Name}}'
& docker volume ls --filter "label=com.docker.compose.project=$qwenProject" --format '{{.Name}}'
```
# Issue #363 BLOCKED 단건 재대기 확인

고유 Compose 프로젝트와 같은 실행의 `executionId`, `eventId`를 기록하고 PLATFORM_ADMIN
JWT로 `GET /api/v1/ai-report-outbox/{executionId}`를 조회한다. `requeueAllowed=true`와
연결 요청·실행 `PENDING`, 결과·attempt 부재, v1 관계를 확인한 경우에만
`POST /api/v1/ai-report-outbox/{eventId}/requeue`에 관측한 `executionId`와 `BLOCKED`를
전달한다. `202`는 DB `PENDING` 한 행과 V19 이력 한 행의 수락이다. broker 중단 중에도
수락될 수 있으므로 발행·소비 완료로 기록하지 않는다. 같은 요청 반복이나 polling
선점으로 조건이 바뀌면 `409`를 정상 거부로 기록한다. broker 복구 뒤 같은 ID의
outbox·이력·실행·연결 요청, topic/group offset, 시작 경로 counter를 대조한다.
`PUBLISHED`만으로 리포트 완료를 선언하지 않는다. token·payload·고객 식별자·Prompt·
Provider 원문은 수집하지 않는다. 프로젝트 소유 컨테이너·네트워크·one-off만 종료하고
PostgreSQL 및 Kafka volume은 보존한다. `down --volumes`와 prune은 사용하지 않는다.

## Issue #365 DLQ 단건 복구 로컬 증거

매 실행 고유 Compose 프로젝트명으로 기존 `compose.yml`, `compose.kafka-local.yml`,
`compose.local-jwt-e2e.yml` 세 파일만 결합한다. 새 프로젝트의 PostgreSQL/Kafka
volume만 사용하고 다른 실행의 volume은 mount하지 않는다. 먼저 topic describe에서
DLQ topic ID를 확인하고 지정 partition·offset 한 건을 `PLATFORM_ADMIN` JWT로
`GET /api/v1/ai-report-dlq/{topicId}/{partition}/{offset}` 조회한다. JWT는 fixture
내부에서만 발급·사용한다. 응답의 `sourceVerified`, `sourceRecovered`, 분류,
V18/V15 관계 및 `replayAllowed`를 기록한다. 구형 `UNKNOWN`·poison은 격리하고
`QUARANTINE` V20 이력 한 행만 확인한다. `PRE_CLAIM_TRANSIENT`에만 관측 분류로
`POST .../replay`를 호출한다. `202`는 V20 조치·의도 한 쌍이다.

Broker 중단/복구와 Backend 재시작은 각각 독립된 새 실행에서 시험한다. broker
ack 전후 중단은 `PENDING/CLAIMED/ACKED/BLOCKED/SKIPPED`, 원본/replay topic
offset, group committed offset, 실행 시작 경로 및 V15 report·attempt를 대조한다.
`ACKED`만으로 Consumer·Provider 완료를 선언하지 않는다. polling 선점은
`startSource=POLLING`으로 표시하며 재처리 성과에 넣지 않는다. Provider 호출 뒤
저장 전 중단은 `WORKER_INTERRUPTED`와 실제 호출 미확정으로 기록하고 재투입하지
않는다. token·payload·Prompt·고객/계좌·모델/Provider 원문을 출력하지 않는다.

새 프로젝트만 동일한 Compose 파일 조합과 프로젝트명으로 `down`하고 컨테이너,
네트워크, one-off 잔여를 확인한다. PostgreSQL/Kafka volume을 보존하며
`down --volumes` 또는 prune을 사용하지 않는다. 운영 화면은 API와 같은 안전
metadata, 조치·발행·업무 상태만 보인다.

Issue #365의 격리된 JWT/Kafka/PostgreSQL 자동 검증은 저장소 루트에서
`python infra/local-jwt-fixture/verify_dlq_recovery_e2e.py`로 실행한다. 이 검증은
`compose.yml`, `compose.local-jwt-e2e.yml`, `compose.kafka-local.yml`,
`compose.dlq-recovery-e2e.yml`을 고유 프로젝트로 결합한다. 최초 소비를 별도
consumer로 확인한 후 PostgreSQL을 잠시 중단하고 canonical 이벤트를 한 번 더
발행하여 실제 Consumer의 claim 이전 예외와 DLQ 분류를 만든다. 원본 payload는
검증 프로세스의 메모리 안에서만 사용하며 출력하지 않는다. 승인 후 V20 의도,
broker ack 좌표, 실행 최종 상태를 연결해 확인한다. 종료는 같은 Compose 조합의
`down --remove-orphans`이며 volume은 삭제하지 않는다.
E2E overlay의 긴 polling 시작 지연과 2초 DB connection timeout은 격리 fixture에서
Kafka 선점과 claim 이전 DB 장애를 재현하기 위한 설정이다. 기본 Compose와 운영 설정에는
적용되지 않는다. 이 검증은 유료 Provider를 사용하지 않지만 기록된 attempt 수가 실제
외부 호출 수를 증명하지는 않는다.

## Issue #367 실제 Qwen 반복 평가

이 절은 저장소 루트의 PowerShell에서 실행한다. 조합은 기존 6절과 같은
`compose.yml` + `compose.qwen-local.yml` + `compose.local-jwt-e2e.yml`이다.
Kafka·Keycloak overlay는 포함하지 않는다. 아래 평가는 실제 호스트 Ollama가
기동되고 Docker Desktop Linux Engine이 응답할 때만 진행한다. 모델 재설치나
기존 모델·volume 삭제는 절차에 없다. 현재 Rule v1로는 CRITICAL을 만들 수
없으므로 `amount_only_control`과 여러 HIGH 조합만 실제 접수한다.

### 준비·현재 모델 식별

```powershell
git status --short
docker version --format '{{.Server.Version}}'
$hostModel = @((Invoke-RestMethod http://127.0.0.1:11434/api/tags -TimeoutSec 10).models |
  Where-Object name -eq 'qwen3.5:4b')
if ($hostModel.Count -ne 1 -or -not $hostModel[0].digest -or
    -not $hostModel[0].details.quantization_level) { throw 'Current host Qwen identity unavailable' }
$oldDigest = [Environment]::GetEnvironmentVariable('FINGUARDOPS_AI_OLLAMA_MODEL_DIGEST','Process')
$oldQuantization = [Environment]::GetEnvironmentVariable('FINGUARDOPS_AI_OLLAMA_QUANTIZATION','Process')
$env:FINGUARDOPS_AI_OLLAMA_MODEL_DIGEST = $hostModel[0].digest
$env:FINGUARDOPS_AI_OLLAMA_QUANTIZATION = $hostModel[0].details.quantization_level
$qwenProject = 'finguardops-qwen-367-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
$qwenCompose = @('compose','-p',$qwenProject,'--env-file','infra/.env',
  '-f','infra/compose.yml','-f','infra/compose.qwen-local.yml',
  '-f','infra/compose.local-jwt-e2e.yml')
& docker @qwenCompose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration invalid' }
$containerJson = & docker @qwenCompose run --rm --no-deps -T --entrypoint python ai-service -c "import json,urllib.request; data=json.load(urllib.request.urlopen('http://host.docker.internal:11434/api/tags',timeout=10)); matches=[m for m in data['models'] if m['name']=='qwen3.5:4b']; assert len(matches)==1; print(json.dumps({'name':matches[0]['name'],'digest':matches[0]['digest'],'quantization':matches[0]['details']['quantization_level']}))"
if ($LASTEXITCODE -ne 0) { throw 'AI Service container cannot read current host model' }
$containerModel = $containerJson | ConvertFrom-Json
if ($containerModel.name -cne $hostModel[0].name -or
    $containerModel.digest -cne $hostModel[0].digest -or
    $containerModel.quantization -cne $hostModel[0].details.quantization_level) {
  throw 'Host/container model identity mismatch; do not generate'
}
```

이 사전 검사는 컨테이너 하나를 일시 생성하므로 그 전후의 프로젝트 소유
one-off 잔여를 확인한다. 실패하면 생성 요청을 보내지 않고 아래 정리 단계로
간다. `infra/.env`의 비밀은 출력하지 않는다. 호스트 메모리·디스크 여유와
다른 Compose 프로젝트의 포트·자원을 확인한다. 과거 평가의 digest를 현재
값으로 가정하지 않는다. 모델 식별은 각 생성 때 AI Service도 다시 검사한다.

### 고유 프로젝트 실행·평가 보고서

```powershell
& docker @qwenCompose up -d --build --wait --wait-timeout 180 backend local-jwt-fixture external-risk-mock
if ($LASTEXITCODE -ne 0) { throw 'Qwen project did not become healthy' }
$backendContainer = "$qwenProject-backend-1"
& docker network connect "${qwenProject}_qwen-host" $backendContainer
if ($LASTEXITCODE -ne 0) { throw 'JWT fixture host-model route failed' }
$fixtureJson = & docker @qwenCompose exec -T local-jwt-fixture python -c "import json,urllib.request; d=json.load(urllib.request.urlopen('http://host.docker.internal:11434/api/tags',timeout=10)); m=[x for x in d['models'] if x['name']=='qwen3.5:4b']; assert len(m)==1; print(json.dumps({'name':m[0]['name'],'digest':m[0]['digest'],'quantization':m[0]['details']['quantization_level']}))"
if ($LASTEXITCODE -ne 0) { throw 'JWT fixture cannot read current host model' }
$fixtureModel = $fixtureJson | ConvertFrom-Json
if ($fixtureModel.name -cne $hostModel[0].name -or
    $fixtureModel.digest -cne $hostModel[0].digest -or
    $fixtureModel.quantization -cne $hostModel[0].details.quantization_level) {
  throw 'JWT fixture model identity mismatch; do not generate'
}
```

`local-jwt-fixture`는 Backend의 네트워크 네임스페이스를 공유한다. Backend의 기본
격리 네트워크에서는 `host.docker.internal`에 접근할 수 없으므로, 이 고유
프로젝트의 Backend만 `qwen-host`에 일시 연결한다. 평가기가 같은 URL에서
현재 모델 식별값을 다시 확인한 뒤 생성한다. 아래의 같은 조합 `down`은 이
연결과 프로젝트 네트워크도 함께 제거한다.

신규 프로젝트에는 활성 Rule이 없다. 먼저 발행 상태를 조회하고 0/0일 때만
기존 Rule v1 one-shot을 한 번 실행한다. 이미 발행된 프로젝트에서는 재실행하지
않는다. 발행 직후 아직 유효 시각 전이면 최대 420초 기다려 4/4를 확인한다.

```powershell
$ruleSql = "SELECT count(*) FILTER (WHERE status='PUBLISHED'),count(*) FILTER (WHERE status='PUBLISHED' AND effective_from<=current_timestamp) FROM rule_version;"
$ruleCounts = (& docker @qwenCompose exec -T postgresql psql -U finguardops -d finguardops -tAc $ruleSql).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Rule inventory failed' }
if ($ruleCounts -eq '0|0') {
  $effectiveFrom = (Get-Date).ToUniversalTime().AddMinutes(5).ToString('yyyy-MM-ddTHH:mm:ssZ')
  & docker @qwenCompose run --rm --no-deps -T -e SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication -e FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false -e FINGUARDOPS_KAFKA_ENABLED=false backend --spring.main.web-application-type=none --logging.level.org.hibernate.orm.connections.pooling=WARN --finguardops.rule-v1-default-publication.enabled=true --finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1 "--finguardops.rule-v1-default-publication.effective-from=$effectiveFrom"
  if ($LASTEXITCODE -ne 0) { throw 'Rule publication failed' }
} elseif ($ruleCounts -ne '4|4' -and $ruleCounts -ne '4|0') { throw 'Unexpected Rule publication state' }
$deadline = (Get-Date).AddSeconds(420)
do {
  $ruleCounts = (& docker @qwenCompose exec -T postgresql psql -U finguardops -d finguardops -tAc $ruleSql).Trim()
  if ($LASTEXITCODE -ne 0) { throw 'Rule activation query failed' }
  if ($ruleCounts -eq '4|4') { break }
  Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
if ($ruleCounts -ne '4|4') { throw 'Rule activation timed out' }
```

이후 보고서를 저장소 밖의 고유 디렉터리에 만들고 현재 PowerShell 사용자에게만
접근을 허용한다.

```powershell
$reportRoot = Join-Path $env:LOCALAPPDATA 'FinGuardOps\qwen-evaluation'
$runDir = Join-Path $reportRoot $qwenProject
if (Test-Path -LiteralPath $runDir) { throw 'Report directory already exists' }
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls.exe $runDir /inheritance:r /grant:r "$($currentUser):(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Report directory ACL failed' }
function Assert-QwenStoredResult($runReport) {
  foreach ($row in @($runReport.results | Where-Object reportEvaluated)) {
    $executionId = [guid]::Parse($row.executionId).ToString()
    $caseId = [guid]::Parse($row.caseId).ToString()
    $transactionId = [guid]::Parse($row.transactionId).ToString()
    $attemptSql = "SELECT json_build_object('attemptNumber',a.attempt_number,'provider',a.provider,'model',a.model_digest,'outcome',a.outcome,'inputTokens',a.input_tokens,'outputTokens',a.output_tokens,'latencyMs',a.latency_ms,'estimatedCost',a.estimated_cost::text,'costCurrency',a.cost_currency)::text FROM provider_call_attempt a JOIN ai_report_execution e ON e.id=a.execution_id WHERE e.execution_id='$executionId' ORDER BY a.attempt_number;"
    $dbLines = @(& docker @qwenCompose exec -T postgresql psql -U finguardops -d finguardops -tA -c $attemptSql)
    if ($LASTEXITCODE -ne 0) { throw 'DB attempt query failed' }
    $dbAttempts = @($dbLines | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
    $check = @{ reported = @($row.attempts); persisted = $dbAttempts; digest = $runReport.model.digest } | ConvertTo-Json -Depth 8 -Compress
    $check | python -B -c 'import json,sys; sys.path.insert(0,"infra/local-jwt-fixture"); import evaluate_qwen_reports as e; p=json.load(sys.stdin); e.compare_persisted_attempts(p["reported"],p["persisted"],p["digest"])'
    if ($LASTEXITCODE -ne 0) { throw 'DB/API/report attempt mismatch' }
    $stateSql = "SELECT (SELECT count(*) FROM audit_log WHERE target_type='FRAUD_CASE' AND target_id='$caseId'),(SELECT case_status FROM fraud_case WHERE case_id='$caseId'),(SELECT processing_status FROM financial_transaction WHERE transaction_id='$transactionId');"
    $values = (& docker @qwenCompose exec -T postgresql psql -U finguardops -d finguardops -tAc $stateSql).Trim().Split('|')
    if ($LASTEXITCODE -ne 0 -or $values.Count -ne 3 -or
        [int]$values[0] -ne $row.baselineAuditCount -or
        $values[1] -cne $row.baselineCaseStatus -or
        $values[2] -cne $row.baselineTransactionStatus) {
      throw 'DB/API/report business-state mismatch'
    }
  }
}
$smokePath = Join-Path $runDir 'smoke.json'
$smokeText = & docker @qwenCompose exec -T local-jwt-fixture python /opt/local-jwt-fixture/evaluate_qwen_reports.py --model-tag $hostModel[0].name --digest $hostModel[0].digest --quantization $hostModel[0].details.quantization_level --fixture-id security_change_high --repetitions 1
$smokeExit = $LASTEXITCODE
if (-not $smokeText) { throw 'No smoke report returned' }
$smoke = ($smokeText -join "`n") | ConvertFrom-Json
$smokeText | Set-Content -LiteralPath $smokePath -Encoding utf8
if ($smokeExit -ne 0 -or $smoke.status -ne 'COMPLETED' -or
    @($smoke.results | Where-Object reportEvaluated).Count -ne 1) {
  throw "Smoke incomplete; inspect restricted report $smokePath"
}
Assert-QwenStoredResult $smoke
$reportPath = Join-Path $runDir 'evaluation.json'
$reportText = & docker @qwenCompose exec -T local-jwt-fixture python /opt/local-jwt-fixture/evaluate_qwen_reports.py --model-tag $hostModel[0].name --digest $hostModel[0].digest --quantization $hostModel[0].details.quantization_level --repetitions 3
$evaluationExit = $LASTEXITCODE
if (-not $reportText) { throw 'No evaluation report returned' }
$report = ($reportText -join "`n") | ConvertFrom-Json
if (-not $report.schemaVersion -or $report.fixtureVersion -ne 'rule-v1-qwen-eval-1') {
  throw 'Evaluation report contract invalid'
}
$reportText | Set-Content -LiteralPath $reportPath -Encoding utf8
if ($evaluationExit -ne 0 -or $report.status -ne 'COMPLETED') {
  throw "Evaluation incomplete; inspect restricted report $reportPath"
}
Assert-QwenStoredResult $report
$report.results | Select-Object fixtureId,repetition,status,requestStatus,
  reportSource,aiWallLatencyMs,fallbackTriggerCode
```

Python 실행기는 JWT를 fixture의 private socket 안에서 발급·사용하고 stdout에
비민감 보고서 JSON 한 개만 출력한다. HTTP·Provider 오류 원문은 기록하지
않는다. 중도 실패 시 `status=INCOMPLETE`와 안전한 `errorCode`, 진행 중
식별자만 보존한다. `COMPLETED`는 평가 절차 종료를 뜻하며 개별 리포트의
`COMPLETED/LLM`과 구별한다. `attempts=[]`는 저장된 attempt 0건이지
Provider 호출 0회 증거가 아니다. 모델 tag·digest·quantization과
`promptVersion`·`modelVersion`, 설정 스냅샷을 함께 대조한다. 메모리·전력은
이 평가기가 계측하지 않는다.

### 저장 결과·업무 불변·정리

실행기는 같은 `aiRequestId`의 PLATFORM_ADMIN 상세를 두 번 읽어 attempt별
순서·결과·지연·토큰·비용을 대조하고, AI 요청 전후의 사건 상태·거래 상태·
사건 Audit 목록을 비교한다. 위의 `Assert-QwenStoredResult`를 smoke와 전체 반복
보고서에 동일하게 적용한다. 이 함수는 `ai_report_execution.execution_id`로
연결한 `provider_call_attempt`를 `attempt_number` 순서대로 읽어 운영 상세
API에서 저장한 보고서의 결과·지연·토큰·비용 null까지 필드별 대조한다. SQL에는
UUID만 넣고 attempt 메타데이터만 SELECT하며 원문 payload·Prompt·JWT는
조회하지 않는다. API의 입력·출력·전체 토큰 합계는 평가기가 attempt별
측정값과 비교한다. 저장 attempt가 0건이면 Backend 상세 API의 집계는 0이지만
실제 Provider 호출 0회라는 증거로 해석하지 않는다. `/api/tags` 조회가
45초에 timeout되면 채팅 attempt 없이 `LLM_TIMEOUT`도 가능하다.

성공률은 `COMPLETED/LLM` 건수와 실제 AI 요청 건수를 함께 적고, fallback·
timeout attempt 건수, attempt별 지연 및 요청 전체 벽시계 지연의 표본 수를
따로 적는다. 토큰 `null`과 비용 `null`을 0으로 환산하지 않는다. 이 조합에서
outbox는 Kafka 발행·소비 증거가 아니다. 실패 시 보고서의 안전한 분류와
기존 DB 상태를 보존한 뒤 같은 프로젝트만 정리한다.

```powershell
& docker @qwenCompose down --remove-orphans
$downExit = $LASTEXITCODE
if ($null -eq $oldDigest) { Remove-Item Env:FINGUARDOPS_AI_OLLAMA_MODEL_DIGEST -ErrorAction SilentlyContinue }
else { $env:FINGUARDOPS_AI_OLLAMA_MODEL_DIGEST = $oldDigest }
if ($null -eq $oldQuantization) { Remove-Item Env:FINGUARDOPS_AI_OLLAMA_QUANTIZATION -ErrorAction SilentlyContinue }
else { $env:FINGUARDOPS_AI_OLLAMA_QUANTIZATION = $oldQuantization }
if ($downExit -ne 0) { throw 'Owned project cleanup failed' }
& docker ps -a --filter "label=com.docker.compose.project=$qwenProject" --format '{{.Names}}'
& docker network ls --filter "label=com.docker.compose.project=$qwenProject" --format '{{.Name}}'
& docker volume ls --filter "label=com.docker.compose.project=$qwenProject" --format '{{.Name}}'
```

앞의 두 목록은 비어 있어야 한다. 마지막 목록은 **보존되는** 이 프로젝트의
named volume 목록이다. `--volumes`, prune, 기존 프로젝트 volume mount·삭제를
수행하지 않는다. 접근 제한 보고서와 모델 식별값을 기록한 뒤 운영자가 개별
volume 보존·정리 여부를 별도 절차로 판단한다.
