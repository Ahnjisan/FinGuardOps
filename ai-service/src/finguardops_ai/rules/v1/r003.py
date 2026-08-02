from finguardops_ai.rules.v1._shared import (
    AMOUNT_THRESHOLD,
    WINDOW_SECONDS,
    elapsed_seconds,
    is_high_amount_transfer,
    is_in_behavior_window,
    order_latest,
    select_latest,
)
from finguardops_ai.rules.v1.models import (
    BehaviorEventType,
    R003Facts,
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleId,
)


def evaluate_r003(rule_input: RuleEvaluationInput) -> RuleEvaluationResult[R003Facts]:
    """Evaluate the recent password and transfer-limit change sequence."""
    transaction = rule_input.transaction
    if not is_high_amount_transfer(transaction):
        return RuleEvaluationResult(rule_id=RuleId.R003, matched=False, facts=None)

    eligible_limit_events = order_latest(
        event
        for event in rule_input.behavior_events
        if event.event_type is BehaviorEventType.TRANSFER_LIMIT_CHANGED
        and event.external_customer_ref == transaction.external_customer_ref
        and event.account_ref == transaction.sender_account_ref
        and is_in_behavior_window(event.occurred_at, transaction.occurred_at)
    )

    for limit_event in eligible_limit_events:
        password_event = select_latest(
            event
            for event in rule_input.behavior_events
            if event.event_type is BehaviorEventType.PASSWORD_CHANGED
            and event.external_customer_ref == transaction.external_customer_ref
            and is_in_behavior_window(event.occurred_at, transaction.occurred_at)
            and event.occurred_at <= limit_event.occurred_at
        )
        if password_event is None:
            continue

        return RuleEvaluationResult(
            rule_id=RuleId.R003,
            matched=True,
            facts=R003Facts(
                observed_amount=transaction.amount,
                amount_threshold=AMOUNT_THRESHOLD,
                password_changed_event_id=password_event.event_id,
                password_changed_at=password_event.occurred_at,
                transfer_limit_changed_event_id=limit_event.event_id,
                transfer_limit_changed_at=limit_event.occurred_at,
                elapsed_seconds=elapsed_seconds(limit_event.occurred_at, transaction.occurred_at),
                window_seconds=WINDOW_SECONDS,
            ),
        )

    return RuleEvaluationResult(rule_id=RuleId.R003, matched=False, facts=None)
