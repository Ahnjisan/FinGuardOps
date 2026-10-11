"""Approved local synthetic Rule v4 contract and time/order counterexamples."""

from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import uuid4

from fastapi.testclient import TestClient

from finguardops_ai.main import app
from finguardops_ai.ml.features_v2 import extract_features
from finguardops_ai.ml.model import load_model
from finguardops_ai.schemas.scenario_rule import ScenarioFactsDto

_CODES = (
    "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
    "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
    "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
    "RECENT_BENEFICIARY_TRANSFER",
    "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT",
    "CUSTOMER_BASELINE_HIGH_AMOUNT",
    "EXTERNAL_RISK_DEVICE_MATCH",
    "MOCK_APP_SECURITY_ALERT",
    "MATERIAL_LIMIT_UPLIFT",
    "MOCK_OVERSEAS_NETWORK",
)
_VERSIONS = (3, 4, 4, 4, 1, 1, 1, 1, 1, 1)
_WEIGHTS = (15, 20, 50, 10, 40, 20, 20, 15, 10, 10)
_SIGNALS = (
    None,
    "NEW_UNVERIFIED_DEVICE",
    "PASSWORD_LIMIT_SEQUENCE",
    "FIRST_REGISTERED_RECIPIENT",
    None,
    "TENFOLD_BASELINE",
    "EXACT_RISK_DEVICE",
    "APP_SECURITY_ALERT",
    "MATERIAL_LIMIT_UPLIFT",
    "OVERSEAS_NETWORK",
)
T = datetime(2026, 10, 12, 12, 0, tzinfo=UTC)


def wire(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def payload() -> dict:
    rules = []
    for index, code in enumerate(_CODES):
        condition = (
            dict(contractVersion="SCN001-002-contract-v1", signal=_SIGNALS[index])
            if _SIGNALS[index]
            else dict(
                subjectType="RECIPIENT_ACCOUNT",
                riskType="SUSPICIOUS_ACCOUNT",
                reasonCode="SUSPICIOUS_RECIPIENT_ACCOUNT",
                freshnessSeconds=86400,
            )
            if index == 4
            else dict(
                transactionTypes=["ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"],
                currencyCode="KRW",
                amountThreshold="10000000",
            )
        )
        rules.append(
            dict(
                fraudRuleId=str(uuid4()),
                ruleCode=code,
                lifecycleStatus="ACTIVE",
                ruleVersionId=str(uuid4()),
                versionNumber=_VERSIONS[index],
                status="PUBLISHED",
                reasonCode=code,
                weight=_WEIGHTS[index],
                conditionDefinition=condition,
                effectiveFrom=wire(T - timedelta(days=1)),
                effectiveTo=None,
            )
        )
    facts = dict(
        sourceVersion="SCN001-002-contract-v1",
        baselineStatus="AVAILABLE",
        baselineCount=10,
        baselineDays=3,
        baselineTotal=1000000,
        baselineMean=100000,
        recipientStatus="KNOWN",
        beneficiaryEventId=None,
        beneficiaryRegisteredAt=None,
        deviceStatus="NEW_UNVERIFIED",
        deviceEventId=str(uuid4()),
        deviceFirstSeenAt=wire(T - timedelta(minutes=5)),
        limitStatus="UNKNOWN",
        limitScope=None,
        previousLimit=None,
        newLimit=None,
        priorLimitExceeded="UNKNOWN",
        passwordEventId=None,
        passwordChangedAt=None,
        limitEventId=None,
        limitChangedAt=None,
        identityStatus="UNKNOWN",
        appSecurityStatus="UNKNOWN",
        appSecurityAt=None,
        networkRegionStatus="UNKNOWN",
        networkRegionAt=None,
        exactProviderDeviceMatch=False,
        transferCoverageFactId=str(uuid4()),
        deviceCoverageFactId=str(uuid4()),
        deviceTrustFactId=str(uuid4()),
        limitFactId=None,
        identityFactId=None,
        appSecurityFactId=None,
        networkRegionFactId=None,
    )
    return dict(
        evaluationCutoffAt=wire(T),
        transaction=dict(
            transactionId=str(uuid4()),
            transactionType="ACCOUNT_TRANSFER",
            amount="1000000",
            currencyCode="KRW",
            occurredAt=wire(T),
            externalCustomerRef="customer",
            senderAccountRef="sender",
            recipientAccountRef="recipient",
            deviceRef="device",
        ),
        behaviorEvents=[],
        ruleVersions=rules,
        externalRisk=dict(
            providerCode="PROVIDER_V1",
            lookupStatus="SUCCEEDED",
            policyResult="UNMATCHED",
            providerAsOf=wire(T),
            lookedUpAt=wire(T),
            matches=[],
        ),
        scenarioFacts=facts,
    )


def test_new_device_and_tenfold_baseline_are_medium() -> None:
    response = TestClient(app).post(
        "/api/v3/rule-analysis", json=payload(), headers={"X-Trace-Id": "scn384test01"}
    )
    assert response.status_code == 200, response.text
    score = response.json()["analysis"]["scoringResult"]
    assert (score["scoringPolicyVersion"], score["riskScore"], score["riskLevel"]) == (
        "scoring-policy-v4",
        40,
        "MEDIUM",
    )
    assert score["ruleContributions"][1]["appliedContribution"] == 20
    evidence = response.json()["analysis"]["evidence"]
    assert (
        next(row for row in evidence if row["ruleId"] == "R006")["observationSummary"][
            "baselineTotal"
        ]
        == "1000000"
    )


def test_same_time_security_change_cannot_score() -> None:
    body = payload()
    facts = body["scenarioFacts"]
    facts.update(
        limitStatus="RAISED",
        limitScope="PER_TRANSFER",
        previousLimit=2000000,
        newLimit=7000000,
        priorLimitExceeded="FALSE",
        limitFactId=str(uuid4()),
        passwordEventId=str(uuid4()),
        passwordChangedAt=wire(T - timedelta(minutes=10)),
        limitEventId=str(uuid4()),
        limitChangedAt=wire(T - timedelta(minutes=10)),
    )
    response = TestClient(app).post(
        "/api/v3/rule-analysis", json=body, headers={"X-Trace-Id": "scn384test02"}
    )
    assert response.status_code == 200, response.text
    assert response.json()["analysis"]["scoringResult"]["ruleContributions"][2]["matched"] is False


def test_missing_rule_version_rejected() -> None:
    body = payload()
    body["ruleVersions"].pop()
    response = TestClient(app).post(
        "/api/v3/rule-analysis", json=body, headers={"X-Trace-Id": "scn384test03"}
    )
    assert response.status_code == 422
    assert response.json()["code"] == "RULE_CONTRACT_ERROR"


def test_v4_rejects_non_krw_internal_wire() -> None:
    body = payload()
    body["transaction"]["currencyCode"] = "USD"
    response = TestClient(app).post(
        "/api/v3/rule-analysis", json=body, headers={"X-Trace-Id": "scn384currency"}
    )
    assert response.status_code == 422
    assert response.json()["code"] == "RULE_CONTRACT_ERROR"


def test_v4_rejects_mismatched_r001_definition_and_ml_model() -> None:
    body = payload()
    body["ruleVersions"][0]["conditionDefinition"]["amountThreshold"] = "9000000"
    assert (
        TestClient(app)
        .post("/api/v3/rule-analysis", json=body, headers={"X-Trace-Id": "scn384test04"})
        .status_code
        == 422
    )
    previous = load_model("fraud-logistic-v2")
    facts = payload()["scenarioFacts"]
    ml = dict(
        transactionId=str(uuid4()),
        evaluationCutoffAt=wire(T),
        amount="1000000",
        transactionType="ACCOUNT_TRANSFER",
        channel="MOBILE_BANKING",
        featureVersion="fraud-feature-v2",
        scoringPolicyVersion="rule-ml-policy-v3",
        modelVersion=previous.version,
        modelSha256=previous.sha256,
        events=[],
        scenarioFacts=facts,
    )
    assert TestClient(app).post("/api/v1/ml-inference", json=ml).status_code == 400


def test_v4_accepts_atm_and_loan_without_transfer_rule_contributions() -> None:
    for transaction_type in ("ATM_WITHDRAWAL", "LOAN_DISBURSED"):
        body = payload()
        body["transaction"]["transactionType"] = transaction_type
        body["transaction"]["amount"] = "10000000"
        body["transaction"]["recipientAccountRef"] = None
        response = TestClient(app).post(
            "/api/v3/rule-analysis", json=body, headers={"X-Trace-Id": "scn384nontransfer"}
        )
        assert response.status_code == 200, response.text
        scoring = response.json()["analysis"]["scoringResult"]
        assert scoring["riskScore"] == 0
        assert all(
            not row["matched"] and row["appliedContribution"] == 0
            for row in scoring["ruleContributions"]
        )


def test_nontransfer_ml_features_ignore_transfer_scenario_signals() -> None:
    facts = payload()["scenarioFacts"]
    risk = ScenarioFactsDto.model_validate(facts)
    facts.update(
        baselineStatus="UNKNOWN",
        baselineCount=0,
        baselineDays=0,
        baselineTotal=0,
        baselineMean=None,
        deviceStatus="UNKNOWN",
        deviceEventId=None,
        deviceFirstSeenAt=None,
        deviceCoverageFactId=None,
        deviceTrustFactId=None,
    )
    unknown = ScenarioFactsDto.model_validate(facts)
    assert extract_features(
        amount=Decimal("10000"), channel="ATM", cutoff=T, facts=risk, transfer=False
    ) == extract_features(
        amount=Decimal("10000"), channel="ATM", cutoff=T, facts=unknown, transfer=False
    )
