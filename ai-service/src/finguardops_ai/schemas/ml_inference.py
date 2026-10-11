"""Strict internal fraud ML inference wire contract."""

from datetime import datetime
from decimal import Decimal
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from finguardops_ai.schemas.scenario_rule import ScenarioAggregationFactsDto, ScenarioFactsDto


class MlDto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=False)


class MlEvent(MlDto):
    eventId: UUID
    eventType: Literal[
        "DEVICE_REGISTERED",
        "PASSWORD_CHANGED",
        "TRANSFER_LIMIT_CHANGED",
        "BENEFICIARY_REGISTERED",
    ]
    occurredAt: datetime
    createdAt: datetime

    @field_validator("eventId")
    @classmethod
    def require_event_uuid_v4(cls, value: UUID) -> UUID:
        if value.version != 4:
            raise ValueError("eventId must be UUID v4")
        return value

    @property
    def event_id(self) -> UUID:
        return self.eventId

    @property
    def event_type(self) -> str:
        return self.eventType

    @property
    def occurred_at(self) -> datetime:
        return self.occurredAt

    @property
    def created_at(self) -> datetime:
        return self.createdAt


class MlInferenceRequest(MlDto):
    transactionId: UUID
    evaluationCutoffAt: datetime
    amount: Decimal = Field(ge=0, le=Decimal("999999999999999"))
    transactionType: Literal[
        "ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER", "ATM_WITHDRAWAL", "LOAN_DISBURSED"
    ]
    channel: Literal["MOBILE_BANKING", "OPEN_BANKING", "ATM", "CORE_BANKING"]
    featureVersion: Literal["fraud-feature-v1", "fraud-feature-v2", "fraud-feature-v3"]
    scoringPolicyVersion: Literal[
        "rule-ml-policy-v1", "rule-ml-policy-v2", "rule-ml-policy-v3", "rule-ml-policy-v4"
    ]
    modelVersion: str = Field(min_length=1, max_length=64)
    modelSha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    events: tuple[MlEvent, ...] = Field(max_length=1000)
    scenarioFacts: ScenarioFactsDto | None = None
    aggregationFacts: ScenarioAggregationFactsDto | None = None

    @field_validator("transactionId")
    @classmethod
    def require_transaction_uuid_v4(cls, value: UUID) -> UUID:
        if value.version != 4:
            raise ValueError("transactionId must be UUID v4")
        return value

    @field_validator("amount", mode="before")
    @classmethod
    def require_canonical_amount(cls, value: object) -> object:
        if not isinstance(value, str) or not value or value.strip() != value:
            raise ValueError("amount must be a decimal string")
        return value

    @model_validator(mode="after")
    def validate_times(self) -> "MlInferenceRequest":
        if self.evaluationCutoffAt.tzinfo is None or any(
            event.occurredAt.tzinfo is None or event.createdAt.tzinfo is None
            for event in self.events
        ):
            raise ValueError("timestamps must have timezone")
        if self.scoringPolicyVersion == "rule-ml-policy-v4":
            if (
                self.featureVersion != "fraud-feature-v3"
                or self.modelVersion != "fraud-logistic-v4"
                or self.scenarioFacts is None
                or self.aggregationFacts is None
                or self.events
            ):
                raise ValueError("Rule+ML v4 requires pinned aggregation facts")
        elif self.scoringPolicyVersion == "rule-ml-policy-v3":
            if (
                self.featureVersion != "fraud-feature-v2"
                or self.modelVersion != "fraud-logistic-v3"
                or self.scenarioFacts is None
                or self.events
                or self.aggregationFacts is not None
            ):
                raise ValueError("Rule+ML v3 requires pinned v2 facts and no legacy events")
        elif (
            self.featureVersion != "fraud-feature-v1"
            or self.scenarioFacts is not None
            or self.aggregationFacts is not None
        ):
            raise ValueError("Legacy Rule+ML requires legacy features")
        return self


class MlInferenceResponse(MlDto):
    transactionId: UUID
    evaluationCutoffAt: datetime
    featureVersion: str
    scoringPolicyVersion: str
    modelVersion: str
    modelSha256: str
    probabilityBasisPoints: int = Field(ge=0, le=10000)
    reasonCode: Literal["ML_RISK_SIGNAL", "ML_BELOW_THRESHOLD"]
