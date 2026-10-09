#!/usr/bin/env python3
"""Read only comparison of one previously verified Kubernetes E2E chain."""

import argparse
import json
import sys
import uuid

import evaluate_qwen_reports as shared


def verify(transaction_id, case_id, request_id, execution_id):
    for value in (transaction_id, case_id, request_id, execution_id):
        if uuid.UUID(value).version != 4:
            raise ValueError("non-v4 identifier")
    transaction = shared.api("GET", "/api/v1/transactions/" + transaction_id,
                             "user-analyst")["transaction"]
    case = shared.api("GET", "/api/v1/cases/" + case_id, "user-analyst")["case"]
    current = shared.api("GET", "/api/v1/cases/" + case_id + "/ai-reports/current",
                         "user-analyst")
    detail = shared.api("GET", "/api/v1/ai-report-requests/" + request_id,
                        "user-platform-admin")
    report = current.get("currentReport")
    if (transaction.get("processingStatus") != "HELD"
            or case.get("caseStatus") != "IN_REVIEW"
            or detail.get("caseId") != case_id
            or detail.get("executionId") != execution_id
            or detail.get("reportStatus") not in {"COMPLETED", "FALLBACK_COMPLETED"}
            or not isinstance(report, dict)
            or report.get("caseId") != case_id
            or report.get("executionId") != execution_id
            or report.get("initiatingAiRequestId") != request_id
            or report.get("reportStatus") != detail.get("reportStatus")):
        raise shared.EvaluationError("BUSINESS_STATE_CHANGED")
    return {"status": "VERIFIED", "transactionId": transaction_id, "caseId": case_id,
            "aiRequestId": request_id, "executionId": execution_id,
            "reportStatus": detail["reportStatus"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("transaction-id", "case-id", "request-id", "execution-id"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.transaction_id, args.case_id, args.request_id, args.execution_id)
    except Exception:
        result = {"status": "FAILED", "errorCode": "RECOVERY_STATE_MISMATCH"}
    print(json.dumps(result, separators=(",", ":")))
    return 0 if result["status"] == "VERIFIED" else 1


if __name__ == "__main__":
    sys.exit(main())
