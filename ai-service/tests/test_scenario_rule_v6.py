"""SCN-006/007 pinned local Mock source-to-use contract."""

from copy import deepcopy
from datetime import timedelta
from uuid import uuid4

from fastapi.testclient import TestClient

from finguardops_ai.main import app
from test_scenario_rule_v4 import T, wire
from test_scenario_rule_v5 import _request


def _unknown(reason: str) -> dict:
    return {
        "status": "UNKNOWN",
        "reasonCode": reason,
        "sourceTransactionId": None,
        "currentLinkId": None,
        "historyCoverageFactId": None,
        "beneficiaryEventId": None,
        "sourceOccurredAt": None,
        "sourceApprovedAt": None,
        "sourceAmount": "0",
        "linkedAmount": "0",
        "ratioBasisPoints": 0,
        "linkedPriorTransactionIds": [],
    }


def _request_v6(kind: str) -> dict:
    data = _request()
    data["transaction"]["transactionType"] = (
        "ATM_WITHDRAWAL" if kind == "atm" else "ACCOUNT_TRANSFER"
    )
    if kind == "atm":
        data["transaction"]["recipientAccountRef"] = None
    data["transaction"]["amount"] = "8000000"
    for window in data["aggregationFacts"].values():
        if isinstance(window, dict):
            window.update(transferCount=0, distinctCount=0, totalAmount="0")
            if kind == "atm":
                window["coverageFactId"] = None
    for code, signal in (
        ("VERIFIED_INBOUND_ATM_DRAIN", "APPROVED_INBOUND_LINKED_ATM_EIGHTY_PERCENT"),
        ("VERIFIED_LOAN_NEW_RECIPIENT_DRAIN", "APPROVED_LOAN_LINKED_NEW_RECIPIENT_EIGHTY_PERCENT"),
    ):
        data["ruleVersions"].append(
            {
                "fraudRuleId": str(uuid4()),
                "ruleCode": code,
                "lifecycleStatus": "ACTIVE",
                "ruleVersionId": str(uuid4()),
                "versionNumber": 1,
                "status": "PUBLISHED",
                "reasonCode": code,
                "weight": 50,
                "conditionDefinition": {
                    "contractVersion": "SCN006-007-contract-v1",
                    "signal": signal,
                },
                "effectiveFrom": wire(T - timedelta(days=1)),
                "effectiveTo": None,
            }
        )
    flow = _unknown("NO_LINK")
    flow.update(
        status="AVAILABLE",
        reasonCode="VERIFIED_SOURCE_TO_USE",
        sourceTransactionId=str(uuid4()),
        currentLinkId=str(uuid4()),
        historyCoverageFactId=str(uuid4()),
        beneficiaryEventId=str(uuid4()) if kind == "loan" else None,
        sourceOccurredAt=wire(T - timedelta(minutes=20)),
        sourceApprovedAt=wire(T - timedelta(minutes=19)),
        sourceAmount="10000000",
        linkedAmount="8000000",
        ratioBasisPoints=8000,
    )
    data["fundFlowFacts"] = {
        "sourceVersion": "SCN006-007-contract-v1",
        "atm": flow if kind == "atm" else _unknown("NOT_ATM_WITHDRAWAL"),
        "loan": flow if kind == "loan" else _unknown("NOT_OUTBOUND_TRANSFER"),
    }
    return data


def test_verified_atm_and_loan_have_exact_high_evidence() -> None:
    for kind, rule in (
        ("atm", "VERIFIED_INBOUND_ATM_DRAIN"),
        ("loan", "VERIFIED_LOAN_NEW_RECIPIENT_DRAIN"),
    ):
        response = TestClient(app).post(
            "/api/v5/rule-analysis",
            json=_request_v6(kind),
            headers={"X-Trace-Id": "scenario-v6-test"},
        )
        assert response.status_code == 200, response.text
        analysis = response.json()["analysis"]
        assert analysis["scoringResult"]["riskLevel"] == "HIGH"
        assert len(analysis["scoringResult"]["ruleContributions"]) == 14
        observation = next(
            item["observationSummary"] for item in analysis["evidence"] if item["ruleCode"] == rule
        )
        assert observation["ratioBasisPoints"] == 8000
        assert observation["mockSourceVersion"] == "v2"


def test_unknown_and_invalid_cutoff_cannot_attribute_funds() -> None:
    data = _request_v6("atm")
    data["fundFlowFacts"]["atm"] = _unknown("ACCOUNT_HISTORY_INCOMPLETE")
    response = TestClient(app).post(
        "/api/v5/rule-analysis",
        json=data,
        headers={"X-Trace-Id": "scenario-v6-test"},
    )
    assert response.status_code == 200, response.text
    assert not response.json()["analysis"]["scoringResult"]["ruleContributions"][12]["matched"]
    late = deepcopy(_request_v6("atm"))
    late["fundFlowFacts"]["atm"]["sourceApprovedAt"] = wire(T + timedelta(seconds=1))
    rejected = TestClient(app).post(
        "/api/v5/rule-analysis",
        json=late,
        headers={"X-Trace-Id": "scenario-v6-test"},
    )
    assert rejected.status_code in (400, 422)
