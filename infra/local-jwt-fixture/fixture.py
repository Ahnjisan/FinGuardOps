#!/usr/bin/env python3
"""Local-only RS256 JWT/JWKS fixture for FinGuardOps Compose E2E.

This is deliberately not an Authorization Server.  It exposes only readiness
and JWKS over loopback HTTP.  Minting and fault control use a private Unix
domain socket in tmpfs.
"""

import argparse
import base64
import hashlib
import http.server
import json
import os
import signal
import socket
import socketserver
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path


ISSUER = "https://local-jwt.fixture.finguardops.invalid"
AUDIENCE = ["finguardops-backend-api"]
HTTP_HOST = "127.0.0.1"
HTTP_PORT = 8002
READY_PATH = "/ready"
JWKS_PATH = "/oauth2/jwks"
STATE_DIR = Path("/run/local-jwt")
CONTROL_SOCKET = STATE_DIR / "control.sock"
MAX_CONTROL_BYTES = 512
MAX_HTTP_TARGET_BYTES = 256
OPENSSL_TIMEOUT_SECONDS = 10
TOKEN_LIFETIME_SECONDS = 840
DELAY_SECONDS = 4


@dataclass(frozen=True)
class Identity:
    sub: str
    principal_type: str
    roles: tuple


IDENTITIES = {
    "service-transaction-ingestor": Identity(
        "9d0edbde-f833-43e2-822a-43a1c38d82ec", "SERVICE", ("TRANSACTION_INGESTOR",)
    ),
    "service-behavior-ingestor": Identity(
        "a0dc7e4b-1260-4888-9e14-54867c9f2293", "SERVICE", ("BEHAVIOR_INGESTOR",)
    ),
    "user-viewer": Identity(
        "3d005f9e-f48e-45e9-98f1-5f9c407d2021", "USER", ("FDS_VIEWER",)
    ),
    "user-analyst": Identity(
        "8fbcd138-76f7-44a8-85f1-3afcf118f1c6", "USER", ("FDS_ANALYST",)
    ),
    "user-approver": Identity(
        "f5b2501d-0c30-462b-b699-8cbb7aa6f3f2", "USER", ("FDS_APPROVER",)
    ),
    "user-analyst-approver": Identity(
        "35b78471-c387-48a8-af51-3490c8718216",
        "USER",
        ("FDS_ANALYST", "FDS_APPROVER"),
    ),
    "user-platform-admin": Identity(
        "edaa43d7-c04f-4195-a7f7-82ee7f1a0de1", "USER", ("PLATFORM_ADMIN",)
    ),
}

NEGATIVE_VARIANTS = {
    "expired",
    "wrong-audience",
    "wrong-issuer",
    "wrong-principal-type",
    "duplicate-role",
    "unknown-role",
    "blank-kid",
    "missing-kid",
    "wrong-alg",
    "unknown-kid",
    "unpublished-key",
}


def b64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def json_bytes(value):
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _der_length(data, offset):
    first = data[offset]
    if first < 128:
        return first, offset + 1
    count = first & 0x7F
    if count == 0 or count > 4:
        raise ValueError("unsupported DER length")
    end = offset + 1 + count
    return int.from_bytes(data[offset + 1:end], "big"), end


def _der_tlv(data, offset, expected_tag):
    if offset >= len(data) or data[offset] != expected_tag:
        raise ValueError("unexpected DER tag")
    length, start = _der_length(data, offset + 1)
    end = start + length
    if end > len(data):
        raise ValueError("truncated DER value")
    return data[start:end], end


def rsa_numbers_from_spki(der):
    outer, end = _der_tlv(der, 0, 0x30)
    if end != len(der):
        raise ValueError("trailing DER data")
    _algorithm, offset = _der_tlv(outer, 0, 0x30)
    bit_string, offset = _der_tlv(outer, offset, 0x03)
    if offset != len(outer) or not bit_string or bit_string[0] != 0:
        raise ValueError("invalid public key bit string")
    rsa, rsa_end = _der_tlv(bit_string[1:], 0, 0x30)
    if rsa_end != len(bit_string) - 1:
        raise ValueError("invalid RSA key wrapper")
    modulus, pos = _der_tlv(rsa, 0, 0x02)
    exponent, pos = _der_tlv(rsa, pos, 0x02)
    if pos != len(rsa):
        raise ValueError("invalid RSA key fields")
    modulus = modulus.lstrip(b"\x00")
    exponent = exponent.lstrip(b"\x00")
    if len(modulus) < 256 or int.from_bytes(exponent, "big") != 65537:
        raise ValueError("RSA key contract violation")
    return modulus, exponent


def run_openssl(argv, *, input_bytes=None):
    try:
        result = subprocess.run(
            argv,
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=OPENSSL_TIMEOUT_SECONDS,
            shell=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("OpenSSL operation failed") from exc
    if result.returncode != 0:
        raise RuntimeError("OpenSSL operation failed")
    return result.stdout


@dataclass(frozen=True)
class KeyMaterial:
    path: Path
    kid: str
    jwk: dict


def generate_key(slot):
    STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(str(STATE_DIR), 0o700)
    path = STATE_DIR / ("key-%s.pem" % slot)
    try:
        path.unlink()
    except FileNotFoundError:
        pass
    run_openssl([
        "openssl", "genpkey", "-algorithm", "RSA",
        "-pkeyopt", "rsa_keygen_bits:2048",
        "-pkeyopt", "rsa_keygen_pubexp:65537",
        "-out", str(path),
    ])
    os.chmod(str(path), 0o600)
    public_der = run_openssl([
        "openssl", "pkey", "-in", str(path), "-pubout", "-outform", "DER"
    ])
    modulus, exponent = rsa_numbers_from_spki(public_der)
    kid = b64url(hashlib.sha256(public_der).digest())
    if not kid.strip():
        raise RuntimeError("generated key identifier is blank")
    return KeyMaterial(path, kid, {
        "alg": "RS256", "e": b64url(exponent), "kid": kid,
        "kty": "RSA", "n": b64url(modulus), "use": "sig",
    })


class FixtureState:
    def __init__(self):
        self.lock = threading.RLock()
        first = generate_key("initial")
        self.active = first
        self.published = [first]
        self.staged = None
        self.generation = 1
        self.fault = "normal"

    def rotate_overlap(self):
        with self.lock:
            self.generation += 1
            key = generate_key("generation-%d" % self.generation)
            self.active = key
            self.published.append(key)
            return key.kid

    def stage(self):
        with self.lock:
            self.generation += 1
            self.staged = generate_key("staged-%d" % self.generation)
            return self.staged.kid

    def jwks(self):
        with self.lock:
            return {"keys": [dict(item.jwk) for item in self.published]}

    def mint(self, identity_name, variant="normal"):
        identity = IDENTITIES[identity_name]
        now = int(time.time())
        # KeyMaterial is immutable and retained for the fixture lifetime.  Take
        # the kid and signing path from one locked snapshot, then release the
        # lock before the comparatively slow OpenSSL operation.
        with self.lock:
            signing_key = self.active
            if variant == "unpublished-key":
                if self.staged is None:
                    raise ValueError("staged key is unavailable")
                signing_key = self.staged
        header = {"alg": "RS256", "kid": signing_key.kid, "typ": "JWT"}
        claims = {
            "aud": list(AUDIENCE), "exp": now + TOKEN_LIFETIME_SECONDS,
            "iat": now, "iss": ISSUER, "nbf": now - 1,
            "principal_type": identity.principal_type,
            "roles": list(identity.roles), "sub": identity.sub,
        }
        if variant == "expired":
            claims["iat"], claims["nbf"], claims["exp"] = now - 900, now - 900, now - 60
        elif variant == "wrong-audience":
            claims["aud"] = ["unapproved-api"]
        elif variant == "wrong-issuer":
            claims["iss"] = "https://wrong.fixture.invalid"
        elif variant == "wrong-principal-type":
            claims["principal_type"] = "USER" if identity.principal_type == "SERVICE" else "SERVICE"
        elif variant == "duplicate-role":
            claims["roles"] = list(identity.roles) + [identity.roles[0]]
        elif variant == "unknown-role":
            claims["roles"] = ["UNAPPROVED_ROLE"]
        elif variant == "blank-kid":
            header["kid"] = ""
        elif variant == "missing-kid":
            del header["kid"]
        elif variant == "wrong-alg":
            header["alg"] = "RS512"
        elif variant == "unknown-kid":
            header["kid"] = "unpublished-unknown-kid"
        elif variant == "unpublished-key":
            pass
        encoded_header = b64url(json_bytes(header))
        encoded_claims = b64url(json_bytes(claims))
        signing_input = (encoded_header + "." + encoded_claims).encode("ascii")
        signature = run_openssl(
            ["openssl", "dgst", "-sha256", "-sign", str(signing_key.path), "-binary"],
            input_bytes=signing_input,
        )
        return signing_input.decode("ascii") + "." + b64url(signature)


class FixtureHttpHandler(http.server.BaseHTTPRequestHandler):
    server_version = "FinGuardOpsLocalFixture"
    sys_version = ""

    def log_message(self, _format, *_args):
        return

    def send_error(self, code, message=None, explain=None):
        if code == 501:
            self._headers(405, "text/plain; charset=utf-8")
            return
        super().send_error(code, message, explain)

    def _headers(self, status, content_type, length=0):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()

    def do_GET(self):
        if len(self.path.encode("utf-8", "ignore")) > MAX_HTTP_TARGET_BYTES:
            self._headers(414, "text/plain; charset=utf-8")
            return
        if self.path == READY_PATH:
            body = b'{"status":"ready"}'
            self._headers(200, "application/json", len(body))
            self.wfile.write(body)
            return
        if self.path == JWKS_PATH:
            state = self.server.fixture_state
            with state.lock:
                fault = state.fault
            if fault == "delay":
                time.sleep(DELAY_SECONDS)
            if fault == "malformed":
                body = b'{"keys":"malformed"}'
            else:
                body = json_bytes(state.jwks())
            self._headers(200, "application/json", len(body))
            self.wfile.write(body)
            return
        self._headers(404, "text/plain; charset=utf-8")

    def do_HEAD(self):
        self._headers(405, "text/plain; charset=utf-8")

    def do_POST(self):
        self._headers(405, "text/plain; charset=utf-8")

    do_PUT = do_POST
    do_PATCH = do_POST
    do_DELETE = do_POST
    do_OPTIONS = do_POST


class ThreadingHttpServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def strict_json(line):
    def pairs_hook(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("duplicate field")
            value[key] = item
        return value
    value = json.loads(line.decode("utf-8"), object_pairs_hook=pairs_hook)
    if not isinstance(value, dict):
        raise ValueError("command must be an object")
    return value


def execute_command(state, request):
    command = request.get("command")
    if command == "mint" and set(request) == {"command", "identity", "variant"}:
        identity = request["identity"]
        variant = request["variant"]
        if identity not in IDENTITIES or (variant != "normal" and variant not in NEGATIVE_VARIANTS):
            raise ValueError("unapproved mint request")
        return {"ok": True, "token": state.mint(identity, variant)}
    if command == "rotate-overlap" and set(request) == {"command"}:
        state.rotate_overlap()
        return {"ok": True}
    if command == "stage" and set(request) == {"command"}:
        state.stage()
        return {"ok": True}
    if command == "fault" and set(request) == {"command", "mode"}:
        if request["mode"] not in {"normal", "malformed", "delay"}:
            raise ValueError("unapproved fault mode")
        with state.lock:
            state.fault = request["mode"]
        return {"ok": True}
    if command == "status" and set(request) == {"command"}:
        with state.lock:
            return {"ok": True, "ready": True, "published_keys": len(state.published),
                    "staged": state.staged is not None, "fault": state.fault}
    raise ValueError("unknown or malformed command")


class ControlHandler(socketserver.StreamRequestHandler):
    def handle(self):
        line = self.rfile.readline(MAX_CONTROL_BYTES + 1)
        try:
            if not line.endswith(b"\n") or len(line) > MAX_CONTROL_BYTES:
                raise ValueError("invalid command length")
            self.request.settimeout(0.01)
            try:
                if self.rfile.peek(1):
                    raise ValueError("multiple commands are not allowed")
            except socket.timeout:
                pass
            response = execute_command(self.server.fixture_state, strict_json(line))
        except Exception:
            response = {"ok": False, "error": "command rejected"}
        self.wfile.write(json_bytes(response) + b"\n")


class ControlServer(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True


def serve():
    if os.getuid() != 10001 or os.getgid() != 10001:
        raise RuntimeError("fixture must run as 10001:10001")
    state = FixtureState()
    try:
        CONTROL_SOCKET.unlink()
    except FileNotFoundError:
        pass
    control = ControlServer(str(CONTROL_SOCKET), ControlHandler)
    control.fixture_state = state
    os.chmod(str(CONTROL_SOCKET), 0o600)
    httpd = ThreadingHttpServer((HTTP_HOST, HTTP_PORT), FixtureHttpHandler)
    httpd.fixture_state = state
    thread = threading.Thread(target=control.serve_forever, name="fixture-control", daemon=True)
    thread.start()

    def shutdown(_signum, _frame):
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    try:
        httpd.serve_forever(poll_interval=0.2)
    finally:
        control.shutdown()
        control.server_close()
        httpd.server_close()
        try:
            CONTROL_SOCKET.unlink()
        except FileNotFoundError:
            pass


def socket_request(request, timeout):
    payload = json_bytes(request) + b"\n"
    if len(payload) > MAX_CONTROL_BYTES:
        raise RuntimeError("control request rejected")
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(timeout)
            client.connect(str(CONTROL_SOCKET))
            client.sendall(payload)
            chunks = []
            total = 0
            while True:
                part = client.recv(4096)
                if not part:
                    break
                chunks.append(part)
                total += len(part)
                if total > 16384:
                    raise RuntimeError("control response rejected")
        response = strict_json(b"".join(chunks))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        raise RuntimeError("control operation failed") from exc
    if response.get("ok") is not True:
        raise RuntimeError("control operation rejected")
    return response


def cli(argv=None):
    parser = argparse.ArgumentParser(description="FinGuardOps local JWT fixture")
    sub = parser.add_subparsers(dest="mode", required=True)
    sub.add_parser("serve")
    for mode in ("machine", "show"):
        mint = sub.add_parser(mode)
        mint.add_argument("command", choices=["mint"])
        mint.add_argument("identity", choices=sorted(IDENTITIES))
        mint.add_argument("--variant", choices=["normal"] + sorted(NEGATIVE_VARIANTS), default="normal")
        mint.add_argument("--timeout", type=float, default=5.0)
    control = sub.add_parser("control")
    control.add_argument("command", choices=["rotate-overlap", "stage", "status", "fault"])
    control.add_argument("value", nargs="?", choices=["normal", "malformed", "delay"])
    control.add_argument("--timeout", type=float, default=5.0)
    args = parser.parse_args(argv)
    if args.mode == "serve":
        serve()
        return 0
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if args.mode in {"machine", "show"}:
        response = socket_request({"command": "mint", "identity": args.identity,
                                   "variant": args.variant}, args.timeout)
        token = response.get("token")
        if not isinstance(token, str) or token.count(".") != 2:
            raise RuntimeError("mint response rejected")
        if args.mode == "show":
            print("WARNING: the next line is a sensitive, short-lived JWT; do not log or share it.", file=sys.stderr)
        sys.stdout.write(token + "\n")
        return 0
    if args.command == "fault":
        if args.value is None:
            parser.error("fault requires a mode")
        request = {"command": "fault", "mode": args.value}
    else:
        if args.value is not None:
            parser.error("unexpected control argument")
        request = {"command": args.command}
    response = socket_request(request, args.timeout)
    safe = {key: value for key, value in response.items() if key != "token"}
    print(json.dumps(safe, separators=(",", ":"), sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(cli())
    except Exception as exc:
        print("fixture command failed: %s" % type(exc).__name__, file=sys.stderr)
        raise SystemExit(1)
