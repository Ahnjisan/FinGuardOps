# AI 리포트 실행 Outbox (V18)

`V18__create_ai_report_outbox.sql`은 새 `AiReportExecution`에 대해 `AiReportExecutionCreated` 한 건을 저장한다. `AiReportService.create`의 신규 실행·최초 요청 insert와 같은 PostgreSQL transaction에서 기록한다. 공유 실행, 완료 리포트 캐시, 같은 요청의 멱등 재생에는 새 행이 없다.

| 컬럼 | 계약 |
| --- | --- |
| `event_id` | UUID v4, 재발행에도 유지하는 전달 식별자, unique |
| `execution_id` | `ai_report_execution.execution_id` FK, 이 이벤트 종류에서 unique인 업무 멱등 키 |
| `event_type`, `event_version`, `payload` | `AiReportExecutionCreated`, v1, 승인된 최소 JSON |
| `status` | `PENDING → CLAIMED → PUBLISHED`; 10회 실패 뒤 `BLOCKED` |
| `attempt_count`, `next_attempt_at` | 발행 횟수와 재시도 예정 시각 |
| `claim_token`, `lease_until` | `FOR UPDATE SKIP LOCKED` 선점 후 30초 소유권. 조건부 완료로 오래된 발행자가 새 선점을 덮어쓰지 못함 |
| `published_at`, `last_failure_code` | broker ack 후 기록한 시각과 안전한 실패 분류 |

Dispatcher는 broker ack와 DB 표시를 한 원자적 transaction으로 묶지 못한다. ack 뒤 중단하면 같은 `event_id`가 다시 전송될 수 있다. Consumer는 `execution_id`가 가리키는 현재 실행 상태를 확인한다. `PUBLISHED` 자동 삭제는 첫 PR에 없다. 30일 보존은 후속 정책 검토값이며 `BLOCKED`는 자동 삭제하지 않는다. Payload에 Prompt·Provider 응답·고객 식별 원문을 저장하지 않는다.
# V19 BLOCKED 단건 재대기 이력

`ai_report_outbox_requeue_log`는 outbox `event_id`당 성공 조치 한 행만 저장한다.
`execution_id`, UUID v4 USER `actor_id`, `BLOCKED → PENDING`, `trace_id`, `changed_at`을
기록하며 UPDATE/DELETE trigger가 변경을 거부한다. outbox 상태 전이와 이력 INSERT는
하나의 PostgreSQL 거래다. 실패하거나 경합에 진 조치는 이 성공 이력에 기록하지 않는다.
기존 거래·사건 `audit_log`의 action/target 계약은 바꾸지 않는다.
