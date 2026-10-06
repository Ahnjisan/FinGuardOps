# AI 조사 리포트 저장 구조 — Issue #339

Flyway V15는 `ai_report_request`, `ai_report_execution`, `ai_report`,
`provider_call_attempt`를 추가한다. 모든 테이블은 기존 사건 또는 실행에
`ON DELETE RESTRICT`로 연결된다. 사건·거래·탐지 결과 자체는 갱신하지 않는다.

`ai_report_request`는 외부 `aiRequestId`, 사건별 `Idempotency-Key`, fingerprint,
요청자 USER 참조, 요청 당시 탐지·Prompt·모델 버전과 상태를 저장한다.
`UNIQUE(fraud_case_id,idempotency_key)`는 같은 키 재전송의 단일 요청을 보장한다.

`ai_report_execution`은 채택 탐지 결과 FK와 정확 일치 네 요소를 저장한다.
`PENDING`·`GENERATING`에만 적용되는 부분 유일 인덱스가 같은 정확 일치
실행의 중복을 막는다. PostgreSQL transaction advisory lock과 `GENERATING`
행은 전체 로컬 추론 동시 수를 1로 제한한다. lease가 만료되면 중복 Provider
호출 없이 `FAILED/WORKER_INTERRUPTED`로 종결한다. 새 멱등 키로 다시 요청할
수 있다. Ollama에는 멱등 접수 API가 없어 프로세스 강제 종료 직전의 외부
호출 횟수를 확정할 수 없으며, 이 설계는 자동 중복 호출을 피한다.

`ai_report`는 성공한 실행에 최대 한 건이며, 정확 일치 키에 유일하다.
본문은 허용된 요약·근거·확인 항목만 저장하고 원시 Prompt와 Provider 응답은
저장하지 않는다. `provider_call_attempt`는 실제 시도별 digest·양자화·확인된
토큰·지연시간·분류 결과를 기록한다. 로컬 전력·장비 원가가 미측정이면
`estimated_cost`·`cost_currency`는 NULL이다.

Issue #347의 V18은 새 실행과 최초 요청을 저장한 동일 transaction에
`AiReportExecutionCreated` outbox를 하나 추가한다. 공유·캐시·동일 키 재생에는
추가하지 않는다. 상세 제약은 [AI 리포트 Outbox](ai-report-outbox-schema.md)를 따른다.

V17은 `ai_report_execution.fallback_trigger_code VARCHAR(64) NULL`을 추가한다.
기존 행은 NULL 그대로 두며 `failure_code`나 attempt outcome에서 원인을 추정해
backfill하지 않는다. 신규 정상 LLM 완료는 두 코드가 모두 NULL, 템플릿 완료는
최종 `failure_code=NULL`과 안전한 `fallback_trigger_code`, 템플릿도 실패하면
`failure_code=TEMPLATE_FALLBACK_FAILED`와 원인 코드를 저장한다. 기존 fallback
행의 `failure_code` 의미는 당시 기록 그대로 유지한다. 저장 트랜잭션이 실패하면
새 코드의 영속화도 보장되지 않는다.

현재 리포트는 성공한 **실행 최초 요청**의 `requested_at DESC`,
`ai_request_id DESC`로 정한다. 새 요청이 진행 중이거나 실패해도 과거 성공
결과를 숨기지 않으며, 공개 응답은 각기 다른 탐지 버전을 명시한다.
