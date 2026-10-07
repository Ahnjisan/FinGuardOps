# AI 리포트 DLQ 복구 스키마 (V20)

`ai_report_dlq_action`은 현재 DLQ topic ID·partition·offset당 최초 운영 결정
`QUARANTINE` 또는 `REPLAY` 한 행을 저장한다. DB trigger는 UPDATE/DELETE를 거부한다.
`actor_id`, `trace_id`, 실패 분류, 유효한 경우의 event/execution ID와 시각을 남긴다.
`REPLAY`의 event ID는 partial unique index로 전체 DLQ 중 한 번만 승인한다.
구형 또는 poison 메시지는 event/execution ID가 null일 수 있다. payload와 header는
저장하지 않는다.

`ai_report_dlq_replay_dispatch`는 승인된 `REPLAY` 조치에만 붙는다. `event_id`는
V18 outbox FK이며 unique다. `PENDING → CLAIMED → ACKED` 또는 `BLOCKED`·`SKIPPED`
상태, claim token·lease, 최대 10회 시도, broker ack 좌표를 저장한다. 조치 INSERT와
발행 의도 INSERT는 같은 PostgreSQL 거래에서 commit한다. broker ack와 DB ACKED
표시는 원자적이지 않으므로 ack 뒤 중단 시 같은 event가 다시 발행될 수 있다.
`BLOCKED`는 발행 미확정이며 자동 성공으로 표시하지 않는다. Kafka DLQ 원문은 DB에
복제하지 않고 dispatcher가 V18 canonical payload를 다시 읽는다.

`ai_report_execution_start_source`는 Worker의 실제 `PENDING → GENERATING` claim과
같은 거래에 `KAFKA` 또는 `POLLING`을 한 번 기록한다. 출처는 발행 성공이나
Provider 호출 횟수의 증거가 아니다. 완료/실패 결과와 report·attempt는 V15에서
별도로 검증한다. V19 `ai_report_outbox_requeue_log`는 변경하지 않는다.
