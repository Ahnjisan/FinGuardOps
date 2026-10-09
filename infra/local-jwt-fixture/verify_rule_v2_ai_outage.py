#!/usr/bin/env python3
"""Prepare a separate CRITICAL case while AI is ready, then test report outage."""

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid

import evaluate_qwen_reports as shared


def prepare():
    manifest = shared.load_fixtures("/opt/local-jwt-fixture/rule_v2_critical_fixture.json")
    fixture = {**manifest["fixtures"][0], "scoringPolicyVersion": manifest["scoringPolicyVersion"]}
    events, transaction = shared.make_input(fixture)
    for event in events:
        shared.api("POST", "/api/v1/behavior-events", "service-behavior-ingestor", event, expected=201)
    created = shared.api("POST", "/api/v1/transactions", "service-transaction-ingestor",
                         transaction, key=shared.idempotency_key("tx"), expected=201)
    transaction_id = transaction["transactionId"]
    case_id = created["caseId"]
    adopted = shared.api("GET", "/api/v1/transactions/" + transaction_id
                         + "/adopted-detection-result", "user-analyst")
    version = shared.assert_detection(fixture, created, adopted)
    case = shared.api("GET", "/api/v1/cases/" + case_id, "user-analyst")["case"]
    if case["caseStatus"] != "OPEN":
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    shared.api("PATCH", "/api/v1/cases/" + case_id + "/status", "user-analyst",
               {"targetStatus": "IN_REVIEW", "assigneeRef": str(uuid.uuid4()),
                "reasonCode": "CASE_REVIEW_STARTED", "expectedVersion": case["concurrencyVersion"]})
    snapshot = shared.business_snapshot(case_id, transaction_id)
    if snapshot["transactionStatus"] != "HELD" or snapshot["caseStatus"] != "IN_REVIEW":
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    return {"status": "PREPARED", "transactionId": transaction_id, "caseId": case_id,
            "detectionResultVersion": version, "snapshot": snapshot}


def verify(prepared):
    if not isinstance(prepared, dict) or prepared.get("status") != "PREPARED":
        raise shared.EvaluationError("OUTAGE_CASE_NOT_PREPARED")
    transaction_id, case_id = prepared["transactionId"], prepared["caseId"]
    baseline = prepared["snapshot"]
    if shared.business_snapshot(case_id, transaction_id) != baseline:
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    body = {"detectionResultVersion": prepared["detectionResultVersion"], "regenerationReason": None}
    request = urllib.request.Request(shared.BASE + "/api/v1/cases/" + case_id + "/ai-reports",
        data=json.dumps(body, separators=(",", ":")).encode(), method="POST",
        headers={"Authorization": "Bearer " + shared.mint("user-analyst"),
                 "Content-Type": "application/json", "Idempotency-Key": shared.idempotency_key("report")})
    try:
        urllib.request.urlopen(request, timeout=20).close()
        raise shared.EvaluationError("REPORT_UNEXPECTEDLY_ACCEPTED")
    except urllib.error.HTTPError as exc:
        if exc.code != 503:
            raise shared.EvaluationError("API_STATUS_MISMATCH") from exc
    if shared.business_snapshot(case_id, transaction_id) != baseline:
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    return {"status": "VERIFIED", "transactionId": transaction_id,
            "caseId": case_id, "reportRequestStatus": 503}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "verify"))
    args = parser.parse_args()
    try:
        result = prepare() if args.mode == "prepare" else verify(json.load(sys.stdin))
    except Exception:
        result = {"status": "FAILED", "errorCode": "AI_OUTAGE_CONTRACT_FAILED"}
    print(json.dumps(result, separators=(",", ":")))
    return 0 if result["status"] in {"PREPARED", "VERIFIED"} else 1


if __name__ == "__main__":
    sys.exit(main())
