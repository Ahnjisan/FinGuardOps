"""Bounded, PII-free HTTP load measurement for an isolated staging backend."""

import argparse
import concurrent.futures
import json
import math
import os
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone


def percentile(samples, fraction):
    ordered = sorted(samples)
    if not ordered:
        return None
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def post_once(base_url, token, timeout):
    transaction_id = str(uuid.uuid4())
    payload = {
        "transactionId": transaction_id,
        "transactionType": "ACCOUNT_TRANSFER",
        "amount": "125000",
        "currencyCode": "KRW",
        "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "externalCustomerRef": "synthetic_customer",
        "senderAccountRef": "synthetic_sender",
        "recipientAccountRef": "synthetic_recipient",
        "channel": "MOBILE_BANKING",
        "deviceRef": "synthetic_device",
    }
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/v1/transactions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Idempotency-Key": "synthetic-" + transaction_id,
            "Authorization": "Bearer " + token,
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        error.read()
        status = error.code
    except (urllib.error.URLError, TimeoutError):
        status = 0
    return status, (time.perf_counter() - started) * 1000


def run_batch(base_url, token, concurrency, count, timeout):
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        samples = list(pool.map(
            lambda _: post_once(base_url, token, timeout), range(count)
        ))
    elapsed = time.perf_counter() - started
    successful = [duration for status, duration in samples if status == 201]
    statuses = {}
    for status, _ in samples:
        statuses[str(status)] = statuses.get(str(status), 0) + 1
    return {
        "concurrency": concurrency,
        "count": count,
        "elapsedSeconds": round(elapsed, 3),
        "completedTps": round(count / elapsed, 3),
        "successTps": round(len(successful) / elapsed, 3),
        "statuses": statuses,
        "successLatencyMs": {
            "p50": percentile(successful, 0.50),
            "p95": percentile(successful, 0.95),
            "p99": percentile(successful, 0.99),
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--concurrency", type=int, nargs="+", default=[1, 4, 8])
    parser.add_argument("--warmup", type=int, default=100)
    parser.add_argument("--requests-per-level", type=int, default=1000)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--timeout-seconds", type=float, default=30)
    args = parser.parse_args()
    token = os.environ.get("FINGUARDOPS_LOAD_BEARER_TOKEN")
    if not token:
        parser.error("FINGUARDOPS_LOAD_BEARER_TOKEN is required")
    if (args.warmup < 0 or args.requests_per_level < 1000
            or args.repeats < 1 or any(value < 1 or value > 8
                                  for value in args.concurrency)):
        parser.error("invalid bounded load parameters")
    if args.warmup:
        run_batch(args.base_url, token, 1, args.warmup, args.timeout_seconds)
    for concurrency in args.concurrency:
        for repeat in range(1, args.repeats + 1):
            result = run_batch(args.base_url, token, concurrency,
                               args.requests_per_level, args.timeout_seconds)
            result["repeat"] = repeat
            print(json.dumps(result, separators=(",", ":"), sort_keys=True),
                  flush=True)
            if result["statuses"].get("201", 0) < args.requests_per_level * 0.95:
                raise SystemExit("stopped: success ratio below 95%")


if __name__ == "__main__":
    main()
