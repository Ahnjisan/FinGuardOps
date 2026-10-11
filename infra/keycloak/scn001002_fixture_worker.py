"""Owned Keycloak Gate worker for one combined SCN-001/002 v4 transfer.

Only service secrets arrive on stdin. Only safe UUIDs and scores leave stdout.
"""

import datetime as dt
import json
import sys
import time
import urllib.parse
import urllib.error
import urllib.request
import uuid


def stamp(value):
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def post(url, payload, *, token=None, key=None, form=False, status=None):
    body = (urllib.parse.urlencode(payload).encode("ascii") if form else
            json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    headers = {"Content-Type": "application/x-www-form-urlencoded" if form else "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    with urllib.request.urlopen(urllib.request(url, data=body, headers=headers,
                                                      method="POST"), timeout=30) as response:
        if response.status != (status if status is not None else (200 if form else 201)):
            raise RuntimeError("SCN001002_STATUS_INVALID")
        return json.load(response)


def event(token, kind, customer, sender, device, recipient, at):
    event_id = str(uuid.uuid4())
    accepted = post("http://127.0.0.1:8080/api/v1/behavior-events", {
        "eventId": event_id, "eventType": kind, "occurredAt": stamp(at),
        "externalCustomerRef": customer, "accountRef": sender,
        "deviceRef": device, "beneficiaryRef": recipient, "transactionId": None,
    }, token=token)
    if accepted.get("eventId") != event_id:
        raise RuntimeError("SCN001002_EVENT_INVALID")
    return event_id


def fact(token, kind, customer, at, **fields):
    fact_id = str(uuid.uuid4())
    body = {
        "factId": fact_id, "factType": kind, "externalCustomerRef": customer,
        "accountRef": None, "deviceRef": None, "beneficiaryRef": None,
        "behaviorEventId": None, "occurredAt": stamp(at),
        "coverageFrom": None, "coverageThrough": None,
        "assertionState": None, "limitScope": None,
        "previousLimit": None, "newLimit": None, "countryCode": None,
    }
    body.update(fields)
    accepted = post("http://127.0.0.1:8080/api/v1/fds-mock-facts", body, token=token)
    if accepted.get("factId") != fact_id:
        raise RuntimeError("SCN001002_FACT_INVALID")


def create(secrets):
    if (not isinstance(secrets, dict) or set(secrets) != {"transaction", "behavior", "suffix"} or
            any(not isinstance(secrets[key], str) or len(secrets[key]) < 8
                for key in ("transaction", "behavior")) or
            not isinstance(secrets["suffix"], str) or len(secrets["suffix"]) != 12 or
            not all(char in "0123456789abcdef" for char in secrets["suffix"])):
        raise RuntimeError("SCN001002_SECRET_INPUT_INVALID")
    token_url = "http://127.0.0.1:8082/realms/finguardops-local/protocol/openid-connect/token"
    tokens = {}
    for key, client in (("transaction", "finguardops-transaction-ingestor"),
                        ("behavior", "finguardops-behavior-ingestor")):
        tokens[key] = post(token_url, {"grant_type": "client_credentials",
                                       "client_id": client, "client_secret": secrets[key]},
                           form=True)["access_token"]
    suffix = secrets["suffix"]
    customer = "issue384-browser-customer-" + suffix
    sender = "issue384-browser-sender-" + suffix
    device = "issue384-browser-new-device-" + suffix
    recipient = "issue384-browser-first-recipient-" + suffix
    now = dt.datetime.now(dt.timezone.utc)
    device_event = event(tokens["behavior"], "DEVICE_REGISTERED", customer, sender,
                         device, None, now - dt.timedelta(minutes=12))
    event(tokens["behavior"], "BENEFICIARY_REGISTERED", customer, sender,
          None, recipient, now - dt.timedelta(minutes=11))
    password_at = now - dt.timedelta(minutes=10)
    limit_at = now - dt.timedelta(minutes=5)
    password_id = event(tokens["behavior"], "PASSWORD_CHANGED", customer, sender,
                        None, None, password_at)
    limit_id = event(tokens["behavior"], "TRANSFER_LIMIT_CHANGED", customer, sender,
                     None, None, limit_at)
    for kind in ("TRANSFER_HISTORY_COMPLETE", "DEVICE_HISTORY_COMPLETE"):
        fact(tokens["behavior"], kind, customer, now,
             coverageFrom="1970-01-01T00:00:00Z",
             coverageThrough=stamp(now + dt.timedelta(minutes=4)),
             assertionState="COMPLETE")
    fact(tokens["behavior"], "DEVICE_TRUST", customer, now - dt.timedelta(minutes=12),
         accountRef=sender, deviceRef=device, behaviorEventId=device_event,
         assertionState="UNVERIFIED")
    fact(tokens["behavior"], "LIMIT_CHANGE", customer, limit_at,
         accountRef=sender, behaviorEventId=limit_id,
         assertionState="SUCCEEDED", limitScope="PER_TRANSFER",
         previousLimit=2000000, newLimit=7000000)
    fact(tokens["behavior"], "IDENTITY_CHECK", customer, password_at,
         accountRef=sender, behaviorEventId=password_id, assertionState="VERIFIED")
    time.sleep(2)
    transaction_id = str(uuid.uuid4())
    transaction = {
        "transactionId": transaction_id, "transactionType": "ACCOUNT_TRANSFER",
        "amount": "1000000", "currencyCode": "KRW",
        "occurredAt": stamp(dt.datetime.now(dt.timezone.utc)),
        "externalCustomerRef": customer, "senderAccountRef": sender,
        "recipientAccountRef": recipient, "channel": "MOBILE_BANKING", "deviceRef": device,
    }
    key = "kc384-" + uuid.uuid4().hex
    accepted = post("http://127.0.0.1:8080/api/v1/transactions", transaction,
                    token=tokens["transaction"], key=key)
    if (accepted.get("transactionId") != transaction_id or
            accepted.get("processingStatus") != "HELD" or
            accepted.get("riskLevel") != "CRITICAL" or
            not isinstance(accepted.get("caseId"), str)):
        raise RuntimeError("SCN001002_TRANSACTION_INVALID")
    replay = post("http://127.0.0.1:8080/api/v1/transactions", transaction,
                  token=tokens["transaction"], key=key)
    if {key: value for key, value in replay.items() if key != "traceId"} != {
            key: value for key, value in accepted.items() if key != "traceId"}:
        raise RuntimeError("SCN001002_REPLAY_INVALID")
    for path in ("/api/v1/cases/" + accepted["caseId"],
                 "/api/v1/cases/" + accepted["caseId"] + "/audit-logs"):
        request = urllib.request.Request("http://127.0.0.1:8080" + path,
                                         headers={"Authorization": "Bearer " + tokens["transaction"]})
        try:
            urllib.request.urlopen(request, timeout=30)
        except urllib.error.HTTPError as error:
            if error.code != 403:
                raise RuntimeError("SCN001002_ROLE_STATUS_INVALID") from None
        else:
            raise RuntimeError("SCN001002_ROLE_BOUNDARY_INVALID")
    return {"transactionId": transaction_id, "caseId": accepted["caseId"],
            "detectionResultId": accepted["adoptedDetectionResultId"],
            "transactionStatus": "HELD"}


if __name__ == "__main__":
    try:
        print(json.dumps(create(json.load(sys.stdin)), separators=(",", ":")))
    except Exception:
        print("SCN001002_FIXTURE_WORKER_FAILED", file=sys.stderr)
        sys.exit(1)
