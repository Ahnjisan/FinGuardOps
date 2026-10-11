"""SCN-008 requires two proven external accounts and an exact cutoff-safe chain."""

from copy import deepcopy
from datetime import timedelta
from uuid import uuid4

from fastapi.testclient import TestClient

from finguardops_ai.main import app
from test_scenario_rule_v4 import T, wire
from test_scenario_rule_v6 import _request_v6, _unknown


def _request_v7() -> dict:
    data = _request_v6("loan")
    data["fundFlowFacts"]["loan"] = _unknown("NO_LINK")
    data["ruleVersions"].append(
        {
            "fraudRuleId": str(uuid4()),
            "ruleCode": "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND",
            "lifecycleStatus": "ACTIVE",
            "ruleVersionId": str(uuid4()),
            "versionNumber": 1,
            "status": "PUBLISHED",
            "reasonCode": "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND",
            "weight": 50,
            "conditionDefinition": {
                "contractVersion": "SCN008-contract-v1",
                "signal": "TWO_OWNED_EXTERNAL_ACCOUNTS_CONCENTRATE_AND_RESEND",
            },
            "effectiveFrom": wire(T - timedelta(days=1)),
            "effectiveTo": None,
        }
    )
    data["openBankingFacts"] = {
        "sourceVersion": "SCN008-contract-v1",
        "status": "AVAILABLE",
        "reasonCode": "VERIFIED_OPEN_BANKING_CHAIN",
        "historyCoverageFactId": str(uuid4()),
        "concentrationStartedAt": wire(T - timedelta(minutes=10)),
        "concentrationCompletedAt": wire(T - timedelta(minutes=8)),
        "concentrationAmount": "10000000",
        "resendAmount": "8000000",
        "ratioBasisPoints": 8000,
        "stages": [
            {
                "sourceTransactionId": str(uuid4()),
                "ownershipAssertionId": str(uuid4()),
                "linkId": str(uuid4()),
                "occurredAt": wire(T - timedelta(minutes=10)),
                "approvedAt": wire(T - timedelta(minutes=9)),
                "amount": "5000000",
            },
            {
                "sourceTransactionId": str(uuid4()),
                "ownershipAssertionId": str(uuid4()),
                "linkId": str(uuid4()),
                "occurredAt": wire(T - timedelta(minutes=8)),
                "approvedAt": wire(T - timedelta(minutes=7)),
                "amount": "5000000",
            },
        ],
    }
    return data


def _analyze(data: dict):
    return TestClient(app).post(
        "/api/v6/rule-analysis", json=data, headers={"X-Trace-Id": "scenario-v7-test"}
    )


def test_verified_chain_has_pinned_rule_and_evidence() -> None:
    response = _analyze(_request_v7())
    assert response.status_code == 200, response.text
    analysis = response.json()["analysis"]
    assert len(analysis["scoringResult"]["ruleContributions"]) == 15
    assert analysis["scoringResult"]["ruleContributions"][14]["matched"]
    evidence = next(row for row in analysis["evidence"] if row["ruleId"] == "R015")
    assert len(evidence["observationSummary"]["stages"]) == 2
    assert evidence["observationSummary"]["ratioBasisPoints"] == 8000


def test_unknown_chain_does_not_match() -> None:
    data = _request_v7()
    data["openBankingFacts"] = {
        "sourceVersion": "SCN008-contract-v1",
        "status": "UNKNOWN",
        "reasonCode": "MOCK_PARTIAL_OR_FAILED",
        "historyCoverageFactId": None,
        "concentrationStartedAt": None,
        "concentrationCompletedAt": None,
        "concentrationAmount": "0",
        "resendAmount": "0",
        "ratioBasisPoints": 0,
        "stages": [],
    }
    response = _analyze(data)
    assert response.status_code == 200, response.text
    assert not response.json()["analysis"]["scoringResult"]["ruleContributions"][14]["matched"]


def test_aggregation_signal_is_preserved_in_v7_chain() -> None:
    data = _request_v7()
    data["aggregationFacts"]["outbound"].update(
        transferCount=3, distinctCount=3, totalAmount="6000000"
    )
    response = _analyze(data)
    assert response.status_code == 200, response.text
    rows = response.json()["analysis"]["scoringResult"]["ruleContributions"]
    assert rows[10]["matched"]
    assert not rows[13]["matched"]
    assert rows[14]["matched"]


def test_late_approval_and_duplicate_source_are_rejected() -> None:
    late = _request_v7()
    late["openBankingFacts"]["stages"][1]["approvedAt"] = wire(T + timedelta(seconds=1))
    assert _analyze(late).status_code == 422
    duplicate = deepcopy(_request_v7())
    duplicate["openBankingFacts"]["stages"][1]["sourceTransactionId"] = duplicate[
        "openBankingFacts"
    ]["stages"][0]["sourceTransactionId"]
    assert _analyze(duplicate).status_code == 422
