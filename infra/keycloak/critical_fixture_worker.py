"""Executed with `python -c` inside the owned external-risk-mock container.

The two service secrets arrive only on stdin. No prompt, token, account or
customer reference is written to stdout or stderr.
"""

import datetime as dt
import json
import sys
import urllib.parse
import urllib.request
import uuid


def request(url, payload, *, token=None, key=None, form=False):
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
            raise RuntimeError("CRITICAL_HTTP_STATUS_INVALID")
        return json.load(response)


def stamp(value):
    return value.isoformat().replace("+00:00", "Z")


def create(secrets):
    if (not isinstance(secrets, dict) or set(secrets) != {"transaction", "behavior"}
            or any(not isinstance(value, str) or len(value) < 8 for value in secrets.values())):
        raise RuntimeError("CRITICAL_SECRET_INPUT_INVALID")
    # This service shares Backend's network namespace, including Keycloak's
    # loopback listener. No Backend or Keycloak host port is opened for intake.
    token_url = "http://127.0.0.1:8082/realms/finguardops-local/protocol/openid-connect/token"
    transaction_token = request(token_url, {"grant_type": "client_credentials",
        "client_id": "finguardops-transaction-ingestor",
        "client_secret": secrets["transaction"]}, form=True)["access_token"]
    behavior_token = request(token_url, {"grant_type": "client_credentials",
        "client_id": "finguardops-behavior-ingestor",
        "client_secret": secrets["behavior"]}, form=True)["access_token"]
    suffix = uuid.uuid4().hex[:12]
    customer, sender, recipient, device = (
        "kc369-customer-" + suffix, "kc369-sender-" + suffix,
        "kc369-recipient-" + suffix, "kc369-device-" + suffix)
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    events = (
        ("DEVICE_REGISTERED", -240, {"deviceRef": device}),
        ("PASSWORD_CHANGED", -180, {}),
        ("TRANSFER_LIMIT_CHANGED", -120, {"accountRef": sender}),
        ("BENEFICIARY_REGISTERED", -60, {"accountRef": sender, "beneficiaryRef": recipient}),
    )
    for kind, offset, references in events:
        event_id = str(uuid.uuid4())
        accepted = request("http://127.0.0.1:8080/api/v1/behavior-events", {
            "eventId": event_id, "eventType": kind,
            "occurredAt": stamp(now + dt.timedelta(seconds=offset)),
            "externalCustomerRef": customer, **references}, token=behavior_token)
        if accepted.get("eventId") != event_id:
            raise RuntimeError("CRITICAL_EVENT_IDENTITY_INVALID")
    transaction_id = str(uuid.uuid4())
    accepted = request("http://127.0.0.1:8080/api/v1/transactions", {
        "transactionId": transaction_id, "transactionType": "ACCOUNT_TRANSFER",
        "amount": "12000000", "currencyCode": "KRW", "occurredAt": stamp(now),
        "externalCustomerRef": customer, "senderAccountRef": sender,
        "recipientAccountRef": recipient, "deviceRef": device,
        "channel": "MOBILE_BANKING"}, token=transaction_token,
        key="kc369-" + uuid.uuid4().hex)
    if (accepted.get("transactionId") != transaction_id
            or accepted.get("processingStatus") != "HELD"
            or accepted.get("riskLevel") != "CRITICAL"
            or accepted.get("riskResponseOutcome") != "HELD"
            or not isinstance(accepted.get("caseId"), str)):
        raise RuntimeError("CRITICAL_TRANSACTION_RESPONSE_INVALID")
    return {"transactionId": transaction_id, "caseId": accepted["caseId"],
            "riskScore": 85, "riskLevel": "CRITICAL", "transactionStatus": "HELD"}


if __name__ == "__main__":
    try:
        result = create(json.load(sys.stdin))
        print(json.dumps(result, separators=(",", ":")))
    except Exception:
        print("CRITICAL_FIXTURE_WORKER_FAILED", file=sys.stderr)
        sys.exit(1)
