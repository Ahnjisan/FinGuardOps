from finguardops_ai.rules.v1._shared import (
    WINDOW_SECONDS,
    elapsed_seconds,
    is_in_behavior_window,
    is_supported_transfer,
    select_latest,
)
from finguardops_ai.rules.v1.models import (
    BehaviorEventType,
    R004Facts,
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleId,
)


def evaluate_r004(rule_input: RuleEvaluationInput) -> RuleEvaluationResult[R004Facts]:
    """Evaluate a recent matching beneficiary registration."""
    transaction = rule_input.transaction
    if not is_supported_transfer(transaction):
        return RuleEvaluationResult(rule_id=RuleId.R004, matched=False, facts=None)

    selected_event = select_latest(
        event
        for event in rule_input.behavior_events
        if event.event_type is BehaviorEventType.BENEFICIARY_REGISTERED
        and event.external_customer_ref == transaction.external_customer_ref
        and event.account_ref == transaction.sender_account_ref
        and event.beneficiary_ref == transaction.recipient_account_ref
        and is_in_behavior_window(event.occurred_at, transaction.occurred_at)
    )
    if selected_event is None:
        return RuleEvaluationResult(rule_id=RuleId.R004, matched=False, facts=None)

    return RuleEvaluationResult(
        rule_id=RuleId.R004,
        matched=True,
        facts=R004Facts(
            observed_amount=transaction.amount,
            event_id=selected_event.event_id,
            beneficiary_registered_at=selected_event.occurred_at,
            elapsed_seconds=elapsed_seconds(selected_event.occurred_at, transaction.occurred_at),
            window_seconds=WINDOW_SECONDS,
        ),
    )
