# Rule 실행 오케스트레이션 내부 계약

## 1. 문서 목적과 현재 구현 상태

이 문서는 [GitHub Issue #104](https://github.com/Ahnjisan/FinGuardOps/issues/104)에
따라 FinGuardOps AI Service의 Rule 실행 오케스트레이션 하위 계층 계약을
정의한다. 호출자가 이미 내부 `RuleId`로 해석하고 순서를 확정한 실행 계획을
받아, 등록된 evaluator capability를 검증하고 결정적인 순서로 실행하는 범위가
대상이다.

문서 상태와 현재 구현 상태는 다음과 같다.

- Rule 실행 오케스트레이션 계약: 문서 정의 완료
- R001~R004 순수 evaluator: 구현됨
- 불변 `RuleEvaluatorRegistry`: 구현됨
- `RuleExecutionOrchestrator` Python 구현: 구현됨
- 점수 합산·위험 등급·Evidence 변환: 미구현 후속 범위
- FastAPI 외부 분석 API와 Spring Boot 연동: 미구현 후속 범위

현재 구현된 evaluator와 Registry의 기준은
[`ai-service/src/finguardops_ai/rules/v1/`](../../ai-service/src/finguardops_ai/rules/v1/)에
있다. 공식 Rule 조건과 시스템 책임 경계는
[Rule v1 탐지 계약](./rule-v1-detection-contract.md)을 따른다. 이 문서는 해당
공식 계약을 변경하거나 Rule 실행 구현 완료를 의미하지 않는다.

## 2. 주요 용어와 책임 계층

### 2.1 내부 Rule ID

`RuleId`는 AI Service가 구현한 evaluator capability를 식별하는 내부 ID다.
현재 값은 `R001`, `R002`, `R003`, `R004`이며 `str` 기반 `StrEnum`이다.
DB의 불변 논리 식별자인 `ruleCode` 또는 특정 `RuleVersion`의 업무 식별자와
같은 개념이 아니다.

### 2.2 ordered 실행 계획

ordered 실행 계획은 호출자가 선택하고 실행 순서를 정한 내부 Rule ID의
유한한 Sequence다. 오케스트레이터는 이 순서를 정책적으로 변경하거나
Registry의 기본 등록 순서로 다시 정렬하지 않는다.

### 2.3 evaluator

evaluator는 하나의 `RuleEvaluationInput`을 받아 하나의
`RuleEvaluatorResult`를 반환하는 동기 callable이다. R001~R004 evaluator는
서로의 실행 결과에 의존하지 않는 개별 순수 함수다.

### 2.4 책임 계층

Rule 실행 흐름의 책임은 다음과 같이 분리한다.

```text
선행 계층
→ 활성 RuleVersion 조회·고정
→ ruleCode와 내부 RuleId 연결
→ ordered 내부 RuleId 실행 계획 구성

Rule 실행 오케스트레이터
→ Registry evaluator capability 조회
→ 실행 전 전체 검증
→ 순차 evaluator 실행
→ ordered raw RuleEvaluationResult tuple 반환

후속 계층
→ RuleVersion·weight 결합
→ 점수·위험 등급 계산
→ Evidence 변환
→ 외부 응답·영속 처리
```

선행 계층과 후속 계층의 구체 구현은 이 문서의 범위가 아니다.
활성 RuleVersion 업무 snapshot을 exact `ruleCode → RuleId` mapping과
결정적인 순서로 변환하는 선행 계층 계약은
[RuleVersion 기반 Rule 실행 계획 내부 계약](./rule-execution-plan-contract.md)에서
정의한다. 실행 계획 생성은 이 오케스트레이터의 책임이 아니다.

## 3. Registry와 오케스트레이터의 책임 분리

`RuleEvaluatorRegistry`는 다음 책임만 가진다.

- 내부 Rule ID에 대응하는 evaluator capability 조회
- 지원하는 내부 Rule ID 목록 제공
- Registry 생성 시 중복 등록 거부
- 생성 후 등록 mapping과 지원 ID 순서의 불변성 유지
- exact·case-sensitive ID 조회와 미지원 ID 거부

Registry는 다음을 수행하지 않는다.

- 현재 실행할 Rule 선택
- DB 활성 상태나 적용 기간 확인
- RuleVersion 조회 또는 snapshot 구성
- 실행 순서 결정
- 여러 evaluator 실행
- 점수·위험 등급·Evidence 계산

Rule 실행 오케스트레이터는 Registry를 생성자 또는 다른 명시적인 의존성
주입 방식으로 전달받는다. 오케스트레이터는 Registry에 등록된 모든 Rule을
자동 실행하지 않고 호출자가 전달한 ordered 실행 계획만 처리한다.

오케스트레이터의 유일한 기능 흐름은 다음과 같다.

```text
ordered RuleId plan
→ Registry evaluator capability 조회
→ 실행 전 전체 검증
→ 순차 evaluator 실행
→ ordered raw RuleEvaluationResult tuple 반환
```

## 4. 입력 계약

구현된 공개 계약은 다음과 같다.

```python
execute(
    rule_ids: Sequence[str],
    rule_input: RuleEvaluationInput,
) -> tuple[RuleEvaluatorResult, ...]
```

입력 계약은 다음과 같다.

- `rule_ids`는 호출자가 선택하고 정렬한 내부 evaluator capability ID다.
- `RuleId`는 `str` 기반 `StrEnum`이므로 `RuleId` 값도 문자열 계열 원소로
  허용한다.
- 컬렉션 자체로 전달된 `str` 또는 `bytes`는 유효한 Rule ID Sequence로
  간주하지 않고 거부한다.
- 각 원소는 `str` 계열이어야 한다. 문자열이 아닌 원소가 하나라도 있으면
  실행 계획 validation에 실패한다.
- 입력 Sequence는 실행 시작 시 한 번 tuple로 snapshot하며 이후 검증과
  실행은 이 snapshot만 사용한다.
- Rule ID를 trim하거나 uppercase로 변환하거나 alias로 치환하지 않는다.
- 이 계층은 이미 내부 `RuleId`로 해석된 실행 계획만 받는다.
- DB 활성 Rule 조회와 `ruleCode → RuleId` 연결은 선행 계층의 책임이다.
- `rule_input`은 현재 구현된 불변 `RuleEvaluationInput`이어야 한다.

호출자가 전달한 Sequence의 이후 변경은 tuple snapshot에 영향을 주지
않아야 한다. 오케스트레이터도 `rule_ids`와 `rule_input`을 변경하지 않는다.

## 5. 출력 계약과 불변식

성공 결과는 다음 타입의 불변 ordered tuple이다.

```python
tuple[RuleEvaluatorResult, ...]
```

성공 출력은 다음 불변식을 모두 만족해야 한다.

- 결과 개수는 요청한 Rule ID 개수와 같다.
- `results[i]`는 `rule_ids[i]`로 해결한 evaluator의 결과다.
- `results[i].rule_id`는 요청한 내부 evaluator capability ID와 일치한다.
- 결과 순서는 요청 순서와 같다.
- Registry의 `supported_rule_ids` 순서로 결과를 재정렬하지 않는다.
- 모든 evaluator가 정상 완료한 경우에만 완전한 tuple을 반환한다.
- 실패 시 빈 tuple이나 부분 tuple을 정상 결과로 반환하지 않는다.

현재 범위에서는 별도의 per-rule wrapper와 execution-level wrapper를
도입하지 않는다. `RuleEvaluationResult`가 `rule_id`, 적중 여부와 facts를
포함하고 ordered tuple이 실행 순서를 보존하므로 현재 성공 계약을 표현할 수
있다.

향후 `executionId`, RuleVersion snapshot, 실행 상태, 시작·종료 시각 또는
실패 상세를 반환해야 한다면 현재 raw 결과 tuple 위의 상위 execution wrapper를
재검토한다. 이때 기존 per-rule 결과 의미를 임의로 변경하지 않는다.

## 6. 실행 전 validation 단계와 오류 우선순위

오케스트레이터는 다음 순서로 validation한다.

1. `rule_ids` 컬렉션 형태를 검증한다. 컬렉션 자체인 `str`와 `bytes`는 이
   단계에서 거부한다.
2. 각 원소가 `str` 계열인지 입력 순서대로 검증한다.
3. 입력 Sequence를 한 번 tuple로 snapshot한다.
4. tuple이 비어 있는지 검증한다.
5. exact 값 기준으로 중복 ID를 검증한다.
6. 모든 ID를 입력 순서대로 Registry에서 조회해 evaluator tuple을 구성한다.
7. 모든 evaluator가 해결된 이후에만 첫 evaluator를 실행한다.

앞 단계의 실패는 뒤 단계보다 우선한다. 예를 들어 한 실행 계획에 중복 ID와
미지원 ID가 함께 있으면 중복 검증이 Registry resolution보다 먼저 실패한다.
Registry resolution 중에는 요청 순서상 처음 확인된 미지원 ID가 오류가 되며
나머지 오류를 집계하기 위해 계속 진행하지 않는다.

이 문서의 "실행 전 전체 검증"은 모든 validation 오류를 한 응답에 집계한다는
뜻이 아니다. 어떤 evaluator도 실행하기 전에 전체 실행 계획의 모든 ID가
evaluator capability로 해결되어야 한다는 뜻이다. 어느 단계에서든 validation이
실패하면 evaluator 호출 횟수는 0회다.

### 6.1 빈 실행 계획

빈 실행 계획은 정상적인 "Rule 적중 없음"이 아니다. 호출자 또는 선행 계층이
유효한 실행 계획을 구성하지 못한 설정 오류로 거부한다. 성공 결과인 빈 tuple로
변환하지 않는다.

현재 DB seed RuleVersion이 모두 DRAFT라는 사실도 이 계층에서 정상적인 빈
실행으로 해석하지 않는다. 활성 Rule이 없는 상황을 어떻게 처리할지는 선행
계층과 상위 거래 처리 계약이 결정해야 한다.

### 6.2 중복 Rule ID

중복 여부는 정규화하지 않은 exact 문자열 값으로 판단한다.

- `R001`, `R001`은 중복이다.
- `RuleId.R001`, `"R001"`은 같은 문자열 값이므로 중복이다.
- `"R001"`, `" R001"`은 중복이 아니다. 두 번째 값은 정규화하지 않으며
  Registry resolution에서 미지원 ID로 거부한다.

Registry 생성 시 중복 등록을 나타내는 `DuplicateRuleIdError`와 실행 요청의
중복은 책임과 의미가 다르다. 현재 구현은 실행 요청의 중복을
`InvalidRuleExecutionPlanError`로 구분한다.

### 6.3 미지원 Rule ID

소문자, 앞뒤 공백, 알 수 없는 ID와 alias는 자동 보정하지 않는다. 예를 들어
`r001`, ` R001`, `R001 `, `R005`는 미지원 ID다. 미지원 ID가 하나라도 있으면
첫 evaluator 실행 전에 실패한다.

## 7. 순차 실행과 결정적인 결과 순서

validation이 완료되면 오케스트레이터는 Rule ID tuple과 미리 해결한 evaluator
tuple을 같은 index 순서로 유지한다. 실행은 하나의 순차 loop로 수행하고 각
evaluator가 반환한 결과를 같은 순서로 수집한다.

다음 방식은 사용하지 않는다.

- Registry 기본 순서로 재정렬
- set 또는 순서가 없는 collection을 통한 실행 계획 재구성
- 병렬 evaluator 실행
- 비동기 `gather`
- 완료 시각 또는 completion order 기준 결과 수집

따라서 같은 Registry, 같은 ordered Rule ID tuple과 같은 `RuleEvaluationInput`에
대해 evaluator가 정상 완료하면 실행 순서와 결과 순서는 항상 동일하다.

## 8. evaluator 실패와 부분 결과 정책

evaluator 실행 중 예외가 발생하면 즉시 fail-fast한다.

- 실패한 evaluator 이후의 evaluator는 실행하지 않는다.
- 자동 재시도, fallback 또는 미적중 결과 변환을 수행하지 않는다.
- 기존 evaluator 예외를 정상 결과나 `matched=False`로 변환하지 않는다.
- 예외 이전에 계산한 결과가 있어도 정상적인 부분 결과로 반환하지 않는다.
- 빈 tuple을 실패 대체 성공값으로 반환하지 않는다.
- 모든 evaluator가 성공해야 완전한 결과 tuple을 반환한다.

내부 fail-fast·무재시도 정책은 상위 시스템의 거래 복구, 전체 FastAPI 요청
재호출 또는 수동 재처리 정책을 결정하지 않는다. 상위 계층은 내부 실패를
무위험·정상 거래 또는 빈 Evidence로 해석해서는 안 된다.

## 9. evaluator 반환 rule_id 방어 검증

오케스트레이터는 각 evaluator 반환 직후 다음 관계를 검증해야 한다.

```text
returned_result.rule_id == requested_rule_id
```

Registry가 특정 요청 ID에 callable을 연결했다는 사실만으로 반환 ID 일치를
신뢰하지 않는다. evaluator가 요청 ID와 다른 `rule_id`를 반환하면 evaluator
계약의 런타임 위반으로 처리한다.

이 계약 위반이 발생하면 다음 정책을 적용한다.

- 이후 evaluator 실행 중단
- 잘못된 결과를 요청 ID의 정상 결과로 치환하지 않음
- 이전에 계산한 결과를 포함한 부분 tuple 미반환
- 자동 수정, ID 덮어쓰기, retry와 fallback 금지

현재 Python 구현은 이 상황을 정상적인 미적중이나 미지원 ID와 구분되는
`RuleEvaluatorResultMismatchError`로 표현한다.

## 10. 점수·위험 등급·Evidence 계층과의 경계

오케스트레이터 출력은 점수와 외부 Evidence가 결합되지 않은 raw
`RuleEvaluationResult` tuple이다. 다음 기능은 후속 계층의 책임이다.

- RuleVersion과 weight 적용
- 그룹 상한과 점수 합산
- 위험 점수와 위험 등급 산정
- facts의 Evidence 및 `observationSummary` 변환
- `reasonCode`, `ruleCode`, RuleVersion 결합
- DetectionResult 완전성 검증
- DetectionResult 저장 및 채택
- FastAPI 외부 API 요청·응답 처리
- Spring Boot 연동과 거래 업무 상태 확정

오케스트레이터는 score, risk level, Evidence, reasonCode 또는 영속 DTO를
추가하지 않는다. downstream 계층도 raw 결과의 순서나 `rule_id`를 근거 없이
변경해서는 안 된다.

## 11. RuleVersion 실행 계획 계약과 연결 경계

현재 내부 계약과 공식 시스템 계약 사이에는 다음 연결 경계가 남아 있다.

- 현재 Registry의 `RuleId`는 내부 evaluator capability ID다.
- [공식 Rule v1 문서](./rule-v1-detection-contract.md)는 `ruleCode`를 evaluator
  선택자로 표현한다.
- 현재 `RuleEvaluationInput`과 `RuleEvaluationResult`에는 RuleVersion ID,
  버전 번호, 실행 조건과 weight가 없다.
- 따라서 현재 오케스트레이터만으로 "고정된 활성 RuleVersion 집합 실행" 전체를
  표현할 수 없다.
- [FraudRule·RuleVersion DB 계약](../04-database/fraud-rule-version-schema.md)의
  초기 RuleVersion seed는 모두 DRAFT다. 오케스트레이터는 이 상태를 정상적인
  빈 실행으로 해석하지 않는다.
- 내부 fail-fast·무재시도 정책은 상위 시스템의 거래 복구 및 재호출 정책을
  결정하지 않는다.

`ruleCode → RuleId` 연결, 활성 RuleVersion snapshot, dependency, 설정
호환성과 결정적 실행 순서는
[RuleVersion 기반 Rule 실행 계획 내부 계약](./rule-execution-plan-contract.md)에
정의되어 있다. 현재 Python에는 불변 `RuleExecutionPlan`,
`RuleExecutionPlanItem`, 순수 `RuleExecutionPlanBuilder`, plan 실행·결합을
담당하는 `RuleExecutionPlanRunner`와 `PlannedRuleResult`가 구현되어 있지만,
Java 구현과 서비스 연동은 아직 구현되지 않았다. 실행 계획의 weight는
snapshot 정보로만 보존하며 이 오케스트레이터는 weight를 적용하거나
scoring하지 않는다. 관련 후속 구현은
[ADR-005](../07-decisions/ADR-005-fraud-rule-version-model.md)의 RuleVersion
불변성과 Spring Boot·PostgreSQL 데이터 소유권을 유지해야 한다.

이번 문서에서는 공식 Rule v1 문서의 `ruleCode = evaluator 선택자` 표현과
DB·ADR 계약을 변경하지 않는다.

## 12. 제외 범위

다음 항목은 이 문서 계약과 현재 작업의 범위에 포함하지 않는다.

- RuleVersion 기반 실행 계획 생성 구현
- `evaluate_all()` 구현
- Registry 기능 확장 또는 수정
- 기존 evaluator와 models 수정
- Python 테스트 코드 추가·수정
- DB 기반 활성 Rule 조회와 적용 기간 판단
- `ruleCode → RuleId` 변환 또는 alias mapping
- Feature 계산 계층 추가
- RuleVersion 조건과 weight 적용
- 점수 합산과 위험 등급 산정
- Evidence, `observationSummary`와 reasonCode 변환
- DetectionResult 검증·저장·채택 구현
- FastAPI 외부 API와 Spring Boot 연동
- retry, fallback과 병렬 실행
- 로그·메트릭 저장
- Redis, Kafka, ML과 LLM 연동
- 신규 외부 의존성
- 공식 Rule v1, DB와 ADR 계약 변경

## 13. Python 구현의 테스트 조건

현재 구현은 최소한 다음 조건을 자동화된 테스트로 검증한다.

- 호출자가 전달한 실행 순서를 유지한다.
- 결과 tuple이 요청과 동일한 순서를 가진다.
- 가변 Sequence를 전달한 뒤 변경해도 실행 시작 시 만든 snapshot은 변하지
  않는다.
- 컬렉션 자체로 전달된 `str`와 `bytes`를 거부한다.
- 빈 실행 계획을 거부하고 evaluator를 호출하지 않는다.
- exact 값 기준 중복 ID를 실행 전에 거부한다.
- `RuleId.R001`과 `"R001"` 조합을 중복으로 거부한다.
- 소문자, 앞뒤 공백 포함 ID와 미지원 ID를 정규화 없이 거부한다.
- 미지원 ID가 하나라도 있으면 evaluator 호출 횟수가 0회다.
- 모든 evaluator resolution이 완료된 이후에만 첫 evaluator를 실행한다.
- evaluator 예외 발생 후 다음 evaluator를 실행하지 않는다.
- evaluator 실패 시 앞서 계산한 부분 결과를 정상 반환하지 않는다.
- evaluator 반환 `rule_id`가 요청 ID와 다르면 계약 위반으로 거부한다.
- 반환 ID 계약 위반 후 다음 evaluator를 실행하지 않고 부분 결과도 반환하지
  않는다.
- 정상 실행 시 요청 수와 결과 수가 1:1로 대응한다.
- Registry 기본 순서와 다른 호출자 지정 순서에서도 실행과 결과 순서를
  그대로 유지한다.
- 자동 retry, fallback, 병렬 실행과 completion order 수집이 발생하지 않는다.

## 14. 이번 작업의 생성·수정 파일

이번 Issue의 승인된 문서 변경 범위는 다음 두 파일이다.

- 생성: `docs/01-requirements/rule-execution-orchestration-contract.md`
- 수정: `ai-service/README.md`

Python 구현, 테스트, 의존성, lock 파일, 공식 Rule v1 계약, DB 문서와 ADR은
변경하지 않는다.
