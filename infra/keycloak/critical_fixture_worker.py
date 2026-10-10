"""Executed with `python -c` inside the owned external-risk-mock container.

The two service secrets arrive only on stdin. No prompt, token, account or
customer reference is written to stdout or stderr.
"""

import datetime as dt
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def request(url, payload, *, token=None, key=None, form=False):
    body = (
        urllib.parse.urlencode(payload).encode("ascii")
        if form
        else json.dumps(payload, separators=(",", ":")).encode("utf-8")
    )
    headers = {
        "Content-Type": "application/x-www-form-urlencoded"
        if form
        else "application/json"
    }
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    with urllib.request.urlopen(
        urllib.request.Request(url, data=body, headers=headers, method="POST"),
        timeout=30,
    ) as response:
        if response.status != (200 if form else 201):
            raise RuntimeError("CRITICAL_HTTP_STATUS_INVALID")
        return json.load(response)


def stamp(value):
    return value.isoformat().replace("+00:00", "Z")


def failed_request(url, payload, *, token, key):
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + token,
        "Idempotency-Key": key,
    }
    try:
        urllib.request.urlopen(
            urllib.request.Request(url, data=body, headers=headers, method="POST"),
            timeout=30,
        )
    except urllib.error.HTTPError as error:
        if (
            error.code != 503
            or json.load(error).get("code") != "DEPENDENCY_UNAVAILABLE"
        ):
            raise RuntimeError("CRITICAL_ML_FAILURE_RESPONSE_INVALID") from None
        return
    raise RuntimeError("CRITICAL_ML_FAILURE_RESPONSE_INVALID")


def create(secrets):
    if (
        not isinstance(secrets, dict)
        or set(secrets) != {"transaction", "behavior"}
        or any(
            not isinstance(value, str) or len(value) < 8 for value in secrets.values()
        )
    ):
        raise RuntimeError("CRITICAL_SECRET_INPUT_INVALID")
    # This service shares Backend's network namespace, including Keycloak's
    # loopback listener. No Backend or Keycloak host port is opened for intake.
    token_url = (
        "http://127.0.0.1:8082/realms/finguardops-local/protocol/openid-connect/token"
    )
    transaction_token = request(
        token_url,
        {
            "grant_type": "client_credentials",
            "client_id": "finguardops-transaction-ingestor",
            "client_secret": secrets["transaction"],
        },
        form=True,
    )["access_token"]
    behavior_token = request(
        token_url,
        {
            "grant_type": "client_credentials",
            "client_id": "finguardops-behavior-ingestor",
            "client_secret": secrets["behavior"],
        },
        form=True,
    )["access_token"]
    suffix = uuid.uuid4().hex[:12]
    customer, sender, recipient, device = (
        "kc369-customer-" + suffix,
        "kc369-sender-" + suffix,
        "kc369-recipient-" + suffix,
        "kc369-device-" + suffix,
    )
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    for kind, references in (
        ("DEVICE_REGISTERED", {"deviceRef": device}),
        ("PASSWORD_CHANGED", {}),
        ("TRANSFER_LIMIT_CHANGED", {"accountRef": sender}),
        ("BENEFICIARY_REGISTERED", {"accountRef": sender, "beneficiaryRef": recipient}),
    ):
        for number in range(3):
            event_id = str(uuid.uuid4())
            accepted = request(
                "http://127.0.0.1:8080/api/v1/behavior-events",
                {
                    "eventId": event_id,
                    "eventType": kind,
                    "occurredAt": stamp(now - dt.timedelta(seconds=240 - number)),
                    "externalCustomerRef": customer,
                    **references,
                },
                token=behavior_token,
            )
            if accepted.get("eventId") != event_id:
                raise RuntimeError("CRITICAL_EVENT_IDENTITY_INVALID")
    # Keep both the persisted event creation time and the provider as-of at or
    # before the transaction cutoff; a future cutoff is rejected by External Risk.
    time.sleep(1.2)
    normal_cutoff = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    transaction_id = str(uuid.uuid4())
    transaction_body = {
        "transactionId": transaction_id,
        "transactionType": "ACCOUNT_TRANSFER",
        "amount": "12000000",
        "currencyCode": "KRW",
        "occurredAt": stamp(normal_cutoff),
        "externalCustomerRef": customer,
        "senderAccountRef": sender,
        "recipientAccountRef": recipient,
        "deviceRef": device,
        "channel": "MOBILE_BANKING",
    }
    key = "kc380-" + uuid.uuid4().hex
    accepted = request(
        "http://127.0.0.1:8080/api/v1/transactions",
        transaction_body,
        token=transaction_token,
        key=key,
    )
    if (
        accepted.get("transactionId") != transaction_id
        or accepted.get("processingStatus") != "HELD"
        or accepted.get("riskLevel") != "CRITICAL"
        or accepted.get("riskResponseOutcome") != "HELD"
        or not isinstance(accepted.get("caseId"), str)
    ):
        raise RuntimeError("CRITICAL_TRANSACTION_RESPONSE_INVALID")
    replay = request(
        "http://127.0.0.1:8080/api/v1/transactions",
        transaction_body,
        token=transaction_token,
        key=key,
    )
    if {k: v for k, v in replay.items() if k != "traceId"} != {
        k: v for k, v in accepted.items() if k != "traceId"
    }:
        raise RuntimeError("CRITICAL_REPLAY_RESPONSE_INVALID")

    # Separate customer, so the failure cannot change the adopted result above.
    failure_customer = "kc380-failure-customer-" + suffix
    failure_sender = "kc380-failure-sender-" + suffix
    event_time = dt.datetime.now(dt.timezone.utc).replace(microsecond=0) - dt.timedelta(
        minutes=1
    )
    for _ in range(1001):
        event_id = str(uuid.uuid4())
        observed = request(
            "http://127.0.0.1:8080/api/v1/behavior-events",
            {
                "eventId": event_id,
                "eventType": "PASSWORD_CHANGED",
                "occurredAt": stamp(event_time),
                "externalCustomerRef": failure_customer,
                "accountRef": failure_sender,
            },
            token=behavior_token,
        )
        if observed.get("eventId") != event_id:
            raise RuntimeError("CRITICAL_ML_LIMIT_EVENT_INVALID")
    failure_id = str(uuid.uuid4())
    time.sleep(1.2)
    failed_request(
        "http://127.0.0.1:8080/api/v1/transactions",
        {
            "transactionId": failure_id,
            "transactionType": "ACCOUNT_TRANSFER",
            "amount": "12000000",
            "currencyCode": "KRW",
            "occurredAt": stamp(dt.datetime.now(dt.timezone.utc).replace(microsecond=0)),
            "externalCustomerRef": failure_customer,
            "senderAccountRef": failure_sender,
            "recipientAccountRef": recipient,
            "channel": "MOBILE_BANKING",
        },
        token=transaction_token,
        key="kc380-failure-" + uuid.uuid4().hex,
    )
    return {
        "transactionId": transaction_id,
        "caseId": accepted["caseId"],
        "detectionResultId": accepted["adoptedDetectionResultId"],
        "failedTransactionId": failure_id,
        "riskScore": 100,
        "riskLevel": "CRITICAL",
        "transactionStatus": "HELD",
    }


if __name__ == "__main__":
    try:
        result = create(json.load(sys.stdin))
        print(json.dumps(result, separators=(",", ":")))
    except Exception:
        print("CRITICAL_FIXTURE_WORKER_FAILED", file=sys.stderr)
        sys.exit(1)
