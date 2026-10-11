"""Synthetic SCN-004/005 window Rule binding and counterexamples."""

from datetime import timedelta
from uuid import uuid4

from fastapi.testclient import TestClient

from finguardops_ai.main import app
from test_scenario_rule_v4 import T, payload, wire


def _request() -> dict:
    data = payload()
    facts = data["scenarioFacts"]
    facts.update(
        baselineStatus="INSUFFICIENT",
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
    for code, signal in (
        ("DISTRIBUTED_OUTBOUND_WINDOW", "THREE_RECIPIENTS_TEN_MINUTES"),
        ("MANY_CUSTOMER_INBOUND_WINDOW", "THREE_CUSTOMERS_TWENTY_FOUR_HOURS"),
    ):
        data["ruleVersions"].append(
            dict(
                fraudRuleId=str(uuid4()),
                ruleCode=code,
                lifecycleStatus="ACTIVE",
                ruleVersionId=str(uuid4()),
                versionNumber=1,
                status="PUBLISHED",
                reasonCode=code,
                weight=25,
                conditionDefinition={"contractVersion": "SCN004-005-contract-v1", "signal": signal},
                effectiveFrom=wire(T - timedelta(days=1)),
                effectiveTo=None,
            )
        )
    data["aggregationFacts"] = {
        "sourceVersion": "SCN004-005-contract-v1",
        "outbound": {
            "transferCount": 3,
            "distinctCount": 3,
            "totalAmount": "3000000",
            "unknownCount": 0,
            "coverageFactId": str(uuid4()),
        },
        "inbound": {
            "transferCount": 3,
            "distinctCount": 3,
            "totalAmount": "5000000",
            "unknownCount": 0,
            "coverageFactId": str(uuid4()),
        },
    }
    return data


def test_two_windows_share_cap_and_keep_exact_evidence() -> None:
    result = TestClient(app).post(
        "/api/v4/rule-analysis", json=_request(), headers={"X-Trace-Id": "scenario-v5-test"}
    )
    assert result.status_code == 200, result.text
    analysis = result.json()["analysis"]
    assert analysis["scoringResult"]["riskScore"] == 40
    assert analysis["scoringResult"]["riskLevel"] == "MEDIUM"
    assert len(analysis["scoringResult"]["ruleContributions"]) == 12
    assert analysis["scoringResult"]["groupSummaries"][-1]["appliedScore"] == 40
    assert [item["ruleCode"] for item in analysis["evidence"]] == [
        "DISTRIBUTED_OUTBOUND_WINDOW",
        "MANY_CUSTOMER_INBOUND_WINDOW",
    ]


def test_missing_proof_and_incomplete_history_do_not_match() -> None:
    data = _request()
    data["aggregationFacts"]["outbound"]["coverageFactId"] = None
    data["aggregationFacts"]["inbound"]["unknownCount"] = 1
    result = TestClient(app).post(
        "/api/v4/rule-analysis", json=data, headers={"X-Trace-Id": "scenario-v5-test"}
    )
    assert result.status_code == 200, result.text
    assert result.json()["analysis"]["scoringResult"]["riskScore"] == 0


def test_malformed_rule_binding_is_rejected() -> None:
    data = _request()
    data["ruleVersions"][-1]["weight"] = 30
    result = TestClient(app).post(
        "/api/v4/rule-analysis", json=data, headers={"X-Trace-Id": "scenario-v5-test"}
    )
    assert result.status_code in (400, 422)
