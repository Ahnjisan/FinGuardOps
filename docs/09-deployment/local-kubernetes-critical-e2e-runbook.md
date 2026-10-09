# Issue #371 로컬 Kubernetes CRITICAL 거래·복구 E2E

기존 #371 로컬 적재 경로의 기준 브랜치는 `feature/371-local-k8s-critical-e2e`, 기준 HEAD는 `c01d05ff7277e3e87c740004ec01477727d546d4`이다. 전용 kind 클러스터에서 PostgreSQL, Backend, AI Service, JWT·외부 위험·Ollama 모의 fixture를 실행한다. 아래 #373 registry 경로를 명시하지 않으면 기존 로컬 빌드·kind load 경로를 사용한다. 실제 Qwen, Frontend, Keycloak Browser, Kafka consumer, 전체 관측 스택과 AWS는 검증 범위 밖이다.

## 사전 조건과 소유권

- `python`, `docker`, `kubectl`, `kind`, `git`이 필요하다. `kindest/node` 이미지와 PostgreSQL·Python의 고정 digest 이미지는 먼저 로컬에 준비한다. Backend·AI Dockerfile의 `FROM`/외부 `COPY --from` 이미지는 `preflight`와 `prepare`가 로컬 유무를 기록한다. 누락 시 Docker build가 registry에 접근할 수 있고 Gradle·uv 의존성 다운로드도 발생할 수 있다. build 네트워크 사용 여부는 측정하지 않았으면 `NOT_MEASURED`로 남긴다. 이 Issue의 필수 무 pull 보장은 빌드·적재된 실행별 이미지를 kind node와 Pod가 외부 registry pull 없이 사용하는 구간이다.
- `preflight`는 호스트 가용 RAM 4 GiB, Docker 할당 메모리 6 GiB, 디스크 15 GiB, CPU 4개를 확인한다. `--allow-low-host-memory`는 해당 호출에서 4 GiB 계획 기준만 경고로 낮춘다. 1 GiB 미만은 기본적으로 `HOST_MEMORY_CRITICAL`로 중단한다. 이번 사용자 승인 실행에 한해 `--allow-low-host-memory --allow-critical-host-memory`를 함께 쓰면 1 GiB도 실제 측정값과 부족분을 경고로 기록한다. 이 예외는 Docker 메모리·CPU·디스크, node pressure, 소유권·Secret·이미지·DB/PV 검사를 바꾸지 않는다. 생성된 node는 Ready, CPU 4개, 메모리 5 GiB, ephemeral storage 10 GiB와 pressure 부재가 필요하다.
- 클러스터 이름은 `finguardops-371-<8 hex>`, namespace는 `finguardops-371-local`이다. `.local/state.json` receipt에는 Docker context·daemon ID, kind container ID·Kubernetes node UID, namespace UID, 고정 자원 UID, Secret 내용 hash, source hash, Docker 이미지 ID와 node 이미지 대상 digest를 기록한다.
- 기존 minikube, 다른 프로젝트 컨테이너와 Compose volume은 이 실행의 소유물이 아니다. API 서버는 `127.0.0.1`에만 노출하고 Service는 ClusterIP이다. 수동 port-forward가 필요하면 전용 kubeconfig·context·namespace와 `--address 127.0.0.1`을 명시한다.

## 실행 순서

저장소 루트에서 새 8자리 hex 실행 ID를 만든다. 모든 `run.py` 명령에 같은 `--run-id`를 전달한다. 각 명령은 UTC 시각·stage·종료 코드·안전한 상태만 ignored `.local/execution-<runId>.jsonl`에 기록하며, 기록 실패는 명령 실패다. 실패한 단계 뒤에는 다음 단계가 PASS로 기록되지 않는다. 출력·결과 파일에 Secret, JWT, 고객 식별자를 남기지 않는다. 과거 실행의 누락된 종료 코드는 소급 작성하지 않는다.

예상 밖 stage 예외는 원문 예외·traceback 없이 고정 `UNEXPECTED_STAGE_FAILURE`, UTC 시각, 종료 코드 1로 기록한다. `KeyboardInterrupt`와 stage 내부 `SystemExit`는 각각 `STAGE_INTERRUPTED`, `STAGE_SYSTEM_EXIT`로 구분한다. 증거 파일 자체에 기록할 수 없으면 고정 오류 코드로 명령을 실패 처리하며 PASS를 출력하지 않는다.

```powershell
$runId = python -c "import uuid; print(uuid.uuid4().hex[:8])"
python -B infra/k8s/local-critical/run.py preflight --run-id $runId
python -B infra/k8s/local-critical/run.py prepare --run-id $runId --node-image <로컬 kindest/node:버전@sha256:...>
python -B infra/k8s/local-critical/run.py deploy --run-id $runId
python -B infra/k8s/local-critical/run.py verify --run-id $runId
python -B infra/k8s/local-critical/run.py recover --run-id $runId
python -B infra/k8s/local-critical/run.py storage --run-id $runId
python -B infra/k8s/local-critical/run.py inventory --run-id $runId
```

이번 저메모리 실행에만 `preflight`, `prepare`, `deploy`, `verify`, `recover`의 각 명령에 `--allow-low-host-memory --allow-critical-host-memory`를 명시한다. 실제 측정값과 4 GiB·1 GiB 대비 부족분을 보존한다. 실제 OOM, 프로세스 강제 종료, 호스트 응답 불능, DB 무결성 오류가 발견되면 다음 단계를 시작하지 않는다. 기본 게이트는 유지한다.

`prepare`는 cluster 생성 전에 receipt를 만들고 node 불변 ID를 저장한다. Backend·AI Service는 실행별 고유 태그로 로컬 빌드하고 Docker 이미지 ID를 node containerd 대상 digest와 비교한다. PostgreSQL·Python은 고정 digest 원본의 로컬 `linux/amd64` 플랫폼을 실행별 고유 alias로 태그하고 플랫폼별 archive로 kind에 적재한다. Docker 원본 index ID, alias ID, Docker 플랫폼 manifest digest, node containerd 대상 digest를 따로 대조한다. `imagePullPolicy: Never`만으로 동일성을 주장하지 않는다. archive는 ignored `.local/`에 둔다.

`deploy`는 namespace가 정확히 없을 때만 `create`하며 UID와 소유 label을 receipt에 쓴다. 조회 오류나 create 충돌은 중단한다. Secret은 Git 밖에서 생성해 stdin `create`로 전달하고 last-applied annotation을 만들지 않는다. ConfigMap·workload·발행 Job의 모든 변경 직전에는 Docker·node·namespace·Secret·source·이미지와 예상 자원을 다시 확인한다. Pod는 기록된 Deployment→ReplicaSet 또는 Job의 템플릿·이미지·label과 대조하고 EndpointSlice는 단일 Service owner UID까지 대조한다. PostgreSQL readiness와 Flyway 뒤 v1, v2 Rule 발행 Job이 완료되어야 다음 단계로 간다. 발행 Job에는 AI Service origin이 필요하다.

`verify`는 발행된 v2 유효시각까지 최대 150초 기다린 다음 소유권과 용량을 재검사한다. 시각이 너무 멀거나 대기가 끝나지 않으면 해당 실행은 실패로 기록하며 후속 단계로 진행하지 않는다. 유효시각 이후 합성 거래의 85/CRITICAL·HELD, 채택 Evidence 4개, 사건·Audit, AI 요청·execution·저장 리포트를 API와 DB에서 같은 ID 및 채택 탐지 결과 ID로 대조한 뒤에만 `VERIFIED`로 기록한다. Provider는 `SYNTHETIC_OLLAMA_MOCK`이다. 실제 Qwen 성공이나 미측정 토큰·비용으로 해석하지 않는다.

`recover`는 잘못된 JWT·Secret·이미지, readiness, DB 미준비, 거래 멱등성을 검증한다. AI Service 중단 전 별도 85/CRITICAL·HELD 사건을 준비하고, 중단 중 그 사건의 새 리포트 요청 503과 거래·사건·Audit 불변을 확인한다. 서비스·DB 재시작과 실패 이미지에서 원래 이미지로의 복귀 뒤 readiness와 업무 상태·데이터 보존을 검증한다. 이미지 복귀는 migration 또는 발행된 Rule을 되돌리지 않는다.

`recover`의 JWT·잘못된 Secret, 별도 사건 준비, AI 중단 503·업무 불변, 서비스·DB 중단과 재시작 후 데이터 불변, 실패 이미지 readiness, 원본 이미지 복귀·readiness는 같은 실행 JSONL에 각각 고정된 비민감 하위 결과·UTC 시각·종료 코드 0으로 기록한다. 모든 하위 결과의 기록이 끝나야 최종 `recover/PASS`와 하위 증거 SHA-256을 기록한다. 하위 검사나 기록이 실패하면 최종 PASS가 없다. 과거 `6cc80c8c` 실행의 상위 단계 결과는 이전 코드의 증거이며, 없는 하위 결과를 소급해 채우지 않는다.

## 실패와 정리

실패한 실행의 source hash가 바뀌면 receipt를 고쳐 재개하지 않는다. incident와 receipt·kubeconfig 사본을 ignored `.local/`에 보존하고 새 실행 ID를 사용한다. 실패 실행에 DB·PVC·PV가 있으면 해당 namespace와 node volume을 보존한다. node를 중지해도 anonymous volume과 Docker 이미지가 남는지 확인한다. Docker context와 소유 ID가 불명확하거나 조회가 실패하면 변경·삭제하지 않는다.

`storage`는 PVC/PV UID, claimRef, reclaim 정책을 표시한다. 기본 `Delete` reclaim 정책에서 namespace 삭제는 PV backing data를 지울 수 있다. `Retain`이라도 kind node 안의 backing data는 클러스터 삭제 시 사라질 수 있다. 자동 kind 삭제는 없다.

```powershell
python -B infra/k8s/local-critical/run.py cleanup --run-id $runId --decision preserve
```

`inventory`는 namespace 전체 자원 UID·내용 hash, 모든 namespace의 PVC, 전체 PV의 연결·reclaim·backing 정보와 DB 전체 public table의 건수·내용 fingerprint를 ignored `.local/inventory.json`에 기록한다. DB 행 값과 Secret은 기록하지 않는다. namespace 삭제에는 별도 OWNER 폐기 결정이 필요하다. 운영자가 재고를 검토한 뒤에만 `.local/disposition.json`에 다음 값을 기록한다.

```json
{"runId":"<receipt runId>","namespaceUid":"<receipt namespaceUid>","decision":"delete","inventoryHash":"<inventory hash>"}
```

```powershell
python -B infra/k8s/local-critical/run.py cleanup --run-id $runId --decision delete --confirm-run-id $runId
```

삭제 직전에 Docker·node·namespace·Secret·source·이미지 ID, 모든 namespace의 PVC, 전체 PV, namespace 전체 자원과 DB table fingerprint를 다시 대조한다. 결정·재고가 다르거나 예상 밖 자원이 있으면 보존한다. 코드의 전체·부분 cleanup은 kind 클러스터·이미지·node volume을 자동 삭제하지 않는다. 실행 후 이들의 처리는 전체 backing data와 소유권에 대한 별도 OWNER 판단이 필요하다. 승인된 수동 kind 정리 직전에는 `kind-cleanup-preflight --run-id $runId`로 모든 namespace의 PVC·전체 PV·node backing이 0이고 Docker node·network·volume·이미지 ID가 영수증과 맞는지 기록한다. 승인된 정확한 클러스터만 수동 `kind delete`하고 실제 명령 종료 코드를 보존한다. 고유 이미지 태그와 빈 전용 network를 ID 대조 후 개별 정리한 뒤 `record-kind-cleanup --run-id $runId --command-exit <실제 kind 종료 코드>`를 호출한다. 최종 기록 단계는 node·volume·network·고유 이미지가 모두 사라진 경우에만 PASS다. 전역 prune과 기존 volume 삭제는 금지한다.

`prepare` 또는 namespace 생성 중 실패하고 업무 workload·PVC·PV가 없으며 불변 node·namespace UID가 입증된 경우에만 `cleanup-partial --run-id $runId --confirm-run-id $runId`를 고려한다. 이 명령도 namespace까지만 처리하고 cluster는 보존한다.

## 검증 경계

mock 테스트는 안전 게이트의 실패 반례이며 실제 Kubernetes 통과 증거가 아니다. 실행한 단계의 종료 코드·동일 ID·UID·digest·Pod 재시작·OOM·호스트 RAM을 따로 기록한다. 기존 Compose·Keycloak·Kafka·Qwen 결과를 Kubernetes E2E 통과로 합산하지 않는다. #371 로컬 적재 실행은 GHCR 발행·pull을 증명하지 않는다. AWS 배포, outbox 경고·보존 정책, CRITICAL 오탐률과 Qwen 반복 평가는 별도 작업이다.

## Issue #373: private GHCR digest 인증 pull 경로

이 경로는 #371 로컬 모드를 바꾸지 않는다. OWNER가 두 패키지의 존재·private visibility·저장소 연결·Actions 접근, 계정 예산과 최소 권한 pull 자격 증명 방식을 확인하고 초기 private 발행을 승인해야 한다. 발행 잠금 해제 뒤 관련 새 main push의 `Local Image Build`에서 두 build, 두 publish와 `Verify GHCR digest pair`가 모두 성공해야 한다. PR의 테스트·빌드 결과는 실제 발행 또는 pull 증거가 아니다.

전용 로컬 환경에는 기존 도구 외에 `gh`가 필요하다. `gh`는 해당 Actions run·job·artifact 조회 권한이 있어야 한다. pull 입력은 Git 밖의 접근 제한된 JSON 파일 `{"username":"<GHCR 사용자>","token":"<read:packages 권한의 토큰>"}` 형식이다. 경로만 명령 인수로 넘긴다. 토큰 값은 명령 인수·Git·manifest·receipt·로그에 넣지 않는다. 실행기는 일시 Docker config로 태그와 manifest를 조회하고, 배포 시 전용 namespace의 `ghcr-pull` Secret을 stdin `create`로 생성한다. Backend·AI Service Pod와 Backend Rule 발행 Job만 `imagePullSecrets`를 참조한다. 기존 PostgreSQL·Python 로컬 적재 이미지는 `Never`, registry 서비스 이미지는 `Always`를 사용한다.

```powershell
$runId = python -c "import uuid; print(uuid.uuid4().hex[:8])"
$sha = (git rev-parse HEAD).Trim()  # clean local main; 원격 main과 같아야 함
$workflowRunId = "<성공한 main push Actions run ID>"
$backendDigest = "sha256:<검증된 Backend registry digest>"
$aiDigest = "sha256:<검증된 AI Service registry digest>"
$pullCredentialsFile = "<저장소 밖의 접근 제한된 JSON 파일 경로>"
python -B infra/k8s/local-critical/run.py preflight --run-id $runId
python -B infra/k8s/local-critical/run.py prepare --run-id $runId --image-source registry --node-image <로컬 kindest/node:버전@sha256:...> --workflow-run-id $workflowRunId --backend-image "ghcr.io/ahnjisan/finguardops-backend@$backendDigest" --ai-image "ghcr.io/ahnjisan/finguardops-ai-service@$aiDigest" --pull-credentials-file $pullCredentialsFile
python -B infra/k8s/local-critical/run.py deploy --run-id $runId --pull-credentials-file $pullCredentialsFile
python -B infra/k8s/local-critical/run.py verify --run-id $runId
python -B infra/k8s/local-critical/run.py recover --run-id $runId
python -B infra/k8s/local-critical/run.py storage --run-id $runId
python -B infra/k8s/local-critical/run.py inventory --run-id $runId
```

`prepare`는 클러스터 생성 전에 원격 main SHA, 성공한 main push workflow와 다섯 job, 해당 attempt의 두 artifact, 전체 SHA 태그와 현재 registry digest, 이미지 revision label, linux/amd64 플랫폼 manifest를 대조한다. `deploy`는 namespace·Secret 생성 **전** 같은 증거와 자격 증명을 재검증한다. 태그·digest·artifact 누락, 한쪽 publish 실패, 인증 실패, 입력 변경은 배포 거부다. 새 kind node의 Backend·AI 이미지는 미리 적재하지 않는다. 배포 후 Pod image/imageID와 실제 `Pulled` 이벤트, node containerd 대상, node의 플랫폼 manifest 바이트 digest를 확인한다. index digest와 플랫폼 digest가 다를 수 있으므로 둘을 동일하다고 가정하지 않는다.

registry 복구 시험은 존재하지 않는 Backend digest의 pull 실패와 readiness 실패를 확인한 뒤 영수증의 원래 검증된 digest로 되돌린다. 거래·사건·AI 리포트와 DB 증거 불변을 다시 확인하며 migration 또는 발행 Rule의 롤백으로 해석하지 않는다. `ghcr-pull` Secret의 UID와 내용 hash는 기존 mutation·inventory·cleanup 게이트에 포함된다. Secret을 포함한 namespace나 PVC/PV의 삭제 여부는 위 보존 절차와 OWNER 결정에 따른다. GHCR 패키지, 기존 minikube·다른 프로젝트 자원·Compose volume은 정리 대상이 아니다. 두 실제 발행과 인증 pull E2E 전까지 Issue #373은 미완료다.
