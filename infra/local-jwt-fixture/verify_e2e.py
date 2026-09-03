#!/usr/bin/env python3
"""Bounded local/manual verifier for the FinGuardOps JWT Compose overlay.

The verifier intentionally uses only the Python standard library.  JWTs are
captured from the fixture CLI and are passed to HTTP probes through stdin.
They are never placed in argv, environment variables, files, or diagnostics.
"""

import argparse
import base64
import copy
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


EXPECTED_IMAGE = "python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7"
ISSUER = "https://local-jwt.fixture.finguardops.invalid"
AUDIENCE = ["finguardops-backend-api"]
JWKS_URL = "http://127.0.0.1:8002/oauth2/jwks"
PROJECT_RE = re.compile(r"^finguardops-jwt-e2e-[a-z0-9][a-z0-9-]{5,40}$")
JWT_RE = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
EXPECTED_SERVICES = {
    "postgresql", "ai-service", "external-risk-mock", "backend", "prometheus",
    "grafana", "alertmanager", "alertmanager-webhook", "local-jwt-fixture",
}
EXPECTED_NETWORKS = {"application", "observability", "prometheus-ui", "grafana-ui"}
EXPECTED_VOLUMES = {"prometheus-data", "alertmanager-data", "grafana-data"}
PROJECT_RESOURCE_KINDS = ("container", "network", "volume")
IDENTITIES = {
    "service-transaction-ingestor": ("9d0edbde-f833-43e2-822a-43a1c38d82ec", "SERVICE", ["TRANSACTION_INGESTOR"]),
    "service-behavior-ingestor": ("a0dc7e4b-1260-4888-9e14-54867c9f2293", "SERVICE", ["BEHAVIOR_INGESTOR"]),
    "user-viewer": ("3d005f9e-f48e-45e9-98f1-5f9c407d2021", "USER", ["FDS_VIEWER"]),
    "user-analyst": ("8fbcd138-76f7-44a8-85f1-3afcf118f1c6", "USER", ["FDS_ANALYST"]),
    "user-approver": ("f5b2501d-0c30-462b-b699-8cbb7aa6f3f2", "USER", ["FDS_APPROVER"]),
    "user-analyst-approver": ("35b78471-c387-48a8-af51-3490c8718216", "USER", ["FDS_ANALYST", "FDS_APPROVER"]),
    "user-platform-admin": ("edaa43d7-c04f-4195-a7f7-82ee7f1a0de1", "USER", ["PLATFORM_ADMIN"]),
}
ALLOWED_ROOTS = {"http://127.0.0.1:8080", "http://127.0.0.1:8081", "http://127.0.0.1:8002",
                 "http://prometheus:9090", "http://grafana:3000", "http://alertmanager:9093"}


class VerificationError(RuntimeError):
    pass


def scrub(value):
    return JWT_RE.sub("<redacted-jwt>", value or "")[:2000]


def run(argv, *, timeout, input_bytes=None, sensitive=False, cwd=None, env=None):
    if timeout <= 0:
        raise VerificationError("subprocess timeout must be positive")
    merged_env = os.environ.copy()
    merged_env.update({"MSYS_NO_PATHCONV": "1", "MSYS2_ARG_CONV_EXCL": "*"})
    if env:
        merged_env.update(env)
    try:
        result = subprocess.run(
            argv, input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=timeout, check=False, shell=False, cwd=cwd, env=merged_env,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise VerificationError("subprocess failed: %s" % argv[0]) from exc
    if result.returncode != 0:
        detail = "" if sensitive else scrub(result.stderr.decode("utf-8", "replace"))
        raise VerificationError("command failed (%d): %s %s" % (result.returncode, argv[0], detail))
    return result.stdout


class Context:
    def __init__(self, repo, project, timeout, deadline):
        self.repo = repo.resolve()
        self.project = project
        self.timeout = timeout
        self.end = time.monotonic() + deadline
        self.base = self.repo / "infra" / "compose.yml"
        self.overlay = self.repo / "infra" / "compose.local-jwt-e2e.yml"
        self.compose = ["docker", "compose", "-p", project, "-f", str(self.base), "-f", str(self.overlay)]
        self.env = {
            "POSTGRES_PASSWORD": "local-e2e-placeholder-not-production",
            "GRAFANA_ADMIN_USER": "local-e2e-admin",
            "GRAFANA_ADMIN_PASSWORD": "local-e2e-placeholder-not-production",
        }

    def remaining(self):
        value = self.end - time.monotonic()
        if value <= 0:
            raise VerificationError("overall deadline exceeded")
        return value

    def compose_run(self, args, *, input_bytes=None, sensitive=False, timeout=None):
        effective_timeout = min(timeout, self.remaining()) if timeout is not None else min(self.timeout, self.remaining())
        return run(self.compose + list(args), timeout=effective_timeout, input_bytes=input_bytes,
                   sensitive=sensitive, cwd=str(self.repo), env=self.env)

    def mint(self, identity, variant="normal"):
        output = self.compose_run([
            "exec", "-T", "local-jwt-fixture", "python",
            "/opt/local-jwt-fixture/fixture.py", "machine", "mint", identity,
            "--variant", variant, "--timeout", str(min(5.0, self.remaining())),
        ], sensitive=True)
        token = output.decode("ascii", "strict").strip()
        if token.count(".") != 2 or not JWT_RE.fullmatch(token):
            raise VerificationError("machine mint returned an invalid envelope")
        return token

    def control(self, command, value=None):
        args = ["exec", "-T", "local-jwt-fixture", "python",
                "/opt/local-jwt-fixture/fixture.py", "control", command]
        if value is not None:
            args.append(value)
        self.compose_run(args)

    def probe(self, method, url, expected, token=None, body=None, headers=None):
        validate_url(url)
        args = ["exec", "-T", "local-jwt-fixture", "python",
                "/opt/local-jwt-fixture/verify_e2e.py", "_probe", "--method", method,
                "--url", url, "--expected", str(expected), "--timeout", str(min(10.0, self.remaining()))]
        if body is not None:
            args += ["--body-b64", base64.urlsafe_b64encode(json.dumps(body, separators=(",", ":")).encode()).decode()]
        for key, value in (headers or {}).items():
            if key.lower() == "authorization":
                raise VerificationError("Authorization must not be passed as an argument")
            args += ["--header", "%s:%s" % (key, value)]
        stdin = ((token or "") + "\n").encode("ascii")
        output = self.compose_run(args, input_bytes=stdin, sensitive=True)
        try:
            return json.loads(output.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise VerificationError("HTTP probe returned an invalid result") from exc

    def probe_from(self, service, url, expected, token):
        validate_url(url)
        if service != "external-risk-mock":
            raise VerificationError("unapproved alternate probe service")
        script = ("import sys,urllib.error,urllib.request;"
                  "t=sys.stdin.readline(16384).strip();"
                  "r=urllib.request.Request(sys.argv[1],headers={'Authorization':'Bearer '+t});"
                  "s=0;"
                  "\ntry:\n with urllib.request.urlopen(r,timeout=8) as x: s=x.status; x.read(262145)"
                  "\nexcept urllib.error.HTTPError as e: s=e.code; e.read(262145)"
                  "\nassert s==int(sys.argv[2]),'unexpected HTTP status'\nprint(s)")
        self.compose_run(["exec", "-T", service, "python", "-c", script, url, str(expected)],
                         input_bytes=(token + "\n").encode("ascii"), sensitive=True)

    def sql_scalar(self, query):
        output = self.compose_run(["exec", "-T", "postgresql", "psql", "-U", "finguardops",
                                   "-d", "finguardops", "-tAc", query])
        return output.decode("utf-8", "strict").strip()


def validate_url(url):
    parsed = urllib.parse.urlsplit(url)
    root = "%s://%s" % (parsed.scheme, parsed.netloc)
    if root not in ALLOWED_ROOTS or parsed.username or parsed.password or parsed.fragment:
        raise VerificationError("URL is outside the approved local targets")
    if not parsed.path.startswith("/") or ".." in parsed.path or len(url) > 512:
        raise VerificationError("URL path is invalid")


def decode_token(token):
    try:
        parts = token.split(".")
        return [json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))) for part in parts[:2]]
    except Exception as exc:
        raise VerificationError("JWT shape is invalid") from exc


def merged_config(ctx):
    output = ctx.compose_run(["config", "--format", "json"], timeout=min(30, ctx.remaining()))
    try:
        return json.loads(output)
    except json.JSONDecodeError as exc:
        raise VerificationError("Compose config was not JSON") from exc


def validate_config(config, *, base_text=""):
    services = config.get("services", {})
    if set(services) != EXPECTED_SERVICES:
        raise VerificationError("merged service set/count changed")
    if set(config.get("networks", {})) != EXPECTED_NETWORKS:
        raise VerificationError("merged network set/count changed")
    if set(config.get("volumes", {})) != EXPECTED_VOLUMES:
        raise VerificationError("merged named-volume set/count changed")
    fixture = services["local-jwt-fixture"]
    if fixture.get("image") != EXPECTED_IMAGE or fixture.get("network_mode") != "service:backend":
        raise VerificationError("fixture image or namespace sharing changed")
    if fixture.get("ports") or fixture.get("expose") or fixture.get("networks"):
        raise VerificationError("fixture has a forbidden network surface")
    if str(fixture.get("user")) != "10001:10001" or fixture.get("read_only") is not True:
        raise VerificationError("fixture privilege/filesystem contract changed")
    if "ALL" not in fixture.get("cap_drop", []):
        raise VerificationError("fixture capabilities are not dropped")
    if "no-new-privileges:true" not in fixture.get("security_opt", []):
        raise VerificationError("fixture no-new-privileges is absent")
    mounts = fixture.get("volumes", [])
    if len(mounts) != 1 or mounts[0].get("type") != "bind" or mounts[0].get("read_only") is not True:
        raise VerificationError("fixture source must be one read-only bind")
    if not any("/run/local-jwt" in str(item) and "nosuid" in str(item) and "nodev" in str(item)
               and "noexec" in str(item) and "size=4m" in str(item) for item in fixture.get("tmpfs", [])):
        raise VerificationError("fixture tmpfs contract changed")
    depends = fixture.get("depends_on", {}).get("backend", {})
    if depends.get("condition") != "service_healthy" or depends.get("restart") is not True:
        raise VerificationError("safe sidecar startup relation changed")
    backend = services["backend"]
    env = backend.get("environment", {})
    expected_env = {
        "FINGUARDOPS_SECURITY_ISSUER": ISSUER,
        "FINGUARDOPS_SECURITY_JWK_SET_URI": JWKS_URL,
        "FINGUARDOPS_SECURITY_INSECURE_LOOPBACK_JWK_ALLOWED": "true",
    }
    for key, value in expected_env.items():
        if str(env.get(key)).lower() != value.lower():
            raise VerificationError("backend local JWT override changed")
    profiles = str(env.get("SPRING_PROFILES_ACTIVE", "")).lower().split(",")
    if "prod" in profiles or "production" in profiles:
        raise VerificationError("production profile leaked into local overlay")
    if "INSECURE_LOOPBACK_JWK_ALLOWED" in base_text:
        raise VerificationError("insecure loopback setting leaked into base Compose")
    ports = []
    for service in services.values():
        for port in service.get("ports", []) or []:
            ports.append((str(port.get("host_ip")), int(port.get("published")), int(port.get("target"))))
    if sorted(ports) != sorted([("127.0.0.1", 9090, 9090), ("127.0.0.1", 3000, 3000)]):
        raise VerificationError("host publish contract changed")


def validate_fixture_source(source):
    required = {
        "identity-allowlist": "identity not in IDENTITIES",
        "variant-allowlist": "variant not in NEGATIVE_VARIANTS",
        "lifetime": "TOKEN_LIFETIME_SECONDS = 840",
        "kid-nonblank": "if not kid.strip():",
        "kid-required": 'header = {"alg": "RS256", "kid": signing_key.kid, "typ": "JWT"}',
        "algorithm": '"alg": "RS256"',
        "audience": 'AUDIENCE = ["finguardops-backend-api"]',
        "issuer": 'ISSUER = "https://local-jwt.fixture.finguardops.invalid"',
        "principal-type": '"principal_type": identity.principal_type',
        "rotation-publish": "self.published.append(key)",
        "normal-jwks": 'return {"keys": [dict(item.jwk) for item in self.published]}',
        "no-token-log": "log_message(self, _format, *_args)",
    }
    for label, marker in required.items():
        if marker not in source:
            raise VerificationError("fixture source contract changed: %s" % label)
    forbidden = ["shell=True", "pip install", "apt-get", '"jku"', '"x5u"', '"scope"', '"authorities"']
    for marker in forbidden:
        if marker in source:
            raise VerificationError("forbidden fixture marker: %s" % marker)


def static_checks(ctx):
    validate_config(merged_config(ctx), base_text=ctx.base.read_text(encoding="utf-8"))
    fixture_text = (ctx.repo / "infra/local-jwt-fixture/fixture.py").read_text(encoding="utf-8")
    validate_fixture_source(fixture_text)
    if ".github/workflows" in (ctx.overlay.read_text(encoding="utf-8")):
        raise VerificationError("workflow reference is forbidden")
    print("static: PASS services=9 networks=4 named_volumes=3 host_publishes=2")


def token_claim_checks(ctx):
    now = int(time.time())
    for name, expected in IDENTITIES.items():
        token = ctx.mint(name)
        header, claims = decode_token(token)
        if set(header) != {"alg", "kid", "typ"} or header["alg"] != "RS256" or not header["kid"]:
            raise VerificationError("JWT header contract failed for %s" % name)
        sub, principal_type, roles = expected
        exact = {"iss": ISSUER, "aud": AUDIENCE, "sub": sub,
                 "principal_type": principal_type, "roles": roles}
        if any(claims.get(key) != value for key, value in exact.items()):
            raise VerificationError("JWT claim contract failed for %s" % name)
        if set(claims) != {"iss", "aud", "sub", "principal_type", "roles", "iat", "nbf", "exp"}:
            raise VerificationError("JWT contains an unapproved claim")
        if claims["exp"] - claims["iat"] > 900 or claims["exp"] <= now:
            raise VerificationError("JWT lifetime contract failed")
    print("token-claim: PASS identities=7")


def runtime_checks(ctx):
    ctx.probe("GET", "http://127.0.0.1:8080/api/health", 200)
    ctx.probe("GET", "http://127.0.0.1:8080/api/health", 401, token="invalid-bearer")
    ctx.probe("GET", "http://127.0.0.1:8080/actuator/health", 404)
    ctx.probe("GET", "http://127.0.0.1:8080/actuator/prometheus", 404)
    ctx.probe("GET", "http://127.0.0.1:8081/actuator/health", 200)
    ctx.probe("GET", "http://127.0.0.1:8081/actuator/prometheus", 200)
    print("runtime: PASS public=2 application_actuator=2 management=2")


def transaction_payload(transaction_id, amount="10000"):
    occurred = dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")
    return {"transactionId": transaction_id, "transactionType": "ACCOUNT_TRANSFER", "amount": amount,
            "currencyCode": "KRW", "occurredAt": occurred, "externalCustomerRef": "local-jwt-e2e-customer",
            "senderAccountRef": "local-jwt-e2e-sender", "recipientAccountRef": "local-jwt-e2e-recipient",
            "channel": "MOBILE_BANKING", "deviceRef": "local-jwt-e2e-device"}


def security_matrix_checks(ctx):
    tx_token = ctx.mint("service-transaction-ingestor")
    behavior_token = ctx.mint("service-behavior-ingestor")
    viewer = ctx.mint("user-viewer")
    analyst = ctx.mint("user-analyst")
    approver = ctx.mint("user-approver")
    combined = ctx.mint("user-analyst-approver")
    admin = ctx.mint("user-platform-admin")
    event_base = {"externalCustomerRef": "local-jwt-e2e-customer", "transactionId": None}
    event_specs = [
        ("DEVICE_REGISTERED", None, "local-jwt-e2e-device", None, -4),
        ("PASSWORD_CHANGED", "local-jwt-e2e-sender", None, None, -3),
        ("TRANSFER_LIMIT_CHANGED", "local-jwt-e2e-sender", None, None, -2),
        ("BENEFICIARY_REGISTERED", "local-jwt-e2e-sender", None, "local-jwt-e2e-recipient", -1),
    ]
    for event_type, account, device, beneficiary, offset in event_specs:
        event = dict(event_base)
        event.update({"eventId": str(uuid.uuid4()), "eventType": event_type,
                      "occurredAt": (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=offset)).isoformat().replace("+00:00", "Z"),
                      "accountRef": account, "deviceRef": device, "beneficiaryRef": beneficiary})
        ctx.probe("POST", "http://127.0.0.1:8080/api/v1/behavior-events", 201, behavior_token, event,
                  {"Content-Type": "application/json"})
    tx_id = str(uuid.uuid4())
    payload = transaction_payload(tx_id, "20000000")
    key = "jwt-e2e-" + uuid.uuid4().hex
    first = ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", 201, tx_token, payload,
                      {"Content-Type": "application/json", "Idempotency-Key": key})
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", 201, tx_token, payload,
              {"Content-Type": "application/json", "Idempotency-Key": key})
    conflict = dict(payload); conflict["amount"] = "20000001"
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", 409, tx_token, conflict,
              {"Content-Type": "application/json", "Idempotency-Key": key})
    event = {"eventId": str(uuid.uuid4()), "eventType": "LOGIN", "occurredAt": payload["occurredAt"],
             "externalCustomerRef": payload["externalCustomerRef"], "accountRef": None,
             "deviceRef": "local-jwt-e2e-device", "transactionId": None, "beneficiaryRef": None}
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/behavior-events", 201, behavior_token, event,
              {"Content-Type": "application/json"})
    audit_before_denials = int(ctx.sql_scalar("select count(*) from audit_log"))
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", 403, behavior_token, transaction_payload(str(uuid.uuid4())),
              {"Content-Type": "application/json", "Idempotency-Key": "cross-" + uuid.uuid4().hex})
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/behavior-events", 403, tx_token, event,
              {"Content-Type": "application/json"})
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 403, tx_token)
    ctx.probe("PATCH", "http://127.0.0.1:8080/api/v1/cases/%s/status" % str(uuid.uuid4()), 403,
              tx_token, {"targetStatus": "IN_REVIEW", "assigneeRef": IDENTITIES["user-analyst"][0],
                         "reasonCode": "CASE_REVIEW_STARTED", "expectedVersion": 0},
              {"Content-Type": "application/json"})
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions", 200, viewer)
    missing_case = str(uuid.uuid4())
    status_body = {"targetStatus": "IN_REVIEW", "assigneeRef": IDENTITIES["user-analyst"][0],
                   "reasonCode": "CASE_REVIEW_STARTED", "expectedVersion": 0}
    resolution = {"finalDisposition": "NORMAL", "reasonCode": "CASE_RESOLUTION_COMPLETED", "expectedVersion": 0}
    note = {"content": "local JWT E2E note", "expectedVersion": 0}
    for token, path, method, body, expected in [
        (viewer, "status", "PATCH", status_body, 403),
        (analyst, "resolution", "POST", resolution, 403),
        (approver, "status", "PATCH", status_body, 403),
        (approver, "notes", "POST", note, 403),
        (admin, "status", "PATCH", status_body, 403),
        (analyst, "status", "PATCH", status_body, 404),
        (approver, "resolution", "POST", resolution, 404),
        (combined, "status", "PATCH", status_body, 404),
        (combined, "resolution", "POST", resolution, 404),
    ]:
        ctx.probe(method, "http://127.0.0.1:8080/api/v1/cases/%s/%s" % (missing_case, path), expected,
                  token, body, {"Content-Type": "application/json"})
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions", 403, admin)
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", 403, admin,
              transaction_payload(str(uuid.uuid4())),
              {"Content-Type": "application/json", "Idempotency-Key": "admin-" + uuid.uuid4().hex})
    for variant in ("expired", "wrong-audience", "wrong-issuer", "wrong-principal-type"):
        bad = ctx.mint("service-transaction-ingestor", variant)
        ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 401, bad)
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 401)
    if int(ctx.sql_scalar("select count(*) from audit_log")) != audit_before_denials:
        raise VerificationError("denied authentication/authorization request created an AuditLog")
    case_id = first.get("body", {}).get("caseId")
    if not case_id:
        raise VerificationError("high-risk authenticated transaction did not create a case")
    _exercise_case_actor(ctx, case_id, analyst, approver)
    print("security-matrix: PASS behavior_intake=5 transaction=3 rbac_probes=16 invalid_tokens=5 user_writes=4")


def _exercise_case_actor(ctx, case_id, analyst, approver):
    case_envelope = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases/%s" % case_id, 200, analyst)["body"]
    case = case_envelope.get("case", {})
    if "concurrencyVersion" not in case:
        raise VerificationError("case detail response contract is missing concurrencyVersion")
    status = ctx.probe("PATCH", "http://127.0.0.1:8080/api/v1/cases/%s/status" % case_id, 200, analyst,
                       {"targetStatus": "IN_REVIEW", "assigneeRef": IDENTITIES["user-analyst"][0],
                        "reasonCode": "CASE_REVIEW_STARTED", "expectedVersion": case["concurrencyVersion"]},
                       {"Content-Type": "application/json"})["body"]
    assignee = ctx.probe("PATCH", "http://127.0.0.1:8080/api/v1/cases/%s/assignee" % case_id, 200, analyst,
                         {"assigneeRef": "20000000-0000-4000-9000-000000000012",
                          "reasonCode": "CASE_ASSIGNEE_CHANGED", "expectedVersion": status["concurrencyVersion"]},
                         {"Content-Type": "application/json"})["body"]
    note = ctx.probe("POST", "http://127.0.0.1:8080/api/v1/cases/%s/notes" % case_id, 201, analyst,
                     {"content": "local JWT E2E actor check", "expectedVersion": assignee["concurrencyVersion"]},
                     {"Content-Type": "application/json"})["body"]
    if note.get("authorRef") != IDENTITIES["user-analyst"][0]:
        raise VerificationError("note author does not match JWT sub")
    audit_before_stale = int(ctx.sql_scalar("select count(*) from audit_log where case_id='%s'" % case_id))
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/cases/%s/notes" % case_id, 409, analyst,
              {"content": "stale request must roll back", "expectedVersion": assignee["concurrencyVersion"]},
              {"Content-Type": "application/json"})
    if int(ctx.sql_scalar("select count(*) from audit_log where case_id='%s'" % case_id)) != audit_before_stale:
        raise VerificationError("stale USER write created an AuditLog")
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/cases/%s/resolution" % case_id, 200, approver,
              {"finalDisposition": "NORMAL", "reasonCode": "CASE_RESOLUTION_COMPLETED",
               "expectedVersion": note["concurrencyVersion"]}, {"Content-Type": "application/json"})
    analyst_sub = IDENTITIES["user-analyst"][0]
    approver_sub = IDENTITIES["user-approver"][0]
    bad_actors = ctx.sql_scalar(
        "select count(*) from audit_log where case_id='%s' and action in "
        "('CASE_STATUS_CHANGED','CASE_ASSIGNEE_CHANGED','CASE_NOTE_CREATED','CASE_RESOLVED') and "
        "(actor_type <> 'USER' or (action='CASE_RESOLVED' and actor_id <> '%s') or "
        "(action <> 'CASE_RESOLVED' and actor_id <> '%s'))" % (case_id, approver_sub, analyst_sub))
    if bad_actors != "0":
        raise VerificationError("USER AuditLog actor does not match JWT sub")
    audits = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases/%s/audit-logs" % case_id, 200, analyst)["body"]
    if "actorId" in json.dumps(audits, separators=(",", ":")):
        raise VerificationError("audit response exposed actorId")


def rotation_failure_checks(ctx):
    token_a = ctx.mint("user-viewer")
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 200, token_a)
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 200, token_a)
    ctx.control("rotate-overlap")
    token_b = ctx.mint("user-viewer")
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 200, token_b)
    ctx.control("stage")
    token_c = ctx.mint("user-viewer", "unpublished-key")
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 401, token_c)
    ctx.control("fault", "delay")
    unknown = ctx.mint("user-viewer", "unknown-kid")
    started = time.monotonic()
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 503, unknown)
    if time.monotonic() - started > 8:
        raise VerificationError("JWK timeout was not bounded")
    ctx.control("fault", "malformed")
    ctx.control("rotate-overlap")
    malformed_key = ctx.mint("user-viewer")
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 500, malformed_key)
    ctx.control("fault", "normal")
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases", 200, malformed_key)
    ctx.control("stage")
    refusal_key = ctx.mint("user-viewer", "unpublished-key")
    stable = EXPECTED_SERVICES - {"local-jwt-fixture"}
    before = snapshot(ctx, stable)
    ctx.compose_run(["stop", "local-jwt-fixture"])
    ctx.probe_from("external-risk-mock", "http://127.0.0.1:8080/api/v1/cases", 200, malformed_key)
    ctx.probe_from("external-risk-mock", "http://127.0.0.1:8080/api/v1/cases", 503, refusal_key)
    ctx.compose_run(["up", "-d", "--no-deps", "local-jwt-fixture"])
    wait_healthy(ctx, ["local-jwt-fixture"])
    if snapshot(ctx, stable) != before:
        raise VerificationError("fixture stop/start propagated to another service")
    print("rotation-failure: PASS initial=1 cached=2 overlap=1 unknown=1 timeout=1 refusal=1 malformed=1 recovery=2")


def observability_checks(ctx):
    result = ctx.probe("GET", "http://prometheus:9090/api/v1/targets", 200)["body"]
    targets = result.get("data", {}).get("activeTargets", [])
    if not any(item.get("health") == "up" for item in targets):
        raise VerificationError("Prometheus has no UP target")
    rules = ctx.probe("GET", "http://prometheus:9090/api/v1/rules", 200)["body"].get("data", {}).get("groups", [])
    recording = next((g for g in rules if g.get("name") == "finguardops-service-derived"), None)
    alerts = next((g for g in rules if g.get("name") == "finguardops-service-alerts"), None)
    if not recording or len(recording.get("rules", [])) != 14 or not alerts or len(alerts.get("rules", [])) != 6:
        raise VerificationError("Prometheus rule counts changed")
    raw_names = {
        "finguardops_transaction_intake_outcomes_total", "finguardops_transactions_received_total",
        "finguardops_transaction_outcomes_total", "finguardops_http_duplicate_requests_total",
        "finguardops_http_idempotency_conflicts_total", "finguardops_external_risk_outcomes_total",
        "finguardops_rule_analysis_outcomes_total", "finguardops_transaction_processing_duration_seconds_count",
        "finguardops_external_risk_duration_seconds_count", "finguardops_rule_analysis_duration_seconds_count",
    }
    recording_names = {
        "finguardops:transaction_intake:rate5m", "finguardops:transactions_received:rate5m",
        "finguardops:transaction_terminal:rate5m", "finguardops:transaction_terminal_by_status:rate5m",
        "finguardops:transaction_terminal_failure:ratio5m", "finguardops:transaction_processing_duration:avg5m",
        "finguardops:http_duplicate_by_result:rate5m", "finguardops:http_idempotency_conflict:rate5m",
        "finguardops:external_risk_by_result:rate5m", "finguardops:external_risk_failure:ratio5m",
        "finguardops:external_risk_duration:avg5m", "finguardops:rule_analysis_by_result:rate5m",
        "finguardops:rule_analysis_failure:ratio5m", "finguardops:rule_analysis_duration:avg5m",
    }
    poll_metric_names(ctx, raw_names, 120, "raw")
    poll_recording_with_traffic(ctx, recording_names, 180)
    ctx.probe("GET", "http://grafana:3000/api/health", 200)
    ctx.probe("GET", "http://alertmanager:9093/-/ready", 200)
    grafana_contract(ctx)
    stable = EXPECTED_SERVICES - {"grafana"}
    before = snapshot(ctx, stable)
    ctx.compose_run(["stop", "grafana"])
    token = ctx.mint("service-transaction-ingestor")
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", 201, token,
              transaction_payload(str(uuid.uuid4())),
              {"Content-Type": "application/json", "Idempotency-Key": "grafana-down-" + uuid.uuid4().hex})
    if snapshot(ctx, stable) != before:
        raise VerificationError("Grafana stop propagated to another service")
    ctx.compose_run(["up", "-d", "--no-deps", "grafana"])
    wait_healthy(ctx, ["grafana"])
    if snapshot(ctx, stable) != before:
        raise VerificationError("Grafana recovery propagated to another service")
    print("observability: PASS prometheus_up=1 raw=10 recording=14 alerts=6 grafana_provisioning=16 grafana_down_tx=201 alertmanager=1")


def poll_metric_names(ctx, expected, seconds, phase):
    url = "http://prometheus:9090/api/v1/label/__name__/values"
    deadline = min(ctx.end, time.monotonic() + seconds)
    while time.monotonic() < deadline:
        actual = set(ctx.probe("GET", url, 200)["body"].get("data", []))
        if expected <= actual:
            return
        time.sleep(2)
    missing = sorted(expected - actual)
    raise VerificationError("Prometheus %s sample set incomplete: %s" % (phase, ",".join(missing)))


def poll_recording_with_traffic(ctx, expected, seconds):
    url = "http://prometheus:9090/api/v1/label/__name__/values"
    deadline = min(ctx.end, time.monotonic() + seconds)
    token = ctx.mint("service-transaction-ingestor")
    actual = set()
    while time.monotonic() < deadline:
        ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", 201, token,
                  transaction_payload(str(uuid.uuid4())),
                  {"Content-Type": "application/json", "Idempotency-Key": "recording-" + uuid.uuid4().hex})
        actual = set(ctx.probe("GET", url, 200)["body"].get("data", []))
        if expected <= actual:
            return
        time.sleep(2)
    raise VerificationError("Prometheus recording sample set incomplete: %s" %
                            ",".join(sorted(expected - actual)))


def grafana_contract(ctx):
    credentials = base64.b64encode((ctx.env["GRAFANA_ADMIN_USER"] + ":" +
                                    ctx.env["GRAFANA_ADMIN_PASSWORD"]).encode()).decode("ascii")
    def get(path):
        request = urllib.request.Request("http://127.0.0.1:3000" + path,
                                         headers={"Authorization": "Basic " + credentials})
        try:
            with urllib.request.urlopen(request, timeout=min(10, ctx.remaining())) as response:
                return json.load(response)
        except (OSError, ValueError, urllib.error.HTTPError) as exc:
            raise VerificationError("Grafana provisioning query failed") from exc
    datasource = get("/api/datasources/uid/finguardops-prometheus")
    dashboard = get("/api/dashboards/uid/finguardops-local-overview")
    if datasource.get("type") != "prometheus" or len(dashboard.get("dashboard", {}).get("panels", [])) != 16:
        raise VerificationError("Grafana datasource/dashboard contract changed")


def publish_rules(ctx):
    effective = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=90)).replace(microsecond=0)
    effective_text = effective.isoformat().replace("+00:00", "Z")
    ctx.compose_run([
        "run", "--rm", "--no-deps", "-T",
        "-e", "SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication",
        "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
        "backend", "--spring.main.web-application-type=none",
        "--finguardops.rule-v1-default-publication.enabled=true",
        "--finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1",
        "--finguardops.rule-v1-default-publication.effective-from=%s" % effective_text,
    ], timeout=min(180, ctx.remaining()))
    query = ("select count(*) from rule_version where status='PUBLISHED' "
             "and effective_from <= current_timestamp and rule_version_id in "
             "('20000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000002',"
             "'20000000-0000-4000-8000-000000000003','20000000-0000-4000-8000-000000000004')")
    while time.monotonic() < ctx.end:
        value = ctx.compose_run(["exec", "-T", "postgresql", "psql", "-U", "finguardops",
                                 "-d", "finguardops", "-tAc", query]).decode().strip()
        if value == "4":
            print("rule-publication: PASS published=4 active=4")
            return
        time.sleep(1)
    raise VerificationError("RuleVersion activation polling expired")


def snapshot(ctx, services):
    result = {}
    for service in services:
        cid = ctx.compose_run(["ps", "-q", service]).decode().strip()
        if not cid:
            raise VerificationError("missing container: %s" % service)
        out = run(["docker", "inspect", cid, "--format", "{{.Id}}|{{.State.StartedAt}}|{{.RestartCount}}"],
                  timeout=ctx.remaining())
        result[service] = out.decode().strip()
    return result


def assert_snapshots_unchanged(before, after, phase):
    changed = sorted(service for service in before if after.get(service) != before[service])
    if changed:
        raise VerificationError("unrelated service changed during %s: %s" %
                                (phase, ",".join(changed)))


def wait_healthy(ctx, services):
    while time.monotonic() < ctx.end:
        healthy = True
        for service in services:
            cid = ctx.compose_run(["ps", "-q", service]).decode().strip()
            if not cid:
                healthy = False; break
            status = run(["docker", "inspect", cid, "--format",
                          "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}"],
                         timeout=ctx.remaining()).decode().strip()
            if status not in {"healthy", "running"}:
                healthy = False; break
        if healthy:
            return
        time.sleep(1)
    raise VerificationError("bounded readiness polling expired")


def lifecycle_checks(ctx):
    stable = EXPECTED_SERVICES - {"backend", "external-risk-mock", "local-jwt-fixture"}
    before = snapshot(ctx, stable)
    ctx.compose_run(["stop", "external-risk-mock", "local-jwt-fixture"])
    ctx.compose_run(["rm", "-f", "external-risk-mock", "local-jwt-fixture"])
    ctx.compose_run(["up", "-d", "--no-deps", "--force-recreate", "backend"], timeout=min(180, ctx.remaining()))
    wait_healthy(ctx, ["backend"])
    ctx.compose_run(["up", "-d", "--no-deps", "external-risk-mock", "local-jwt-fixture"], timeout=min(180, ctx.remaining()))
    wait_healthy(ctx, ["external-risk-mock", "local-jwt-fixture"])
    after = snapshot(ctx, stable)
    assert_snapshots_unchanged(before, after, "backend recreation")
    namespaces = []
    for service in ("backend", "external-risk-mock", "local-jwt-fixture"):
        namespaces.append(ctx.compose_run(["exec", "-T", service, "readlink", "/proc/self/ns/net"]).decode().strip())
    if len(set(namespaces)) != 1:
        raise VerificationError("sidecars do not share the recreated backend namespace")
    jwks_before = ctx.probe("GET", JWKS_URL, 200)["body"]
    old_kids = {key["kid"] for key in jwks_before["keys"]}
    before_all = snapshot(ctx, EXPECTED_SERVICES - {"local-jwt-fixture"})
    ctx.compose_run(["up", "-d", "--no-deps", "--force-recreate", "local-jwt-fixture"])
    wait_healthy(ctx, ["local-jwt-fixture"])
    after_all = snapshot(ctx, EXPECTED_SERVICES - {"local-jwt-fixture"})
    assert_snapshots_unchanged(before_all, after_all, "fixture recreation")
    jwks_after = ctx.probe("GET", JWKS_URL, 200)["body"]
    new_kids = {key["kid"] for key in jwks_after["keys"]}
    if old_kids & new_kids or len(new_kids) != 1:
        raise VerificationError("fixture recreate did not replace the ephemeral key")
    print("lifecycle: PASS backend_recreate_sidecars=2 fixture_recreate_key_change=1 isolation=8")


def mutated_unit_probe(ctx, label, relative_path, old, new, test_name):
    with tempfile.TemporaryDirectory(prefix="finguardops-jwt-mutation-") as directory:
        root = Path(directory) / "local-jwt-fixture"
        tests = root / "tests"
        tests.mkdir(parents=True)
        for name in ("fixture.py", "verify_e2e.py"):
            shutil.copy2(ctx.repo / "infra/local-jwt-fixture" / name, root / name)
        shutil.copy2(
            ctx.repo / "infra/local-jwt-fixture/tests/test_fixture.py",
            tests / "test_fixture.py",
        )
        target = root / relative_path
        source = target.read_text(encoding="utf-8")
        if source.count(old) != 1:
            raise VerificationError("mutation target is not unique: %s" % label)
        command = [
            "docker", "run", "--rm", "--user", "10001:10001", "--read-only",
            "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=8m,mode=1777",
            "--tmpfs", "/run/local-jwt:rw,nosuid,nodev,noexec,size=4m,mode=0700,uid=10001,gid=10001",
            "--mount", "type=bind,src=%s,dst=/opt/local-jwt-fixture,readonly" % root,
            "--entrypoint", "python", EXPECTED_IMAGE, "-B",
            "/opt/local-jwt-fixture/tests/test_fixture.py", "-k", test_name,
        ]
        environment = {
            **os.environ, "PYTHONDONTWRITEBYTECODE": "1", "MSYS_NO_PATHCONV": "1",
            "MSYS2_ARG_CONV_EXCL": "*",
        }
        baseline = subprocess.run(
            command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
            shell=False, timeout=min(180, ctx.remaining()), cwd=str(ctx.repo), env=environment,
        )
        if baseline.returncode != 0:
            raise VerificationError("mutation baseline test did not pass: %s" % label)
        target.write_text(source.replace(old, new), encoding="utf-8")
        result = subprocess.run(
            command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
            shell=False, timeout=min(180, ctx.remaining()), cwd=str(ctx.repo), env=environment,
        )
        if result.returncode == 0:
            raise VerificationError("mutation survived its behavior test: %s" % label)
        if JWT_RE.search(result.stdout.decode("utf-8", "replace")) or JWT_RE.search(
                result.stderr.decode("utf-8", "replace")):
            if label != "token-log-sentinel":
                raise VerificationError("mutation test output exposed a JWT-like value")
    return "%s:%s" % (label, test_name)


def mutation_checks(ctx):
    original = merged_config(ctx)
    mutations = []
    def expect(label, change):
        candidate = copy.deepcopy(original)
        change(candidate)
        try:
            validate_config(candidate, base_text=ctx.base.read_text(encoding="utf-8"))
        except VerificationError:
            mutations.append(label); return
        raise VerificationError("mutation escaped detection: %s" % label)
    expect("wildcard-host-publish", lambda c: c["services"]["local-jwt-fixture"].update({"ports": [{"host_ip": "0.0.0.0", "published": 8002, "target": 8002}]}))
    expect("observability-network", lambda c: c["services"]["local-jwt-fixture"].update({"networks": {"observability": None}}))
    expect("named-volume", lambda c: c["volumes"].update({"jwt-keys": {}}))
    expect("private-key-bind", lambda c: c["services"]["local-jwt-fixture"]["volumes"].append(
        {"type": "bind", "source": "/tmp/key.pem", "target": "/run/local-jwt/key.pem", "read_only": False}))
    expect("root", lambda c: c["services"]["local-jwt-fixture"].update({"user": "0:0"}))
    expect("writable-root", lambda c: c["services"]["local-jwt-fixture"].update({"read_only": False}))
    expect("no-new-privileges", lambda c: c["services"]["local-jwt-fixture"].update({"security_opt": []}))
    expect("production-profile", lambda c: c["services"]["backend"]["environment"].update({"SPRING_PROFILES_ACTIVE": "production"}))
    try:
        validate_config(copy.deepcopy(original), base_text="FINGUARDOPS_SECURITY_INSECURE_LOOPBACK_JWK_ALLOWED")
    except VerificationError:
        mutations.append("insecure-base-leak")
    else:
        raise VerificationError("mutation escaped detection: insecure-base-leak")
    fixture_mutations = [
        ("arbitrary-identity", "fixture.py", "    \"user-platform-admin\": Identity(\n        \"edaa43d7-c04f-4195-a7f7-82ee7f1a0de1\", \"USER\", (\"PLATFORM_ADMIN\",)\n    ),\n",
         "    \"user-platform-admin\": Identity(\n        \"edaa43d7-c04f-4195-a7f7-82ee7f1a0de1\", \"USER\", (\"PLATFORM_ADMIN\",)\n    ),\n    \"arbitrary-user\": Identity(\n        \"d3d2cbca-c811-4b37-9817-1ee9b314fdb6\", \"USER\", (\"FDS_VIEWER\",)\n    ),\n",
         "test_identity_allowlist_has_exact_public_contract"),
        ("arbitrary-role", "fixture.py", '("FDS_VIEWER",)', '("FDS_APPROVER",)',
         "test_identity_allowlist_has_exact_public_contract"),
        ("arbitrary-lifetime", "fixture.py", "TOKEN_LIFETIME_SECONDS = 840",
         "TOKEN_LIFETIME_SECONDS = 99999", "test_identity_allowlist_has_exact_public_contract"),
        ("blank-kid", "fixture.py", "kid = b64url(hashlib.sha256(public_der).digest())", 'kid = ""',
         "test_generated_rsa_jwk_and_permissions"),
        ("missing-kid", "fixture.py", '{"alg": "RS256", "kid": signing_key.kid, "typ": "JWT"}',
         '{"alg": "RS256", "typ": "JWT"}', "test_normal_token_claims_are_exact"),
        ("wrong-alg", "fixture.py", '{"alg": "RS256", "kid": signing_key.kid, "typ": "JWT"}',
         '{"alg": "RS512", "kid": signing_key.kid, "typ": "JWT"}', "test_normal_token_claims_are_exact"),
        ("wrong-audience", "fixture.py", 'AUDIENCE = ["finguardops-backend-api"]',
         'AUDIENCE = ["wrong"]', "test_normal_token_claims_are_exact"),
        ("wrong-issuer", "fixture.py", 'ISSUER = "https://local-jwt.fixture.finguardops.invalid"',
         'ISSUER = "https://wrong.invalid"', "test_normal_token_claims_are_exact"),
        ("wrong-principal-type", "fixture.py", '"principal_type": identity.principal_type',
         '"principal_type": "SERVICE"', "test_normal_token_claims_are_exact"),
        ("jwks-new-key-omission", "fixture.py", "            self.published.append(key)\n",
         "            pass\n", "test_rotation_overlap_and_unpublished_key"),
        ("malformed-jwks", "fixture.py", 'return {"keys": [dict(item.jwk) for item in self.published]}',
         'return {"keys": "malformed"}', "test_http_surface_and_no_cors"),
        ("token-log-sentinel", "fixture.py",
         '        return signing_input.decode("ascii") + "." + b64url(signature)\n',
         '        token = signing_input.decode("ascii") + "." + b64url(signature)\n        print(token)\n        return token\n',
         "test_normal_token_claims_are_exact"),
    ]
    verifier_mutations = [
        ("cleanup-omission", "verify_e2e.py",
         'PROJECT_RESOURCE_' + 'KINDS = ("container", "network", "volume")',
         'PROJECT_RESOURCE_KINDS = ("container", "volume")',
         "test_fresh_project_checks_exact_label_for_all_resource_kinds"),
        ("service-restart", "verify_e2e.py",
         '    if changed:\n        raise VerificationError("unrelated service changed during %s: %s" %\n' +
         '                                (phase, ",".join(changed)))\n',
         '    if False and changed:\n        raise VerificationError("unrelated service changed during %s: %s" %\n                                (phase, ",".join(changed)))\n',
         "test_non_target_restart_guard_detects_identity_or_restart_change"),
    ]
    killed = [mutated_unit_probe(ctx, *specification)
              for specification in fixture_mutations + verifier_mutations]
    mutations.extend(item.split(":", 1)[0] for item in killed)
    print("mutations: PASS detected=%d actual_unit_mutations=%d labels=%s" %
          (len(mutations), len(killed), ",".join(mutations)))


def probe_main(args):
    validate_url(args.url)
    if args.timeout <= 0:
        raise VerificationError("HTTP timeout must be positive")
    token = sys.stdin.readline(16384).rstrip("\r\n")
    if len(token) >= 16383:
        raise VerificationError("token input is too large")
    headers = {}
    for item in args.header:
        if ":" not in item:
            raise VerificationError("header syntax is invalid")
        key, value = item.split(":", 1)
        if key.lower() == "authorization" or "\r" in value or "\n" in value:
            raise VerificationError("header is forbidden")
        headers[key] = value
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if args.body_b64 is not None:
        data = base64.urlsafe_b64decode(args.body_b64 + "=" * (-len(args.body_b64) % 4))
        if len(data) > 65536:
            raise VerificationError("request body exceeds verifier limit")
    request = urllib.request.Request(args.url, data=data, headers=headers, method=args.method)
    body = b""; status = 0
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            status = response.status; body = response.read(262145)
    except urllib.error.HTTPError as error:
        status = error.code
        error.read(262145)  # Consume but never report an untrusted error body.
    except (OSError, TimeoutError) as exc:
        raise VerificationError("HTTP transport failed") from exc
    if status != args.expected:
        raise VerificationError("unexpected HTTP status expected=%d actual=%d" % (args.expected, status))
    parsed = None
    if 200 <= status < 300 and body:
        if len(body) > 262144:
            raise VerificationError("HTTP response exceeds verifier limit")
        try:
            parsed = json.loads(body)
        except json.JSONDecodeError:
            parsed = None
    print(json.dumps({"status": status, "body": parsed}, separators=(",", ":"), sort_keys=True))


def project_resources(project, *, timeout, command_runner=run):
    commands = {
        "container": ["docker", "ps", "-aq", "--filter",
                      "label=com.docker.compose.project=%s" % project],
        "network": ["docker", "network", "ls", "-q", "--filter",
                    "label=com.docker.compose.project=%s" % project],
        "volume": ["docker", "volume", "ls", "-q", "--filter",
                   "label=com.docker.compose.project=%s" % project],
    }
    resources = {}
    for kind in PROJECT_RESOURCE_KINDS:
        output = command_runner(commands[kind], timeout=timeout)
        resources[kind] = tuple(item for item in output.decode("utf-8", "strict").splitlines() if item)
    return resources


def assert_project_resources_empty(resources, phase):
    if set(resources) != set(PROJECT_RESOURCE_KINDS):
        raise VerificationError("project resource inventory is incomplete during %s" % phase)
    residual = {kind: len(resources[kind]) for kind in PROJECT_RESOURCE_KINDS if resources[kind]}
    if residual:
        raise VerificationError("project resources exist during %s: %s" %
                                (phase, ",".join("%s=%d" % item for item in sorted(residual.items()))))


def ensure_fresh_project(ctx, resource_reader=project_resources):
    if not PROJECT_RE.fullmatch(ctx.project):
        raise VerificationError("project name is not an approved fresh E2E name")
    resources = resource_reader(ctx.project, timeout=ctx.remaining())
    assert_project_resources_empty(resources, "fresh-project check")


def compose_down(ctx):
    run(
        ctx.compose + ["down", "--volumes", "--remove-orphans", "--timeout", "20"],
        timeout=max(30, min(180, ctx.timeout * 6)), cwd=str(ctx.repo), env=ctx.env,
    )


def finalize_project(ctx, original_error=None, resource_reader=project_resources,
                     down_action=None):
    cleanup_failures = []
    try:
        (down_action or (lambda: compose_down(ctx)))()
    except Exception as exc:
        cleanup_failures.append("compose down failed: %s" % type(exc).__name__)
    try:
        resources = resource_reader(
            ctx.project,
            timeout=max(5, ctx.timeout),
        )
        assert_project_resources_empty(resources, "cleanup check")
    except Exception as exc:
        cleanup_failures.append("resource cleanup check failed: %s" % scrub(str(exc)))
    if original_error is not None:
        original_error.cleanup_failures = tuple(cleanup_failures)
        for failure in cleanup_failures:
            if hasattr(original_error, "add_note"):
                original_error.add_note(failure)
        if cleanup_failures:
            print("cleanup also failed after the primary E2E failure: %s" %
                  "; ".join(cleanup_failures), file=sys.stderr)
        raise original_error
    if cleanup_failures:
        raise VerificationError("; ".join(cleanup_failures))


def actual_cli_rejection_checks(ctx):
    attempts = [
        ["machine", "mint", "arbitrary-user"],
        ["machine", "mint", "user-viewer", "--role", "FDS_APPROVER"],
        ["machine", "mint", "user-viewer", "--lifetime", "99999"],
    ]
    merged_env = os.environ.copy()
    merged_env.update(ctx.env)
    merged_env.update({"MSYS_NO_PATHCONV": "1", "MSYS2_ARG_CONV_EXCL": "*"})
    for arguments in attempts:
        result = subprocess.run(
            ctx.compose + ["exec", "-T", "local-jwt-fixture", "python",
                           "/opt/local-jwt-fixture/fixture.py"] + arguments,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, shell=False,
            timeout=min(ctx.timeout, ctx.remaining()), cwd=str(ctx.repo), env=merged_env,
        )
        stdout = result.stdout.decode("utf-8", "replace")
        stderr = result.stderr.decode("utf-8", "replace")
        if result.returncode == 0 or JWT_RE.search(stdout) or JWT_RE.search(stderr):
            raise VerificationError("unapproved mint CLI request was not safely rejected")
    print("control-cli: PASS rejected_identity=1 rejected_role=1 rejected_lifetime=1 tokens=0")


def all_checks(ctx):
    ensure_fresh_project(ctx)
    static_checks(ctx)
    with tempfile.TemporaryDirectory(prefix="finguardops-jwt-e2e-"):
        original_error = None
        try:
            ctx.compose_run(["up", "-d", "--build", "--wait"], timeout=min(900, ctx.remaining()))
            publish_rules(ctx); token_claim_checks(ctx); actual_cli_rejection_checks(ctx)
            runtime_checks(ctx); security_matrix_checks(ctx)
            rotation_failure_checks(ctx); observability_checks(ctx); lifecycle_checks(ctx)
        except BaseException as exc:
            original_error = exc
        finalize_project(ctx, original_error)
    print("all: PASS cleanup=complete")


def parser():
    result = argparse.ArgumentParser(description="FinGuardOps local JWT E2E verifier")
    sub = result.add_subparsers(dest="mode", required=True)
    common_modes = ["static", "token-claim", "runtime", "security-matrix", "rotation-failure",
                    "observability", "lifecycle", "mutations", "all"]
    for mode in common_modes:
        item = sub.add_parser(mode)
        item.add_argument("--repo-root", default=str(Path(__file__).resolve().parents[2]))
        item.add_argument("--project", default="finguardops-jwt-e2e-manual")
        item.add_argument("--cli-timeout", type=float, default=30)
        item.add_argument("--deadline-seconds", type=float, default=1800)
    internal = sub.add_parser("_probe")
    internal.add_argument("--method", required=True, choices=["GET", "POST", "PATCH"])
    internal.add_argument("--url", required=True)
    internal.add_argument("--expected", required=True, type=int)
    internal.add_argument("--timeout", required=True, type=float)
    internal.add_argument("--body-b64")
    internal.add_argument("--header", action="append", default=[])
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    if args.mode == "_probe":
        probe_main(args); return 0
    if args.cli_timeout <= 0 or args.deadline_seconds <= 0:
        raise VerificationError("CLI timeout and overall deadline must be positive")
    repo = Path(args.repo_root)
    if not repo.is_absolute() or not (repo / ".git").exists():
        raise VerificationError("repo root must be an absolute FinGuardOps checkout")
    if not PROJECT_RE.fullmatch(args.project):
        raise VerificationError("invalid Compose project name")
    ctx = Context(repo, args.project, args.cli_timeout, args.deadline_seconds)
    actions = {
        "static": static_checks, "token-claim": token_claim_checks, "runtime": runtime_checks,
        "security-matrix": security_matrix_checks, "rotation-failure": rotation_failure_checks,
        "observability": observability_checks, "lifecycle": lifecycle_checks,
        "mutations": mutation_checks, "all": all_checks,
    }
    actions[args.mode](ctx)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as exc:
        print("verification failed: %s" % scrub(str(exc)), file=sys.stderr)
        raise SystemExit(1)
    except Exception as exc:
        print("verification failed: unexpected %s" % type(exc).__name__, file=sys.stderr)
        raise SystemExit(1)
