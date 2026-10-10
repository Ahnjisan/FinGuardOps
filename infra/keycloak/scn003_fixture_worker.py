"""Owned Browser Gate worker: one SCN-003 transfer with a real Keycloak token.

Only service secrets arrive on stdin. Only safe UUIDs and outcome codes leave stdout.
"""

import datetime as dt
import json
import sys
import time
import urllib.parse
import urllib.request
import uuid


def post(url, payload, *, token=None, key=None, form=False):
    body = (urllib.parse.urlencode(payload).encode("ascii") if form else
            json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    headers = {"Content-Type": "application/x-www-form-urlencoded" if form else "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers,
                                                      method="POST"), timeout=30) as response:
        if response.status != (200 if form else 201):
            raise RuntimeError("SCN003_STATUS_INVALID")
        return json.load(response)


def stamp(value):
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def create(secrets):
    if not isinstance(secrets, dict) or set(secrets) != {"transaction", "behavior"} or any(
            not isinstance(value, str) or len(value) < 8 for value in secrets.values()):
        raise RuntimeError("SCN003_SECRET_INPUT_INVALID")
    token_url = "http://127.0.0.1:8082/realms/finguardops-local/protocol/openid-connect/token"
    tokens = {}
    for key, client in (("transaction", "finguardops-transaction-ingestor"),
                        ("behavior", "finguardops-behavior-ingestor")):
        tokens[key] = post(token_url, {"grant_type": "client_credentials",
                                       "client_id": client, "client_secret": secrets[key]},
                           form=True)["access_token"]
    suffix = uuid.uuid4().hex[:12]
    customer = "issue382-synthetic-customer-" + suffix
    sender = "issue382-synthetic-sender-" + suffix
    recipient = "issue382-synthetic-risk-" + suffix
    event_id = str(uuid.uuid4())
    event = {"eventId": event_id, "eventType": "BENEFICIARY_REGISTERED",
             "occurredAt": stamp(dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=5)),
             "externalCustomerRef": customer, "accountRef": sender, "beneficiaryRef": recipient}
    accepted_event = post("http://127.0.0.1:8080/api/v1/behavior-events", event,
                          token=tokens["behavior"])
    if accepted_event.get("eventId") != event_id:
        raise RuntimeError("SCN003_EVENT_INVALID")
    time.sleep(2)
    cutoff = stamp(dt.datetime.now(dt.timezone.utc))
    transaction_id = str(uuid.uuid4())
    transaction = {"transactionId": transaction_id, "transactionType": "ACCOUNT_TRANSFER",
                   "amount": "1000", "currencyCode": "KRW", "occurredAt": cutoff,
                   "externalCustomerRef": customer, "senderAccountRef": sender,
                   "recipientAccountRef": recipient, "channel": "MOBILE_BANKING",
                   "deviceRef": "issue382-synthetic-device-" + suffix}
    key = "kc382-" + uuid.uuid4().hex
    accepted = post("http://127.0.0.1:8080/api/v1/transactions", transaction,
                    token=tokens["transaction"], key=key)
    if (accepted.get("transactionId") != transaction_id or
            accepted.get("processingStatus") != "ADDITIONAL_AUTH_REQUIRED" or
            accepted.get("riskLevel") != "HIGH" or
            not isinstance(accepted.get("caseId"), str)):
        raise RuntimeError("SCN003_TRANSACTION_INVALID")
    replay = post("http://127.0.0.1:8080/api/v1/transactions", transaction,
                  token=tokens["transaction"], key=key)
    if {k: v for k, v in replay.items() if k != "traceId"} != {
            k: v for k, v in accepted.items() if k != "traceId"}:
        raise RuntimeError("SCN003_REPLAY_INVALID")
    return {"transactionId": transaction_id, "caseId": accepted["caseId"],
            "detectionResultId": accepted["adoptedDetectionResultId"],
            "evaluationCutoffAt": cutoff, "riskScore": 50,
            "riskLevel": "HIGH", "transactionStatus": "ADDITIONAL_AUTH_REQUIRED"}


if __name__ == "__main__":
    try:
        print(json.dumps(create(json.load(sys.stdin)), separators=(",", ":")))
    except Exception:
        print("SCN003_FIXTURE_WORKER_FAILED", file=sys.stderr)
        sys.exit(1)
