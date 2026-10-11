"""Internal v3 wire for approved SCN-001/002 facts and attributed Rule scores."""

from decimal import Decimal
from typing import Annotated

from pydantic import Field

from finguardops_ai.schemas.rule_analysis import (
    CanonicalUuid4,
    ReferenceValue,
    RuleAnalysisDto,
    RuleAnalysisRequestV2,
    RuleSetVersion,
    RuleTransactionSnapshotRequest,
    StrictText,
    TraceId,
    UtcZDateTime,
)


class ScenarioFactsDto(RuleAnalysisDto):
    source_version: StrictText
    baseline_status: StrictText
    baseline_count: int
    baseline_days: int
    baseline_total: Annotated[Decimal, Field(strict=False)]
    baseline_mean: Annotated[Decimal, Field(strict=False)] | None
    recipient_status: StrictText
    beneficiary_event_id: CanonicalUuid4 | None
    beneficiary_registered_at: UtcZDateTime | None
    device_status: StrictText
    device_event_id: CanonicalUuid4 | None
    device_first_seen_at: UtcZDateTime | None
    limit_status: StrictText
    limit_scope: StrictText | None
    previous_limit: Annotated[Decimal, Field(strict=False)] | None
    new_limit: Annotated[Decimal, Field(strict=False)] | None
    prior_limit_exceeded: StrictText
    password_event_id: CanonicalUuid4 | None
    password_changed_at: UtcZDateTime | None
    limit_event_id: CanonicalUuid4 | None
    limit_changed_at: UtcZDateTime | None
    identity_status: StrictText
    app_security_status: StrictText
    app_security_at: UtcZDateTime | None
    network_region_status: StrictText
    network_region_at: UtcZDateTime | None
    exact_provider_device_match: bool
    transfer_coverage_fact_id: CanonicalUuid4 | None
    device_coverage_fact_id: CanonicalUuid4 | None
    device_trust_fact_id: CanonicalUuid4 | None
    limit_fact_id: CanonicalUuid4 | None
    identity_fact_id: CanonicalUuid4 | None
    app_security_fact_id: CanonicalUuid4 | None
    network_region_fact_id: CanonicalUuid4 | None


class ScenarioTransactionSnapshotRequest(RuleTransactionSnapshotRequest):
    recipient_account_ref: ReferenceValue | None


class RuleAnalysisRequestV3(RuleAnalysisRequestV2):
    transaction: ScenarioTransactionSnapshotRequest
    scenario_facts: ScenarioFactsDto


class ScenarioContribution(RuleAnalysisDto):
    rule_id: StrictText
    execution_order: int
    matched: bool
    original_contribution: int
    applied_contribution: int


class ScenarioGroupSummary(RuleAnalysisDto):
    group_id: StrictText
    raw_score: int
    cap: int
    applied_score: int
    reduction: int


class ScenarioScoring(RuleAnalysisDto):
    scoring_policy_version: StrictText
    risk_score: int
    risk_level: StrictText
    rule_contributions: tuple[ScenarioContribution, ...]
    group_summaries: tuple[ScenarioGroupSummary, ...]


class ScenarioEvidence(RuleAnalysisDto):
    rule_id: StrictText
    rule_version_id: CanonicalUuid4
    rule_code: StrictText
    rule_version: StrictText
    reason_code: StrictText
    execution_order: int
    score_contribution: int
    applied_contribution: int
    observation_summary: dict[str, object]
    evidence_occurred_at: UtcZDateTime


class ScenarioAnalysis(RuleAnalysisDto):
    evaluation_cutoff_at: UtcZDateTime
    rule_set_version: RuleSetVersion
    scoring_result: ScenarioScoring
    evidence: tuple[ScenarioEvidence, ...]


class RuleAnalysisResponseV3(RuleAnalysisDto):
    transaction_id: CanonicalUuid4
    trace_id: TraceId
    analysis: ScenarioAnalysis


class ScenarioWindowDto(RuleAnalysisDto):
    transfer_count: int
    distinct_count: int
    total_amount: Annotated[Decimal, Field(strict=False)]
    unknown_count: int
    coverage_fact_id: CanonicalUuid4 | None


class ScenarioAggregationFactsDto(RuleAnalysisDto):
    source_version: StrictText
    outbound: ScenarioWindowDto
    inbound: ScenarioWindowDto


class RuleAnalysisRequestV4(RuleAnalysisRequestV3):
    aggregation_facts: ScenarioAggregationFactsDto


class ScenarioFundFlowDto(RuleAnalysisDto):
    status: StrictText
    reason_code: StrictText
    source_transaction_id: CanonicalUuid4 | None
    current_link_id: CanonicalUuid4 | None
    history_coverage_fact_id: CanonicalUuid4 | None
    beneficiary_event_id: CanonicalUuid4 | None
    source_occurred_at: UtcZDateTime | None
    source_approved_at: UtcZDateTime | None
    source_amount: Annotated[Decimal, Field(strict=False)]
    linked_amount: Annotated[Decimal, Field(strict=False)]
    ratio_basis_points: int
    linked_prior_transaction_ids: list[CanonicalUuid4]


class ScenarioFundFlowFactsDto(RuleAnalysisDto):
    source_version: StrictText
    atm: ScenarioFundFlowDto
    loan: ScenarioFundFlowDto


class RuleAnalysisRequestV5(RuleAnalysisRequestV4):
    fund_flow_facts: ScenarioFundFlowFactsDto


class ScenarioOpenBankingStageDto(RuleAnalysisDto):
    source_transaction_id: CanonicalUuid4
    ownership_assertion_id: CanonicalUuid4
    link_id: CanonicalUuid4
    occurred_at: UtcZDateTime
    approved_at: UtcZDateTime
    amount: Annotated[Decimal, Field(strict=False)]


class ScenarioOpenBankingFactsDto(RuleAnalysisDto):
    source_version: StrictText
    status: StrictText
    reason_code: StrictText
    history_coverage_fact_id: CanonicalUuid4 | None
    concentration_started_at: UtcZDateTime | None
    concentration_completed_at: UtcZDateTime | None
    concentration_amount: Annotated[Decimal, Field(strict=False)]
    resend_amount: Annotated[Decimal, Field(strict=False)]
    ratio_basis_points: int
    stages: list[ScenarioOpenBankingStageDto]

    def hit(self) -> bool:
        return (
            self.status == "AVAILABLE"
            and len(self.stages) >= 2
            and self.concentration_amount >= Decimal(5000000)
            and self.ratio_basis_points >= 8000
        )


class RuleAnalysisRequestV6(RuleAnalysisRequestV5):
    open_banking_facts: ScenarioOpenBankingFactsDto
