from finguardops_ai.rules.v1._shared import (
    AMOUNT_THRESHOLD,
    WINDOW_SECONDS,
    elapsed_seconds,
    is_high_amount_transfer,
    is_in_behavior_window,
    select_latest,
)
from finguardops_ai.rules.v1.models import (
    BehaviorEventType,
    R002Facts,
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleId,
)


def evaluate_r002(rule_input: RuleEvaluationInput) -> RuleEvaluationResult[R002Facts]:
    """Evaluate recent matching device registration on a high-amount transfer."""
    transaction = rule_input.transaction
    if not is_high_amount_transfer(transaction) or transaction.device_ref is None:
        return RuleEvaluationResult(rule_id=RuleId.R002, matched=False, facts=None)

    selected_event = select_latest(
        event
        for event in rule_input.behavior_events
        if event.event_type is BehaviorEventType.DEVICE_REGISTERED
        and event.external_customer_ref == transaction.external_customer_ref
        and event.device_ref == transaction.device_ref
        and is_in_behavior_window(event.occurred_at, transaction.occurred_at)
    )
    if selected_event is None:
        return RuleEvaluationResult(rule_id=RuleId.R002, matched=False, facts=None)

    return RuleEvaluationResult(
        rule_id=RuleId.R002,
        matched=True,
        facts=R002Facts(
            observed_amount=transaction.amount,
            amount_threshold=AMOUNT_THRESHOLD,
            event_id=selected_event.event_id,
            device_registered_at=selected_event.occurred_at,
            elapsed_seconds=elapsed_seconds(selected_event.occurred_at, transaction.occurred_at),
            window_seconds=WINDOW_SECONDS,
        ),
    )
