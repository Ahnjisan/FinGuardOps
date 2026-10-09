#!/usr/bin/env python3
"""One authenticated Rule v2 business scenario; prints only IDs and safe status."""

import json
import sys
import uuid
import urllib.error
import urllib.request

import evaluate_qwen_reports as shared


def verify():
    manifest = shared.load_fixtures(
        "/opt/local-jwt-fixture/rule_v2_critical_fixture.json")
    fixture = {**manifest["fixtures"][0],
               "scoringPolicyVersion": manifest["scoringPolicyVersion"]}
    events, transaction = shared.make_input(fixture)
    for event in events:
        shared.api("POST", "/api/v1/behavior-events", "service-behavior-ingestor",
                   event, expected=201)
    shared.api("POST", "/api/v1/behavior-events", "service-behavior-ingestor",
               events[0], expected=200)
    repeated_fact = {**events[0], "eventId": str(uuid.uuid4())}
    shared.api("POST", "/api/v1/behavior-events", "service-behavior-ingestor",
               repeated_fact, expected=201)
    key = shared.idempotency_key("tx")
    created = shared.api("POST", "/api/v1/transactions", "service-transaction-ingestor",
                         transaction, key=key, expected=201)
    repeated = shared.api("POST", "/api/v1/transactions", "service-transaction-ingestor",
                          transaction, key=key, expected=201)
    if repeated.get("transactionId") != created.get("transactionId") or repeated.get("caseId") != created.get("caseId"):
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    conflicting = {**transaction, "amount": "12000001"}
    request = urllib.request.Request(shared.BASE + "/api/v1/transactions",
        data=json.dumps(conflicting).encode(), method="POST",
        headers={"Authorization": "Bearer " + shared.mint("service-transaction-ingestor"),
                 "Content-Type": "application/json", "Idempotency-Key": key})
    try:
        urllib.request.urlopen(request, timeout=20).close()
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    except urllib.error.HTTPError as error:
        if error.code != 409:
            raise shared.EvaluationError("BUSINESS_STATE_CHANGED") from error
    transaction_id = transaction["transactionId"]
    case_id = created.get("caseId")
    adopted = shared.api("GET", "/api/v1/transactions/" + transaction_id
                         + "/adopted-detection-result", "user-analyst")
    version = shared.assert_detection(fixture, created, adopted)
    case = shared.api("GET", "/api/v1/cases/" + case_id, "user-analyst")["case"]
    if case["caseStatus"] != "OPEN" or case["finalDisposition"] is not None:
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    changed = shared.api("PATCH", "/api/v1/cases/" + case_id + "/status",
                         "user-analyst", {"targetStatus": "IN_REVIEW",
                                          "assigneeRef": str(uuid.uuid4()),
                                          "reasonCode": "CASE_REVIEW_STARTED",
                                          "expectedVersion": case["concurrencyVersion"]})
    note = shared.api("POST", "/api/v1/cases/" + case_id + "/notes", "user-analyst",
                      {"content": "Review adopted Rule evidence and transaction link.",
                       "expectedVersion": changed["concurrencyVersion"]}, expected=201)
    if note["concurrencyVersion"] <= changed["concurrencyVersion"]:
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    baseline = shared.business_snapshot(case_id, transaction_id)
    if baseline["transactionStatus"] != "HELD" or baseline["caseStatus"] != "IN_REVIEW":
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    accepted = shared.api("POST", "/api/v1/cases/" + case_id + "/ai-reports",
                          "user-analyst", {"detectionResultVersion": version,
                                           "regenerationReason": None},
                          key=shared.idempotency_key("report"), expected=202)
    request_id, execution_id = shared.require_new_execution(accepted, set())
    current = shared.poll(case_id, request_id, execution_id)
    detail = shared.api("GET", "/api/v1/ai-report-requests/" + request_id,
                        "user-platform-admin")
    report = current.get("currentReport")
    if (detail.get("reportStatus") not in {"COMPLETED", "FALLBACK_COMPLETED"}
            or detail.get("caseId") != case_id
            or detail.get("executionId") != execution_id
            or detail.get("detectionResultVersion") != version
            or not isinstance(report, dict)
            or report.get("caseId") != case_id
            or report.get("executionId") != execution_id
            or report.get("initiatingAiRequestId") != request_id
            or report.get("detectionResultVersion") != version
            or report.get("reportStatus") != detail.get("reportStatus")
            or not all(shared.quality(report, fixture["expectedReasonCodes"]).values())
            or shared.business_snapshot(case_id, transaction_id) != baseline):
        raise shared.EvaluationError("REPORT_QUALITY_FAILED")
    return {"status": "VERIFIED", "transactionId": transaction_id,
            "caseId": case_id, "detectionResultVersion": version,
            "aiRequestId": request_id, "executionId": execution_id,
            "riskScore": 85, "riskLevel": "CRITICAL", "transactionStatus": "HELD",
            "caseStatus": "IN_REVIEW", "reportStatus": detail["reportStatus"],
            "reportSource": detail.get("reportSource"),
            "fallbackTriggerCode": detail.get("fallbackTriggerCode"),
            "attemptCount": len(detail.get("attempts", [])),
            "inputTokens": detail.get("inputTokens") if detail.get("attempts") else None,
            "outputTokens": detail.get("outputTokens") if detail.get("attempts") else None,
            "estimatedCost": detail.get("estimatedCost"),
            "auditCount": baseline["auditCount"]}


if __name__ == "__main__":
    try:
        result = verify()
    except shared.EvaluationError as exc:
        result = {"status": "FAILED", "errorCode": exc.code}
    except Exception:
        result = {"status": "FAILED", "errorCode": "UNEXPECTED_FAILURE"}
    print(json.dumps(result, separators=(",", ":")))
    sys.exit(0 if result["status"] == "VERIFIED" else 1)
