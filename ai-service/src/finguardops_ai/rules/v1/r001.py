from finguardops_ai.rules.v1._shared import AMOUNT_THRESHOLD, is_high_amount_transfer
from finguardops_ai.rules.v1.models import (
    R001Facts,
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleId,
)


def evaluate_r001(rule_input: RuleEvaluationInput) -> RuleEvaluationResult[R001Facts]:
    """Evaluate the Rule v1 absolute high-amount transfer contract."""
    transaction = rule_input.transaction
    if not is_high_amount_transfer(transaction):
        return RuleEvaluationResult(rule_id=RuleId.R001, matched=False, facts=None)

    return RuleEvaluationResult(
        rule_id=RuleId.R001,
        matched=True,
        facts=R001Facts(
            observed_amount=transaction.amount,
            amount_threshold=AMOUNT_THRESHOLD,
        ),
    )
