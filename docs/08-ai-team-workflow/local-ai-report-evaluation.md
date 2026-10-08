# 로컬 AI 조사 리포트 평가 — Issue #339

첫 릴리스 제한 시간: Backend 모델 식별 조회는 최대 2초, 비동기 생성
HTTP 읽기는 최대 240초, Worker lease는 최소 300초다. AI Service는
메타데이터/채팅 호출별 최대 45초와 최대 두 번의 생성 시도로 제한한다.
모델 평가에서는 접수 지연과 완료 지연을 따로 측정한다.

첫 후보 `qwen3.5:4b`는 로컬 Ollama에서만 실행한다. 태그 외에 `/api/tags`의
실제 digest·`details.quantization_level`을 기록하고 실행 설정에 고정한다.
Prompt나 출력 한도가 바뀌면 공개 opaque `modelVersion`도 바뀐다.

합성 IN_REVIEW 사건은 채택된 HIGH·CRITICAL RULE 코드·버전·reason code·
기여도만 포함한다. 고객·계좌 참조값, 조사 메모 원문, 원시 JSON, 행동
타임라인은 전달하지 않는다. 같은 입력을 최소 3회 사용해 한국어 문장,
입력 RULE reason code 보존, 없는 거래·고객·행동·판정의 삽입 여부, 요청별
벽시계 시간과 실행 중 최대 RSS/시스템 메모리를 기록한다. 부정확하거나
노트북에서 지나치게 느리면 `gemma3:4b`를 같은 입력으로 비교한다.

로컬 모델이 아직 설치·기동되지 않으면 위 품질·속도·메모리는 **미검증**이다.
공식 Keycloak Gate의 모의 Ollama는 HTTP 계약, 저장, 재조회, 권한과
fallback만 검증하며 모델 품질 측정이 아니다. 로컬 전력·장비 원가도
계측 전에는 미측정이며 0원으로 표시하지 않는다.

## 2026-10-05 actual local Qwen measurement

This is a real local Ollama 0.35.1 run on a Windows laptop with 16.85 GB physical RAM and Intel integrated graphics. Ollama listened on `127.0.0.1:11434` only. The exact pulled tag was `qwen3.5:4b`, digest `d8b0f5e9760cd1682034f292d7ef72ec46f432149be0df7574bf2d6e92e38c04`, quantization `Q4_K_M`, downloaded model size 3,324,173,934 bytes. No external LLM Provider was used. The installer was downloaded from Ollama's official Windows URL and its Windows Authenticode status was `Valid` with signer organization `Ollama Inc.`.

The first unconstrained baseline timed out twice at 45 seconds, returned `TEMPLATE_FALLBACK` after 90.11 seconds, and had no token count. With `think: false`, three synthetic reports completed in Korean in 41.56, 40.88, and 39.84 seconds, but all three asserted facts not present in the input: actual new device use, an exceeded transaction velocity, or a customer behavior pattern. These freeform outputs fail the first release's factuality boundary.

The release therefore accepts model output only when its summary and each RULE reason sentence exactly match a deterministic projection of the adopted evidence. The model can select one or two distinct checklist entries from three allowed public evidence review actions. Other output becomes `TEMPLATE_FALLBACK`. This is real model-assisted checklist selection with a constrained factual surface; it does not demonstrate general Korean freeform report quality. `modelVersion` changes with the observed digest, quantization, prompts, output limit, and Ollama generation settings.

| Final constrained synthetic input | Result | Wall time | Ollama prompt/output tokens | Unsupported factual assertion |
| --- | --- | ---: | ---: | --- |
| HIGH, two adopted RULE codes (`NEW_DEVICE`, `HIGH_AMOUNT`) | `COMPLETED`, `LLM` | 40.23 s | 394 / 153 | None in accepted content |
| CRITICAL, one adopted RULE code (`VELOCITY_ALERT`) | `COMPLETED`, `LLM` | 31.50 s | 351 / 119 | None in accepted content |
| HIGH, opaque adopted RULE code (`SYNTHETIC_SIGNAL`) | `COMPLETED`, `LLM` | 33.33 s | 356 / 122 | None in accepted content |

The sampled peak combined Ollama server and model runner working set was 4,103,798,784 bytes during the intermediate two-RULE run; Windows later reported a model-runner peak of 4,263,497,728 bytes during the constrained two-RULE run. These are local process memory observations, not a Docker Gate measurement or a hardware minimum. Latency has little margin against the configured 45 second provider timeout on this CPU-only laptop; slower machines may fall back. The first physical free-memory observation was about 3.5 GB while tests were running and about 5.1 GB after they ended. Local electricity and equipment cost were not measured, so `estimatedCost` and `costCurrency` remain null; there is no zero-cost claim.

The official Keycloak Docker Gate uses a synthetic Ollama wire fixture and can verify HTTP, persistence, RBAC, and requery. It cannot establish Qwen quality, speed, memory, or real Provider cost. Gemma 3 4B was not downloaded because the final constrained Qwen samples met the defined evidence boundary, and this installation was restricted to the configured `qwen3.5:4b` tag.

## Issue #345 repeated local evaluation

2026-10-06에 실제 로컬 Ollama의 `qwen3.5:4b`를 사용했다. digest는
`d8b0f5e9760cd1682034f292d7ef72ec46f432149be0df7574bf2d6e92e38c04`,
quantization은 `Q4_K_M`이다. 개인정보가 없는 동일 HIGH 합성 입력
(`NEW_DEVICE`, `HIGH_AMOUNT`)과 동일 CRITICAL 합성 입력 (`VELOCITY_ALERT`)을
각각 3회 반복했다. 서비스의 45초 제한과 확인된 timeout 1회 재시도 규칙을
그대로 적용했다. 아래의 지연은 Provider 호출 합계만이 아닌 `generate` 전체 벽시계다.

| 합성 입력 | 반복 | 최종 결과 | 벽시계 지연 | 저장 가능 attempt 결과 | Ollama 입력/출력 토큰 | 채택 근거 일치 | 근거 없는 사실 삽입 |
| --- | ---: | --- | ---: | --- | --- | --- | --- |
| HIGH | 1 | `COMPLETED / LLM` | 72.80초 | `TIMEOUT`, `COMPLETED` | 미측정, 392/152 | 일치 | 수용 결과에 없음 |
| HIGH | 2 | `COMPLETED / LLM` | 28.27초 | `COMPLETED` | 392/152 | 일치 | 수용 결과에 없음 |
| HIGH | 3 | `COMPLETED / LLM` | 27.69초 | `COMPLETED` | 392/152 | 일치 | 수용 결과에 없음 |
| CRITICAL | 1 | `COMPLETED / LLM` | 35.09초 | `COMPLETED` | 351/119 | 일치 | 수용 결과에 없음 |
| CRITICAL | 2 | `COMPLETED / LLM` | 21.30초 | `COMPLETED` | 351/119 | 일치 | 수용 결과에 없음 |
| CRITICAL | 3 | `COMPLETED / LLM` | 24.55초 | `COMPLETED` | 351/119 | 일치 | 수용 결과에 없음 |

최종 fallback 비율은 관측 6건 중 0건(0%)이고 첫 HIGH 실행에는 45.05초
timeout attempt가 1건 있다. 나머지 성공 attempt 지연은 순서대로 27.69,
28.23, 27.66, 35.08, 21.28, 24.52초였다. `keyReasons`의 code 집합이 입력과
같음을 각 실행에서 확인했고, 서비스는 수용 전에 요약과 각 근거 문장을 채택
RULE에서 만든 결정적 안전 문장과 정확히 대조하며 체크리스트를 허용된 문장으로
제한한다. 따라서 없는 사실 삽입 없음은 이 수용 결과에 관한 판정이며 모델의
자유 서술 능력을 입증하지 않는다. timeout attempt의 토큰은 알 수 없다.

실행 중 Windows `llama-server`의 관측 working set은 3,786,686,464 bytes였고
Ollama 본체와 앱은 각각 23,973,888 및 19,988,480 bytes였다. 이는 한 시점의
프로세스 표본이다. 2초 간격 별도 표본의 결합 working set 최고 관측값은
3,831,386,112 bytes였다. 측정 구간에는 다른 테스트 실행도 포함되어 있으므로
Qwen만의 독립 메모리 사용량으로 해석하지 않는다.
실제 전력·장비 비용은 미측정이므로 비용 필드는 계속 NULL이다. 공식 Docker
Gate는 이번 작업에서 실행하지 않았으며, 기존 모의 Ollama Gate 결과를 실제
Qwen 평가에 합산하지 않는다.

## Issue #367: 반복 평가 계약

`infra/local-jwt-fixture/qwen_evaluation_fixtures.json`은 비식별 Rule v1 fixture의
버전, 이벤트 조합, 예상 점수·등급·채택 reason code를 고정한다. 실행기는 매 반복마다
새 고객·계좌 참조, 이벤트·거래 UUID, 거래·리포트 멱등 키를 만들고 실제 거래 접수와
채택 결과가 fixture 기대값에 맞는지 확인한 **뒤에만** AI 리포트를 요청한다.
동일 사건의 캐시 적중이나 실행 공유는 실제 Qwen 반복 횟수로 세지 않고 실패로
분류한다. `amount_only_control`은 15/LOW이므로 사건과 AI 요청이 생기지 않는
비대상 대조군이다. R001+R003은 55/HIGH, 기기 등록을 더하면 75/HIGH,
수취인 등록을 더하면 65/HIGH, 네 Rule 전부는 보안 그룹 상한 때문에 75/HIGH다.
R001+R002만으로는 35/MEDIUM이다. 현행 Rule v1의 최고점은 75여서
CRITICAL fixture의 실제 접수·리포트 생성은 **불가능**하다. 점수·등급이나
채택 Evidence를 임의로 주입하지 않는다. CRITICAL 평가 완료 조건은 그 등급을
만드는 승인된 탐지 계약과 실행 경로가 생긴 뒤 별도로 충족해야 한다.

평가 통과 항목은 리포트 구조, 채택 reason code 집합과 중복 부재, 허용된
체크리스트 1~2개, 알려진 금지 주장 패턴 부재다. 문장 전체를 고정 문자열과
비교하지 않는다. 금지 주장 검사는 한정된 반례 탐지이며 모델의 한국어 자유
서술 전반이나 사실성을 보증하지 않는다. 서비스의 `COMPLETED/LLM`과
`FALLBACK_COMPLETED/TEMPLATE_FALLBACK`을 구분하고 attempt별 결과를 별도 기록한다.
저장 상세 API를 두 번 읽어 같은 순서의 attempt를 대조한다. timeout·연결 실패
뒤에만 두 번째 시도가 가능하고 총 2회를 넘으면 실패다. attempt 0건은 호출
0회를 입증하지 않는다. 입력·출력 토큰이 누락되면 합계는 `null`, 로컬
전력·장비 비용 미측정도 `null`이다.

실행 보고서에는 실행 시작·종료 UTC, fixture 버전, 호스트와 컨테이너에서
대조한 현재 model tag·digest·quantization, 공개 promptVersion·modelVersion,
설정 스냅샷, 사건·요청·실행 ID, 리포트 상태·fallback 원인·벽시계 지연과
저장 attempt를 기록한다. 원문 Prompt·모델 응답, JWT·암호, 고객·계좌 참조와
Provider 오류 원문은 기록하지 않는다. 보고서는 저장소 밖의 실행별 접근 제한
디렉터리에 둔다. 실행 및 DB 대조·정리는
[`local-kafka-ai-report-runbook.md`](../09-deployment/local-kafka-ai-report-runbook.md)의
Issue #367 절을 따른다. Kafka overlay를 쓰지 않으므로 Kafka 발행·소비
성공을 이 평가 결과로 주장하지 않는다.
## Issue #369 Rule v2 CRITICAL smoke

기존 #367의 Rule v1 HIGH 반복 결과는 그대로 보존한다. 별도
`rule_v2_critical_fixture.json`은 네 적격 근거와 85/CRITICAL을 요구한다.
`scoring-policy-v2`와 `feature_version=rule-v2`가 저장된 채택 결과에
일치할 때만 리포트를 요청한다. 리포트는 전달된 Rule code·version·reason과
기여도만 사실 근거로 삼으며 입력에 없는 상세 행동 타임라인이나 최종 사기
판정을 주장하지 않는다. 실제 모델 검증은 tag·digest·quantization과 가용
메모리를 재확인한 뒤 별도 프로젝트에서 한 번만 실행한다. 완료/LLM과
fallback을 구분하고 호출 수·지연·토큰은 관측값만 보고한다. 미측정 비용과
토큰은 0으로 환산하지 않는다.
