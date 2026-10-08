#!/usr/bin/env python3
"""Authenticated local Qwen evaluation. Stdout is one redacted JSON report.

Run inside local-jwt-fixture, where the mint socket is private. The caller owns
the report file outside the repository; this process never writes credentials.
"""

import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BASE = "http://127.0.0.1:8080"
RULES = {
    "TRANSFER_ABSOLUTE_HIGH_AMOUNT": (15, "amount"),
    "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT": (20, "security"),
    "RECENT_SECURITY_CHANGE_HIGH_AMOUNT": (40, "security"),
    "RECENT_BENEFICIARY_TRANSFER": (10, "security"),
}
EVENTS = {"DEVICE_REGISTERED", "PASSWORD_CHANGED", "TRANSFER_LIMIT_CHANGED", "BENEFICIARY_REGISTERED"}
CHECKLIST = {
    "채택된 RULE 근거와 원거래를 대조하세요.",
    "사유 코드와 RULE 버전을 확인하세요.",
    "채택 탐지 결과의 위험 등급과 점수 근거를 검토하세요.",
}
# This catches common prohibited claims; it is not a general factuality classifier.
UNSUPPORTED_CLAIMS = re.compile(
    r"고객이|고객은|실제 신규 기기|최초 수취인|한도 상향|한도를 초과|"
    r"거래를 차단|거래 차단|사기 확정|최종 판정|제재 완료|행동 패턴|"
    r"확인되었습니다|발생했습니다"
)
TERMINAL = {"COMPLETED", "FALLBACK_COMPLETED", "FAILED"}
ATTEMPT_FIELDS = ("attemptNumber", "provider", "model", "outcome", "inputTokens",
                  "outputTokens", "latencyMs", "estimatedCost", "costCurrency")
FALLBACK_BY_ATTEMPT = {"TIMEOUT": "LLM_TIMEOUT",
                       "CONNECTION_FAILED": "LLM_UNAVAILABLE",
                       "PROVIDER_ERROR": "LLM_UNAVAILABLE",
                       "INVALID_OUTPUT": "LLM_OUTPUT_REJECTED"}
PRE_GENERATION_FAILURES = {"REPORT_INPUT_CHANGED", "FASTAPI_CONNECTION_FAILED",
                           "FASTAPI_TIMEOUT", "DEPENDENCY_UNAVAILABLE",
                           "FASTAPI_RESPONSE_INVALID"}
SAFE_ERROR_CODES = {
    "FIXTURE_INVALID", "MODEL_IDENTITY_MISMATCH", "API_STATUS_MISMATCH",
    "DETECTION_MISMATCH", "CACHE_OR_SHARED_EXECUTION", "REQUEST_ID_REUSED",
    "RESULT_ID_MISMATCH", "ATTEMPT_MISMATCH", "ATTEMPT_LIMIT",
    "REPORT_QUALITY_FAILED", "BUSINESS_STATE_CHANGED", "POLL_TIMEOUT",
    "LOCAL_DEPENDENCY_UNAVAILABLE", "UNEXPECTED_FAILURE",
}


class EvaluationError(Exception):
    def __init__(self, code):
        if code not in SAFE_ERROR_CODES:
            code = "UNEXPECTED_FAILURE"
        super().__init__(code)
        self.code = code


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def stamp(value):
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def idempotency_key(kind):
    if kind not in {"tx", "report"}:
        raise ValueError("invalid key kind")
    return "qwen367-" + kind + "-" + uuid.uuid4().hex


def score(codes):
    amount = sum(RULES[code][0] for code in codes if RULES[code][1] == "amount")
    security = sum(RULES[code][0] for code in codes if RULES[code][1] == "security")
    total = min(100, min(15, amount) + min(60, security))
    return total, ("CRITICAL" if total >= 80 else "HIGH" if total >= 50 else
                   "MEDIUM" if total >= 20 else "LOW")


def load_fixtures(path):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        if set(data) != {"fixtureVersion", "scoringPolicyVersion", "modelEligibleRiskLevels",
                         "criticalAvailability", "fixtures"}:
            raise ValueError()
        if data["scoringPolicyVersion"] != "scoring-policy-v1" or data["modelEligibleRiskLevels"] != ["HIGH", "CRITICAL"]:
            raise ValueError()
        if data["criticalAvailability"] != "UNREACHABLE_WITH_RULE_V1_MAX_75":
            raise ValueError()
        if not isinstance(data["fixtureVersion"], str) or not re.fullmatch(r"[a-z0-9-]{8,64}", data["fixtureVersion"]):
            raise ValueError()
        fixtures = data["fixtures"]
        if not isinstance(fixtures, list) or not fixtures:
            raise ValueError()
        seen = set()
        for item in fixtures:
            if set(item) != {"id", "amount", "events", "expectedRiskScore", "expectedRiskLevel",
                             "expectedReasonCodes", "reportExpected"}:
                raise ValueError()
            if not isinstance(item["id"], str) or not re.fullmatch(r"[a-z0-9_]{4,64}", item["id"]) or item["id"] in seen:
                raise ValueError()
            seen.add(item["id"])
            if item["amount"] != "12000000" or not isinstance(item["events"], list) or len(item["events"]) != len(set(item["events"])) or not set(item["events"]) <= EVENTS:
                raise ValueError()
            matched = {"TRANSFER_ABSOLUTE_HIGH_AMOUNT"}
            if "DEVICE_REGISTERED" in item["events"]:
                matched.add("RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT")
            if {"PASSWORD_CHANGED", "TRANSFER_LIMIT_CHANGED"} <= set(item["events"]):
                matched.add("RECENT_SECURITY_CHANGE_HIGH_AMOUNT")
            if "BENEFICIARY_REGISTERED" in item["events"]:
                matched.add("RECENT_BENEFICIARY_TRANSFER")
            actual_score, level = score(matched)
            if (item["expectedReasonCodes"] != [code for code in RULES if code in matched]
                    or type(item["expectedRiskScore"]) is not int or item["expectedRiskScore"] != actual_score
                    or item["expectedRiskLevel"] != level or type(item["reportExpected"]) is not bool
                    or item["reportExpected"] != (level in {"HIGH", "CRITICAL"})):
                raise ValueError()
        return data
    except (OSError, ValueError, TypeError, KeyError) as exc:
        raise EvaluationError("FIXTURE_INVALID") from exc


def mint(identity):
    from fixture import socket_request
    return socket_request({"command": "mint", "identity": identity, "variant": "normal"}, 5)["token"]


def api(method, path, identity, body=None, key=None, expected=200):
    headers = {"Authorization": "Bearer " + mint(identity)}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if key is not None:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(
        BASE + path, data=None if body is None else json.dumps(body, separators=(",", ":")).encode(),
        headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            if response.status != expected:
                raise EvaluationError("API_STATUS_MISMATCH")
            return json.load(response)
    except urllib.error.HTTPError as exc:
        # Neither response body nor Provider error text is reported.
        raise EvaluationError("API_STATUS_MISMATCH") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise EvaluationError("LOCAL_DEPENDENCY_UNAVAILABLE") from exc


def model_identity(expected_tag, expected_digest, expected_quantization):
    try:
        with urllib.request.urlopen("http://host.docker.internal:11434/api/tags", timeout=10) as response:
            models = json.load(response)["models"]
        matched = [item for item in models if item.get("name") == expected_tag]
        if len(matched) != 1 or matched[0].get("digest") != expected_digest or matched[0].get("details", {}).get("quantization_level") != expected_quantization:
            raise EvaluationError("MODEL_IDENTITY_MISMATCH")
    except (urllib.error.URLError, TimeoutError, OSError, ValueError, KeyError, TypeError) as exc:
        raise EvaluationError("MODEL_IDENTITY_MISMATCH") from exc
    return {"tag": expected_tag, "digest": expected_digest, "quantization": expected_quantization}


def make_input(fixture):
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    run_id = uuid.uuid4().hex
    customer = "qwen367-customer-" + run_id
    sender = "qwen367-sender-" + run_id
    recipient = "qwen367-recipient-" + run_id
    device = "qwen367-device-" + run_id
    offsets = {"DEVICE_REGISTERED": -240, "PASSWORD_CHANGED": -180,
               "TRANSFER_LIMIT_CHANGED": -120, "BENEFICIARY_REGISTERED": -60}
    events = []
    for kind in fixture["events"]:
        event = {"eventId": str(uuid.uuid4()), "eventType": kind,
                 "occurredAt": stamp(now + dt.timedelta(seconds=offsets[kind])),
                 "externalCustomerRef": customer}
        if kind in {"TRANSFER_LIMIT_CHANGED", "BENEFICIARY_REGISTERED"}:
            event["accountRef"] = sender
        if kind == "BENEFICIARY_REGISTERED":
            event["beneficiaryRef"] = recipient
        if kind == "DEVICE_REGISTERED":
            event["deviceRef"] = device
        events.append(event)
    transaction = {"transactionId": str(uuid.uuid4()), "transactionType": "ACCOUNT_TRANSFER",
                   "amount": fixture["amount"], "currencyCode": "KRW", "occurredAt": stamp(now),
                   "externalCustomerRef": customer, "senderAccountRef": sender,
                   "recipientAccountRef": recipient, "channel": "MOBILE_BANKING"}
    if "DEVICE_REGISTERED" in fixture["events"]:
        transaction["deviceRef"] = device
    return events, transaction


def assert_detection(fixture, created, adopted):
    result = adopted.get("adoptedResult")
    evidence = result.get("ruleEvidence") if isinstance(result, dict) else None
    codes = [item.get("reasonCode") for item in evidence] if isinstance(evidence, list) else []
    if (created.get("riskLevel") != fixture["expectedRiskLevel"]
            or created.get("riskResponseOutcome") != ("ADDITIONAL_AUTH_REQUIRED" if fixture["reportExpected"] else "APPROVED")
            or bool(created.get("caseId")) != fixture["reportExpected"]
            or not isinstance(result, dict) or result.get("riskLevel") != fixture["expectedRiskLevel"]
            or result.get("riskScore") != fixture["expectedRiskScore"]
            or result.get("scoringPolicyVersion") != "scoring-policy-v1"
            or codes != fixture["expectedReasonCodes"] or len(codes) != len(set(codes))):
        raise EvaluationError("DETECTION_MISMATCH")
    return result["detectionResultVersion"]


def quality(report, expected_codes):
    if not isinstance(report, dict):
        return {"structure": False, "reasonCodes": False, "checklist": False, "unsupportedClaims": False}
    reasons = report.get("keyReasons")
    checklist = report.get("investigationChecklist")
    required = {"reportId", "executionId", "initiatingAiRequestId", "caseId",
                "detectionResultVersion", "reportStatus", "reportSource", "summary",
                "keyReasons", "timelineSummary", "investigationChecklist", "promptVersion",
                "modelVersion", "generatedAt", "failureCode", "fallbackTriggerCode", "traceId"}
    structure = (set(report) == required and isinstance(report.get("summary"), str)
                 and 1 <= len(report["summary"]) <= 600
                 and isinstance(reasons, list) and 1 <= len(reasons) <= 20
                 and all(isinstance(x, dict) and set(x) == {"reasonCode", "description"}
                         and isinstance(x.get("reasonCode"), str)
                         and isinstance(x.get("description"), str) and 1 <= len(x["description"]) <= 240 for x in reasons)
                 and isinstance(checklist, list) and 1 <= len(checklist) <= 8
                 and all(isinstance(x, str) for x in checklist)
                 and isinstance(report.get("timelineSummary"), str)
                 and isinstance(report.get("promptVersion"), str)
                 and isinstance(report.get("modelVersion"), str))
    codes = [x.get("reasonCode") for x in reasons] if isinstance(reasons, list) and all(isinstance(x, dict) for x in reasons) else []
    code_ok = (all(isinstance(code, str) for code in codes)
               and len(codes) == len(set(codes)) and set(codes) == set(expected_codes))
    checklist_ok = (isinstance(checklist, list) and 1 <= len(checklist) <= 2
                    and all(isinstance(x, str) for x in checklist)
                    and len(checklist) == len(set(checklist)) and set(checklist) <= CHECKLIST)
    text = [report.get("summary", "")]
    if isinstance(reasons, list):
        text.extend(x.get("description", "") for x in reasons if isinstance(x, dict))
    if isinstance(checklist, list):
        text.extend(x for x in checklist if isinstance(x, str))
    claims_ok = all(isinstance(x, str) and not UNSUPPORTED_CLAIMS.search(x) for x in text)
    return {"structure": structure, "reasonCodes": code_ok, "checklist": checklist_ok,
            "unsupportedClaims": claims_ok}


def compare_attempts(first, second, digest):
    if not isinstance(first, list) or not isinstance(second, list) or len(first) != len(second):
        raise EvaluationError("ATTEMPT_MISMATCH")
    if len(first) > 2:
        raise EvaluationError("ATTEMPT_LIMIT")
    safe = []
    for index, (left, right) in enumerate(zip(first, second), 1):
        if any(left.get(field) != right.get(field) for field in ATTEMPT_FIELDS):
            raise EvaluationError("ATTEMPT_MISMATCH")
        if (left.get("attemptNumber") != index or left.get("provider") != "OLLAMA_LOCAL"
                or left.get("model") != digest or left.get("outcome") not in
                {"COMPLETED", "TIMEOUT", "CONNECTION_FAILED", "PROVIDER_ERROR", "INVALID_OUTPUT"}
                or type(left.get("latencyMs")) is not int or left["latencyMs"] < 0
                or any(x is not None and (type(x) is not int or x < 0)
                       for x in (left.get("inputTokens"), left.get("outputTokens")))
                or left.get("estimatedCost") is not None or left.get("costCurrency") is not None):
            raise EvaluationError("ATTEMPT_MISMATCH")
        total = (None if left["inputTokens"] is None or left["outputTokens"] is None
                 else left["inputTokens"] + left["outputTokens"])
        if ("totalTokens" not in left or "totalTokens" not in right
                or left["totalTokens"] != total or right["totalTokens"] != total):
            raise EvaluationError("ATTEMPT_MISMATCH")
        safe.append({field: left.get(field) for field in ATTEMPT_FIELDS})
    return safe


def compare_persisted_attempts(reported, persisted, digest):
    """Compare the redacted API report with ordered provider_call_attempt DB rows."""
    if (not isinstance(reported, list) or not isinstance(persisted, list)
            or len(reported) != len(persisted) or len(persisted) > 2):
        raise EvaluationError("ATTEMPT_MISMATCH")
    for number, (api_row, db_row) in enumerate(zip(reported, persisted), 1):
        if (not isinstance(api_row, dict) or not isinstance(db_row, dict)
                or set(api_row) != set(ATTEMPT_FIELDS)
                or set(db_row) != set(ATTEMPT_FIELDS)
                or api_row.get("attemptNumber") != number
                or db_row.get("attemptNumber") != number
                or any(api_row.get(field) != db_row[field] for field in ATTEMPT_FIELDS)):
            raise EvaluationError("ATTEMPT_MISMATCH")
        if api_row.get("model") != digest or api_row.get("provider") != "OLLAMA_LOCAL":
            raise EvaluationError("ATTEMPT_MISMATCH")
    return len(persisted)


def expected_token_total(attempts, field):
    # The Backend detail API currently returns 0 for an empty stored attempt list.
    # This aggregate is not evidence that the Provider received zero calls.
    values = [item[field] for item in attempts]
    return None if any(value is None for value in values) else sum(values)


def fallback_trigger_matches(trigger, outcomes):
    if outcomes:
        return trigger == FALLBACK_BY_ATTEMPT.get(outcomes[-1])
    # Model metadata timeout and unavailable/mismatched model occur before a
    # stored /api/chat attempt; no finer cause is exposed by the detail API.
    return trigger in {"LLM_TIMEOUT", "LLM_UNAVAILABLE"}


def validate_outcome(detail, attempts, report):
    status = detail.get("reportStatus")
    outcomes = [item["outcome"] for item in attempts]
    if len(outcomes) == 2 and outcomes[0] not in {"TIMEOUT", "CONNECTION_FAILED"}:
        raise EvaluationError("ATTEMPT_MISMATCH")
    if status == "COMPLETED":
        if (not outcomes or outcomes[-1] != "COMPLETED" or detail.get("reportSource") != "LLM"
                or detail.get("fallbackTriggerCode") is not None
                or detail.get("failureCode") is not None or detail.get("fallbackUsed") is not False):
            raise EvaluationError("ATTEMPT_MISMATCH")
    elif status == "FALLBACK_COMPLETED":
        trigger = detail.get("fallbackTriggerCode")
        if ("COMPLETED" in outcomes or detail.get("reportSource") != "TEMPLATE_FALLBACK"
                or detail.get("failureCode") is not None or detail.get("fallbackUsed") is not True
                or not fallback_trigger_matches(trigger, outcomes)):
            raise EvaluationError("ATTEMPT_MISMATCH")
    elif status == "FAILED":
        if report is not None or detail.get("reportSource") is not None:
            raise EvaluationError("RESULT_ID_MISMATCH")
        trigger = detail.get("fallbackTriggerCode")
        if detail.get("fallbackUsed") is not False or (
                trigger is None and (outcomes or detail.get("failureCode") not in PRE_GENERATION_FAILURES)
                or trigger is not None and
                (detail.get("failureCode") != "TEMPLATE_FALLBACK_FAILED"
                 or not fallback_trigger_matches(trigger, outcomes))):
            raise EvaluationError("ATTEMPT_MISMATCH")
    else:
        raise EvaluationError("RESULT_ID_MISMATCH")
    input_total = expected_token_total(attempts, "inputTokens")
    output_total = expected_token_total(attempts, "outputTokens")
    total = None if input_total is None or output_total is None else input_total + output_total
    if (detail.get("inputTokens") != input_total
            or detail.get("outputTokens") != output_total
            or detail.get("totalTokens") != total):
        raise EvaluationError("ATTEMPT_MISMATCH")
    if detail.get("estimatedCost") is not None or detail.get("costCurrency") is not None:
        raise EvaluationError("ATTEMPT_MISMATCH")


def require_new_execution(accepted, prior_ids):
    if (accepted.get("cacheHit") is not False or accepted.get("executionShared") is not False
            or accepted.get("reportStatus") != "PENDING" or not accepted.get("executionId")):
        raise EvaluationError("CACHE_OR_SHARED_EXECUTION")
    ids = (accepted.get("aiRequestId"), accepted.get("executionId"))
    if any(value in prior_ids for value in ids) or ids[0] == ids[1]:
        raise EvaluationError("REQUEST_ID_REUSED")
    prior_ids.update(ids)
    return ids


def poll(case_id, request_id, execution_id, deadline_seconds=300):
    deadline = time.monotonic() + deadline_seconds
    while True:
        current = api("GET", "/api/v1/cases/" + case_id + "/ai-reports/current", "user-analyst")
        latest = current.get("latestRequest")
        if (isinstance(latest, dict) and latest.get("aiRequestId") == request_id
                and latest.get("executionId") == execution_id
                and latest.get("reportStatus") in TERMINAL):
            return current
        if time.monotonic() >= deadline:
            raise EvaluationError("POLL_TIMEOUT")
        time.sleep(5)


def business_snapshot(case_id, transaction_id):
    case = api("GET", "/api/v1/cases/" + case_id, "user-analyst")["case"]
    transaction = api("GET", "/api/v1/transactions/" + transaction_id, "user-analyst")["transaction"]
    audit = api("GET", "/api/v1/cases/" + case_id + "/audit-logs?page=0&size=100&sort=changedAt,asc", "user-analyst")
    count = audit["page"]["totalElements"]
    if count > 100:
        raise EvaluationError("BUSINESS_STATE_CHANGED")
    return {"caseStatus": case["caseStatus"], "finalDisposition": case["finalDisposition"],
            "concurrencyVersion": case["concurrencyVersion"],
            "transactionStatus": transaction["processingStatus"],
            "auditActions": [item["action"] for item in audit["content"]],
            "auditCount": count}


def evaluate_one(fixture, repetition, prior_ids, digest, results):
    started_at = utc_now()
    events, transaction = make_input(fixture)
    row = {"fixtureId": fixture["id"], "repetition": repetition, "startedAt": started_at,
           "transactionId": transaction["transactionId"], "caseId": None,
           "reportEvaluated": False, "status": "INCOMPLETE"}
    results.append(row)
    for event in events:
        api("POST", "/api/v1/behavior-events", "service-behavior-ingestor", event, expected=201)
    created = api("POST", "/api/v1/transactions", "service-transaction-ingestor",
                  transaction, key=idempotency_key("tx"), expected=201)
    row["caseId"] = created.get("caseId")
    transaction_id = transaction["transactionId"]
    adopted = api("GET", "/api/v1/transactions/" + transaction_id + "/adopted-detection-result", "user-analyst")
    version = assert_detection(fixture, created, adopted)
    adopted_result = adopted["adoptedResult"]
    row.update({"caseId": created.get("caseId"),
           "observedRiskLevel": created["riskLevel"], "observedRiskScore": adopted_result["riskScore"],
           "adoptedReasonCodes": [item["reasonCode"] for item in adopted_result["ruleEvidence"]],
           "detectionResultVersion": version,
           "status": "DETECTION_VERIFIED"})
    if not fixture["reportExpected"]:
        row["finishedAt"] = utc_now()
        row["status"] = "NON_REPORT_CONTROL_VERIFIED"
        return row
    case_id = created["caseId"]
    before = api("GET", "/api/v1/cases/" + case_id, "user-analyst")["case"]
    api("PATCH", "/api/v1/cases/" + case_id + "/status", "user-analyst",
        {"targetStatus": "IN_REVIEW", "assigneeRef": str(uuid.uuid4()),
         "reasonCode": "CASE_REVIEW_STARTED", "expectedVersion": before["concurrencyVersion"]})
    baseline_state = business_snapshot(case_id, transaction_id)
    ai_start = time.monotonic()
    accepted = api("POST", "/api/v1/cases/" + case_id + "/ai-reports", "user-analyst",
                   {"detectionResultVersion": version, "regenerationReason": None},
                   key=idempotency_key("report"), expected=202)
    request_id, execution_id = require_new_execution(accepted, prior_ids)
    row.update({"aiRequestId": request_id, "executionId": execution_id,
                "status": "REQUEST_ACCEPTED"})
    current = poll(case_id, request_id, execution_id)
    wall_ms = round((time.monotonic() - ai_start) * 1000)
    detail = api("GET", "/api/v1/ai-report-requests/" + request_id, "user-platform-admin")
    second = api("GET", "/api/v1/ai-report-requests/" + request_id, "user-platform-admin")
    latest = current["latestRequest"]
    if (detail.get("aiRequestId") != request_id or detail.get("executionId") != execution_id
            or detail.get("caseId") != case_id or detail.get("reportStatus") != latest.get("reportStatus")
            or second.get("reportStatus") != detail.get("reportStatus")):
        raise EvaluationError("RESULT_ID_MISMATCH")
    attempts = compare_attempts(detail.get("attempts"), second.get("attempts"), digest)
    report = current.get("currentReport")
    q = quality(report, fixture["expectedReasonCodes"]) if latest["reportStatus"] != "FAILED" else None
    if report is not None and (report.get("caseId") != case_id
                               or report.get("executionId") != execution_id
                               or report.get("reportStatus") != detail.get("reportStatus")
                               or report.get("reportSource") != detail.get("reportSource")):
        raise EvaluationError("RESULT_ID_MISMATCH")
    if report is not None and (report.get("promptVersion") != detail.get("promptVersion")
                               or report.get("modelVersion") != detail.get("modelVersion")):
        raise EvaluationError("RESULT_ID_MISMATCH")
    validate_outcome(detail, attempts, report)
    if q is not None and not all(q.values()):
        raise EvaluationError("REPORT_QUALITY_FAILED")
    after = business_snapshot(case_id, transaction_id)
    if after != baseline_state:
        raise EvaluationError("BUSINESS_STATE_CHANGED")
    row.update({"reportEvaluated": True, "aiRequestId": request_id, "executionId": execution_id,
                "requestStatus": latest["reportStatus"], "reportStatus": detail["reportStatus"],
                "reportSource": detail.get("reportSource"), "fallbackTriggerCode": detail.get("fallbackTriggerCode"),
                "failureCode": detail.get("failureCode"), "promptVersion": detail.get("promptVersion"),
                "modelVersion": detail.get("modelVersion"), "attempts": attempts,
                "inputTokens": detail.get("inputTokens"), "outputTokens": detail.get("outputTokens"),
                "estimatedCost": detail.get("estimatedCost"), "costCurrency": detail.get("costCurrency"),
                "qualityChecks": q, "businessStateAndAuditUnchangedAfterAiRequest": True,
                "baselineAuditCount": baseline_state["auditCount"],
                "baselineTransactionStatus": baseline_state["transactionStatus"],
                "baselineCaseStatus": baseline_state["caseStatus"],
                "aiWallLatencyMs": wall_ms, "finishedAt": utc_now(), "status": "VERIFIED"})
    return row


def evaluate(args):
    started = utc_now()
    report = {"schemaVersion": 1, "startedAt": started, "finishedAt": None,
              "fixtureVersion": None, "model": None,
              "generationSettings": {"providerTimeoutSeconds": 45, "maxProviderAttempts": 2,
                                     "maxOutputTokens": 384, "think": False, "temperature": 0,
                                     "format": "json", "settingsSnapshotVersion": "qwen-local-defaults-v1"},
              "repetitions": args.repetitions, "criticalAvailability": None,
              "results": [], "status": "INCOMPLETE", "errorCode": None}
    try:
        fixtures = load_fixtures(args.fixtures)
        report["fixtureVersion"] = fixtures["fixtureVersion"]
        report["criticalAvailability"] = fixtures["criticalAvailability"]
        report["model"] = model_identity(args.model_tag, args.digest, args.quantization)
        prior_ids = set()
        selected = [item for item in fixtures["fixtures"]
                    if args.fixture_id is None or item["id"] == args.fixture_id]
        if not selected:
            raise EvaluationError("FIXTURE_INVALID")
        for item in selected:
            for repetition in range(1, args.repetitions + 1):
                evaluate_one(item, repetition, prior_ids, args.digest, report["results"])
        report["status"] = "COMPLETED"
    except EvaluationError as exc:
        report["errorCode"] = exc.code
    except Exception:
        # Never serialize exception messages: HTTP and Provider failures may contain secrets.
        report["errorCode"] = "UNEXPECTED_FAILURE"
    report["finishedAt"] = utc_now()
    return report


def main():
    parser = argparse.ArgumentParser(description="Run bounded local Qwen evaluation")
    parser.add_argument("--fixtures", default="/opt/local-jwt-fixture/qwen_evaluation_fixtures.json")
    parser.add_argument("--model-tag", required=True)
    parser.add_argument("--digest", required=True)
    parser.add_argument("--quantization", required=True)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--fixture-id", default=None,
                        help="Run one validated fixture, for example a HIGH smoke case")
    args = parser.parse_args()
    if (args.model_tag != "qwen3.5:4b" or not re.fullmatch(r"[0-9a-f]{64}", args.digest)
            or not re.fullmatch(r"[A-Za-z0-9_]{1,64}", args.quantization)
            or not 1 <= args.repetitions <= 10):
        parser.error("invalid bounded model identity or repetition count")
    result = evaluate(args)
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if result["status"] == "COMPLETED" else 1


if __name__ == "__main__":
    sys.exit(main())
