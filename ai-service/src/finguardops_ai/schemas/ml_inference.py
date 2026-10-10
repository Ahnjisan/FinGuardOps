"""Strict internal fraud ML inference wire contract."""

from datetime import datetime
from decimal import Decimal
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


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
    featureVersion: Literal["fraud-feature-v1"]
    scoringPolicyVersion: Literal["rule-ml-policy-v1"]
    modelVersion: str = Field(min_length=1, max_length=64)
    modelSha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    events: tuple[MlEvent, ...] = Field(max_length=1000)

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
