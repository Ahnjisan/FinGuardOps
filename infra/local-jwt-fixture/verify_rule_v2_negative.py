#!/usr/bin/env python3
"""Reject invalid JWTs without printing the token or response body."""

import json
import sys
import urllib.error
import urllib.request

from fixture import socket_request


def reject(variant):
    token = socket_request({"command": "mint", "identity": "user-analyst",
                            "variant": variant}, 5)["token"]
    request = urllib.request.Request("http://127.0.0.1:8080/api/v1/cases",
        headers={"Authorization": "Bearer " + token})
    try:
        urllib.request.urlopen(request, timeout=10).close()
    except urllib.error.HTTPError as exc:
        return exc.code == 401
    return False


def main():
    try:
        passed = all(reject(variant) for variant in ("expired", "wrong-issuer", "wrong-audience"))
    except Exception:
        passed = False
    print(json.dumps({"status": "VERIFIED" if passed else "FAILED",
                      "variants": 3 if passed else 0}, separators=(",", ":")))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
